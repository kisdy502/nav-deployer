package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 定位操作（load_map / relocalize）闭环跟踪器。
 *
 * 数据源（机器人侧契约 docs/localization_operation_api.md）：
 * - 话题 /agv/localization_operation：阶段变化即时发布 + 约每墙钟秒重发最新操作
 *   （reliable + transient_local QoS），本类作为 RosbridgeHandler 持续接收；
 * - 服务 /agv/get_localization_operation：按 operation_id 查询（空串=最近一次），
 *   用于「受理后立即补齐」（话题可能先于服务响应到达）与「WS 重连恢复」。
 *
 * 一致性规则：终态结论具有权威性且不可回退——已 SUCCEEDED / FAILED / TIMED_OUT 的
 * 记录忽略迟到的 RUNNING 回调；新旧记录只按 operation_id 对应，绝不互相覆盖。
 *
 * 重连策略：不自动重新发送切图/重定位（机器人侧新请求会被明确拒绝），
 * 只对未终态操作与最近一次操作发起查询补齐。
 *
 * 回调线程约定：本类 onMessage / onConnected 运行在 WS IO 线程，内部只做内存写，
 * 查询经 CompletableFuture 异步进行，不阻塞 IO 线程。
 */
public class LocalizationOperationTracker implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(LocalizationOperationTracker.class);

    /** 内存保留条数上限（机器人侧保留 128 条，上位机同量级即可）。 */
    private static final int MAX_RECORDS = 256;

    private final SimAgvProperties props;
    /** 查询执行器（由装配方注入 dispatcher::queryLocalizationOperation），避免构造环。 */
    private Function<String, CompletableFuture<Optional<LocalizationOperation>>> queryCaller;

    private final ConcurrentHashMap<String, LocalizationOperation> operations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<LocalizationOperation>> waiters = new ConcurrentHashMap<>();
    private volatile LocalizationOperation latest;

    public LocalizationOperationTracker(SimAgvProperties props) {
        this.props = props;
    }

    /** 由装配方注入查询能力（RosbridgeConfig 中 dispatcher 先于 tracker 创建）。 */
    public void attachQueryCaller(Function<String, CompletableFuture<Optional<LocalizationOperation>>> queryCaller) {
        this.queryCaller = queryCaller;
    }

    // ==================== 接收与合并 ====================

    @Override
    public void onMessage(JsonNode message) {
        if (!"publish".equals(message.path("op").asText(""))) {
            return;
        }
        if (!props.getLocalizationOperationTopic().equals(message.path("topic").asText(""))) {
            return;
        }
        LocalizationOperation operation = LocalizationOperation.fromMessage(message.path("msg"));
        if (operation != null) {
            ingest(operation);
        }
    }

    /** 合并一条操作状态（话题或查询结果同入口）；终态记录不被迟到回调改写。 */
    public void ingest(LocalizationOperation incoming) {
        LocalizationOperation previous = operations.get(incoming.operationId());
        if (previous != null && previous.isTerminal() && !incoming.isTerminal()) {
            log.debug("ignore late non-terminal callback for finished operation {} ({}), stage={}",
                    incoming.operationId(), previous.state(), incoming.stage());
            return;
        }
        boolean firstSeen = previous == null;
        boolean newlyTerminal = incoming.isTerminal() && (previous == null || !previous.isTerminal());
        operations.put(incoming.operationId(), incoming);
        latest = incoming;
        trimIfNeeded();
        if (incoming.isTerminal()) {
            CompletableFuture<LocalizationOperation> waiter = waiters.remove(incoming.operationId());
            if (waiter != null) {
                waiter.complete(incoming);
            }
        }
        // 话题约每墙钟秒重发最新操作（心跳）：内存照常刷新，但只在状态真正变化时打日志，
        // 否则终态后的每秒重发会持续刷屏（同一条 SUCCEEDED/FAILED 结论 1 条/秒）
        if (firstSeen) {
            log.info("localization operation {}: type={}, map={}, state={}, stage={}, message={}",
                    incoming.operationId(), incoming.operationType(), incoming.mapName(),
                    incoming.state(), incoming.stage(), incoming.message());
        } else if (newlyTerminal) {
            log.info("localization operation {}: type={}, map={}, state={}, elapsed={}s, message={}",
                    incoming.operationId(), incoming.operationType(), incoming.mapName(),
                    incoming.state(), String.format("%.1f", incoming.elapsedSec()), incoming.message());
        } else if (incoming.isTerminal()) {
            log.debug("localization operation {} terminal heartbeat: {}",
                    incoming.operationId(), incoming.state());
        } else if (previous != null && !incoming.stage().equals(previous.stage())) {
            // RUNNING 阶段推进（停止定位 → 启动定位 → 等待收敛），每个阶段只打一条
            log.info("localization operation {}: stage {} -> {}, message={}",
                    incoming.operationId(), previous.stage(), incoming.stage(), incoming.message());
        }
    }

    private void trimIfNeeded() {
        if (operations.size() <= MAX_RECORDS) {
            return;
        }
        // 超限时优先淘汰非终态之外的最早记录：简单策略——按接收时间留最新 MAX_RECORDS 条
        List<Map.Entry<String, LocalizationOperation>> entries = new ArrayList<>(operations.entrySet());
        entries.sort((a, b) -> a.getValue().receivedAt().compareTo(b.getValue().receivedAt()));
        int toRemove = entries.size() - MAX_RECORDS;
        for (int i = 0; i < toRemove; i++) {
            operations.remove(entries.get(i).getKey());
        }
    }

    // ==================== 查询与重连恢复 ====================

    /**
     * 主动向机器人查询一条操作记录并合并。使用场景：
     * 1. 服务受理拿到 operation_id 后立即查一次（话题可能先到或丢失，见文档 §2.5）；
     * 2. WS 重连后对未终态操作补齐结论。
     * found=false（bridge 重启/记录被淘汰）时返回 empty——结果未知，由业务决定兜底策略。
     */
    public CompletableFuture<Optional<LocalizationOperation>> refreshFromRobot(String operationId) {
        Function<String, CompletableFuture<Optional<LocalizationOperation>>> caller = queryCaller;
        if (caller == null || operationId == null || operationId.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String target = operationId;
        return caller.apply(target).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("query localization operation {} failed: {}", target, error.getMessage());
                return;
            }
            result.ifPresent(operation -> {
                if (!target.equals(operation.operationId())) {
                    return; // 防御：串号记录直接丢弃
                }
                ingest(operation);
            });
        });
    }

    /** WS 重连（含首次连接）：对未终态操作与最近一次操作查询补齐，不重发指令。 */
    @Override
    public void onConnected() {
        List<String> toRefresh = new ArrayList<>();
        for (LocalizationOperation operation : operations.values()) {
            if (!operation.isTerminal()) {
                toRefresh.add(operation.operationId());
            }
        }
        LocalizationOperation latestSnapshot = latest;
        if (latestSnapshot != null && !toRefresh.contains(latestSnapshot.operationId())) {
            toRefresh.add(latestSnapshot.operationId());
        }
        if (toRefresh.isEmpty()) {
            return;
        }
        log.info("rosbridge reconnected, refreshing {} localization operation(s)", toRefresh.size());
        for (String operationId : toRefresh) {
            refreshFromRobot(operationId);
        }
    }

    // ==================== 读取与等待 ====================

    public LocalizationOperation get(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return null;
        }
        return operations.get(operationId);
    }

    public LocalizationOperation latest() {
        return latest;
    }

    /** 是否存在未终态的定位操作（受理后仍在执行的权威判断）。 */
    public boolean hasRunningOperation() {
        for (LocalizationOperation operation : operations.values()) {
            if (!operation.isTerminal()) {
                return true;
            }
        }
        return false;
    }

    /** 等待某操作到达终态；超时以 TimeoutException 异常完成（不取消机器人侧流程，仅放弃等待）。 */
    public CompletableFuture<LocalizationOperation> awaitTerminal(String operationId, long timeoutMs) {
        LocalizationOperation current = get(operationId);
        if (current != null && current.isTerminal()) {
            return CompletableFuture.completedFuture(current);
        }
        CompletableFuture<LocalizationOperation> future = waiters
                .computeIfAbsent(operationId, key -> new CompletableFuture<>())
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        future.whenComplete((result, error) -> waiters.remove(operationId));
        // 注册等待者期间可能刚好终态到达：双重检查补齐竞态窗口
        LocalizationOperation afterRegister = get(operationId);
        if (afterRegister != null && afterRegister.isTerminal()) {
            future.complete(afterRegister);
        }
        return future;
    }
}
