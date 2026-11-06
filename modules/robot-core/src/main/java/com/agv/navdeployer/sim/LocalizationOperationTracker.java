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

public class LocalizationOperationTracker implements RosbridgeHandler {

    private static final Logger log = LoggerFactory.getLogger(LocalizationOperationTracker.class);

    private static final int MAX_RECORDS = 256;

    private final SimAgvProperties props;
    private Function<String, CompletableFuture<Optional<LocalizationOperation>>> queryCaller;

    private final ConcurrentHashMap<String, LocalizationOperation> operations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<LocalizationOperation>> waiters = new ConcurrentHashMap<>();
    private volatile LocalizationOperation latest;

    public LocalizationOperationTracker(SimAgvProperties props) {
        this.props = props;
    }

    public void attachQueryCaller(Function<String, CompletableFuture<Optional<LocalizationOperation>>> queryCaller) {
        this.queryCaller = queryCaller;
    }

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
            log.info("localization operation {}: stage {} -> {}, message={}",
                    incoming.operationId(), previous.stage(), incoming.stage(), incoming.message());
        }
    }

    private void trimIfNeeded() {
        if (operations.size() <= MAX_RECORDS) {
            return;
        }
        List<Map.Entry<String, LocalizationOperation>> entries = new ArrayList<>(operations.entrySet());
        entries.sort((a, b) -> a.getValue().receivedAt().compareTo(b.getValue().receivedAt()));
        int toRemove = entries.size() - MAX_RECORDS;
        for (int i = 0; i < toRemove; i++) {
            operations.remove(entries.get(i).getKey());
        }
    }

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
                    return;
                }
                ingest(operation);
            });
        });
    }

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

    public LocalizationOperation get(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return null;
        }
        return operations.get(operationId);
    }

    public LocalizationOperation latest() {
        return latest;
    }

    public boolean hasRunningOperation() {
        for (LocalizationOperation operation : operations.values()) {
            if (!operation.isTerminal()) {
                return true;
            }
        }
        return false;
    }

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
