package com.agv.navdeployer.service;

import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.sim.LiveMapCache;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.RosCommandDispatcher.GetMapResult;
import com.agv.navdeployer.sim.RosCommandDispatcher.MapServiceResult;
import com.agv.navdeployer.sim.RosbridgeClient;
import com.agv.navdeployer.sim.SimAgvProperties;
import com.agv.navdeployer.sim.SimAgvSsePublisher;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.agv.navdeployer.vo.MapModeTaskVO;
import com.agv.navdeployer.vo.NavMapVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地图模式任务编排：切图（SWITCH_MAP），P3 扩展建图（START_MAPPING）/保存（SAVE_MAP）。
 *
 * 一致性原则：机器人 /agv/status 是唯一事实源。
 * - 业务闸门全部读镜像（新鲜度 + mode + 无在途任务），不读本地 DB 状态；
 * - load_map 服务响应只代表「已受理」，终态由 {@link MapModeStateMachine} 跟踪 mode 流转得出；
 * - 只有切图成功（mode 回到 NAVIGATION 且 map_name=目标）才把 DB ACTIVE 对齐到该地图，
 *   失败/超时不动 DB，与机器人状态保持一致。
 *
 * 任务为内存态（秒~分钟级生命周期）：应用重启任务即失联，由机器人侧 mode 自行收敛，
 * DB ACTIVE 不会被错误的中间态污染。
 */
@Service
public class MapModeTaskService {

    private static final Logger log = LoggerFactory.getLogger(MapModeTaskService.class);
    private static final int HISTORY_LIMIT = 100;
    private static final String MODE_NAVIGATION = "NAVIGATION";

    private final NavMapMapper navMapMapper;
    private final NavMapService navMapService;
    private final RosCommandDispatcher dispatcher;
    private final RosbridgeClient rosbridgeClient;
    private final SimAgvTelemetry telemetry;
    private final SimAgvProperties props;
    private final SimAgvSsePublisher ssePublisher;
    private final MoveTaskService moveTaskService;

    private final AtomicLong idSequence = new AtomicLong();
    private final Deque<MapModeStateMachine> history = new ArrayDeque<>();
    private MapModeStateMachine current;

    /** 保存成功后的栅格同步线程（get_map 数 MB 不阻塞调度线程）。 */
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "map-mode-worker");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    public MapModeTaskService(NavMapMapper navMapMapper,
                              NavMapService navMapService,
                              RosCommandDispatcher dispatcher,
                              RosbridgeClient rosbridgeClient,
                              SimAgvTelemetry telemetry,
                              SimAgvProperties props,
                              SimAgvSsePublisher ssePublisher,
                              MoveTaskService moveTaskService) {
        this.navMapMapper = navMapMapper;
        this.navMapService = navMapService;
        this.dispatcher = dispatcher;
        this.rosbridgeClient = rosbridgeClient;
        this.telemetry = telemetry;
        this.props = props;
        this.ssePublisher = ssePublisher;
        this.moveTaskService = moveTaskService;
    }

    /** 切换机器人定位地图（调 /agv/load_map，受理后由状态机跟踪收敛）。 */
    public MapModeTaskVO switchMap(Long navMapId, String robotMapNameOverride) {
        requireIdleAndGates(MODE_NAVIGATION);

        NavMap map = navMapMapper.selectById(navMapId);
        if (map == null) {
            throw new NotFoundException("地图 " + navMapId + " 不存在");
        }
        String robotMapName = resolveRobotMapName(map, robotMapNameOverride);
        requireValidRobotMapName(robotMapName);

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SWITCH_MAP,
                map.getId(), map.getMapName(), robotMapName,
                Instant.now().plusMillis(props.getModeTaskTimeoutMs()));

        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        if (robotMapName.equals(status.mapName())) {
            // 已在目标地图：无需重启定位，直接成功并对齐 ACTIVE
            machine.onStatus(MODE_NAVIGATION, robotMapName, 0, Long.MAX_VALUE);
        } else {
            MapServiceResult result;
            try {
                result = dispatcher.loadMap(robotMapName).join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IllegalStateException("load_map 调用失败: " + cause.getMessage(), cause);
            }
            if (!result.success()) {
                throw new IllegalStateException("机器人拒绝切图: " + result.message());
            }
        }

        return registerAndPublish(machine);
    }

    /** 进入在线建图（调 /agv/start_mapping，停托管定位、拉起建图；遥控不受影响）。 */
    public MapModeTaskVO startMapping() {
        requireIdleAndGates(MODE_NAVIGATION);

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.START_MAPPING,
                null, null, null,
                Instant.now().plusMillis(props.getModeTaskTimeoutMs()));

        MapServiceResult result;
        try {
            result = dispatcher.startMapping().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("start_mapping 调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝建图: " + result.message());
        }

        return registerAndPublish(machine);
    }

    /**
     * 保存建图并回到定位（调 /agv/save_map）。
     * 成功（mode 回到 NAVIGATION 且 map_name=目标）后自动 get_map 同步栅格入库并激活。
     */
    public MapModeTaskVO saveMap(String mapName) {
        String target = mapName == null ? "" : mapName.trim();
        requireValidRobotMapName(target);
        requireIdleAndGates("MAPPING");

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SAVE_MAP,
                null, target, target,
                Instant.now().plusMillis(props.getModeTaskTimeoutMs()));

        MapServiceResult result;
        try {
            result = dispatcher.saveMap(target).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("save_map 调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝保存: " + result.message());
        }

        return registerAndPublish(machine);
    }

    /** 当前任务（终态保留供查询，无任务返回 null）。 */
    public synchronized MapModeTaskVO currentTask() {
        return current == null ? null : MapModeTaskVO.from(current);
    }

    /** 是否有非终态任务在途。 */
    public synchronized boolean hasActiveTask() {
        return current != null && !current.isTerminal();
    }

    /** 最近 N 条任务历史（新在前）。 */
    public synchronized List<MapModeTaskVO> recent(int limit) {
        int bounded = Math.clamp(limit, 1, HISTORY_LIMIT);
        List<MapModeTaskVO> result = new ArrayList<>();
        var iterator = history.descendingIterator();
        while (iterator.hasNext() && result.size() < bounded) {
            result.add(MapModeTaskVO.from(iterator.next()));
        }
        return result;
    }

    /** 轮询 /agv/status 镜像驱动状态机；deadline 到点由同一节拍兜底。 */
    @Scheduled(fixedDelay = 500)
    public void pollStatus() {
        MapModeStateMachine machine;
        synchronized (this) {
            machine = current;
        }
        if (machine == null || machine.isTerminal()) {
            return;
        }

        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        long staleLimit = Math.max(props.getStatusFreshMs() * 6, 30_000L);
        Optional<MapModeStateMachine.Status> transition;
        if (status == null) {
            transition = machine.onStatus(null, null, Long.MAX_VALUE, staleLimit);
        } else {
            transition = machine.onStatus(status.mode(), status.mapName(),
                    telemetry.statusAgeMs(), staleLimit);
        }
        if (transition.isEmpty()) {
            transition = machine.onDeadline(Instant.now());
        }
        transition.ifPresent(next -> publishAndFinalize(machine));
    }

    /** 公共闸门：无在途任务 + 连接正常 + 状态新鲜 + mode 等于要求值（全部读机器人镜像）。 */
    private void requireIdleAndGates(String requiredMode) {
        synchronized (this) {
            if (current != null && !current.isTerminal()) {
                throw new IllegalStateException(
                        "已有模式任务在途（id=" + current.getId() + ", status=" + current.getStatus()
                                + "），请等待完成后再操作");
            }
        }
        if (!rosbridgeClient.isConnected()) {
            throw new IllegalStateException("rosbridge 未连接，无法执行模式任务");
        }
        if (!telemetry.isStatusFresh(props.getStatusFreshMs())) {
            throw new IllegalStateException("机器人状态不新鲜（未连接或超 "
                    + props.getStatusFreshMs() / 1000 + "s 未上报），视为 UNKNOWN，拒绝执行");
        }
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        if (status.mode() == null) {
            throw new IllegalStateException("机器人未上报 mode 字段（旧版 agv_bridge_v2），请升级机器人固件到 v0.3.0+");
        }
        if (!requiredMode.equals(status.mode())) {
            String hint = MODE_NAVIGATION.equals(requiredMode)
                    ? "（切图/建图中），请等待回到 NAVIGATION"
                    : "，仅建图模式（MAPPING）下可执行，请先 start-mapping";
            throw new IllegalStateException("机器人当前 mode=" + status.mode() + hint);
        }
        if (moveTaskService.active() != null) {
            throw new IllegalStateException("存在在途移动任务，请先取消或等待完成后再执行");
        }
    }

    /** 注册为当前任务并发布首条事件。 */
    private MapModeTaskVO registerAndPublish(MapModeStateMachine machine) {
        synchronized (this) {
            current = machine;
            history.addLast(machine);
            while (history.size() > HISTORY_LIMIT) {
                history.pollFirst();
            }
        }
        publishAndFinalize(machine);
        log.info("map mode task created: id={}, type={}, map={}, status={}",
                machine.getId(), machine.getType(), machine.getRobotMapName(), machine.getStatus());
        return MapModeTaskVO.from(machine);
    }

    /** 状态迁移副作用：成功后对齐 DB ACTIVE（对齐失败则任务降级为 FAILED），SSE 推送。 */
    private void publishAndFinalize(MapModeStateMachine machine) {
        if (machine.getStatus() == MapModeStateMachine.Status.SUCCEEDED
                && machine.getNavMapId() != null) {
            // SWITCH_MAP / SAVE_MAP 成功后对齐档案（SAVE_MAP 的 navMapId 由同步阶段回填前为 null，
            // 激活放在同步完成处；SWITCH_MAP 在此直接激活）
            if (machine.getType() == MapModeStateMachine.Type.SWITCH_MAP) {
                try {
                    navMapService.activate(machine.getNavMapId());
                    log.info("switch map task succeeded: id={}, map={} activated as ACTIVE",
                            machine.getId(), machine.getMapName());
                } catch (Exception e) {
                    log.error("activate map {} failed after switch success: {}",
                            machine.getNavMapId(), e.getMessage(), e);
                    machine.fail("切图已成功，但激活地图档案失败: " + e.getMessage());
                }
            }
        } else if (machine.getStatus() == MapModeStateMachine.Status.FAILED) {
            log.warn("map mode task failed: id={}, type={}, map={}, reason={}",
                    machine.getId(), machine.getType(), machine.getRobotMapName(), machine.getErrorMessage());
        }

        if (machine.getStatus() == MapModeStateMachine.Status.SUCCEEDED
                && machine.getType() == MapModeStateMachine.Type.SAVE_MAP) {
            // 保存成功的收尾：拉取栅格入库 + 激活，走 worker 线程避免阻塞调度
            worker.execute(() -> syncRobotMap(machine));
        }
        ssePublisher.publishEvent("map-task", MapModeTaskVO.from(machine));
    }

    /** SAVE_MAP 成功后的机器人 -> 上位机同步：get_map 拉栅格 -> 入库(upsert) -> 激活。 */
    private void syncRobotMap(MapModeStateMachine machine) {
        String robotMapName = machine.getRobotMapName();
        try {
            NavMapVO saved = importRobotMap(robotMapName);
            log.info("map synced from robot after save: navMapId={}, robotMapName={}",
                    saved.getId(), robotMapName);
            ssePublisher.publishEvent("map-sync", Map.of(
                    "success", true, "navMapId", saved.getId(), "robotMapName", robotMapName));
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : e;
            log.error("sync map {} from robot failed: {}", robotMapName, cause.getMessage(), cause);
            ssePublisher.publishEvent("map-sync", Map.of(
                    "success", false, "robotMapName", robotMapName,
                    "message", String.valueOf(cause.getMessage())));
        }
    }

    /** 机器人侧现有地图列表（maps_dir 下 pgm+yaml 齐全的地图名）。 */
    public List<String> listRobotMaps() {
        requireConnection();
        RosCommandDispatcher.ListMapsResult result;
        try {
            result = dispatcher.listMaps().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("list_maps 调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝 list_maps: " + result.message());
        }
        return result.mapNames();
    }

    /**
     * 手动从机器人导入地图（命令行 save_map、历史地图等场景）：
     * get_map 拉栅格 -> 入库（同名 upsert，source=ROBOT_SYNC）。
     * 激活策略：仅当机器人当前就在这张图上时才置 ACTIVE（机器人是唯一事实源，
     * 导入一张机器人没在用的图不应改变部署状态）；否则保持 DRAFT，
     * 由 {@link #alignActiveMapWithRobot()} 周期对账兜底。
     */
    public NavMapVO importRobotMap(String robotMapName) {
        String target = robotMapName == null ? "" : robotMapName.trim();
        requireValidRobotMapName(target);
        requireConnection();

        GetMapResult getMapResult;
        try {
            getMapResult = dispatcher.getMap(target).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("get_map 调用失败: " + cause.getMessage(), cause);
        }
        if (!getMapResult.success() || getMapResult.grid() == null) {
            throw new IllegalStateException("get_map 失败: "
                    + (getMapResult.message() == null || getMapResult.message().isBlank()
                            ? "无栅格数据" : getMapResult.message()));
        }
        LiveMapCache.OccupancyGrid grid = LiveMapCache.parse(getMapResult.grid());
        if (grid == null) {
            throw new IllegalStateException("get_map 返回的栅格数据非法（几何不一致）");
        }
        NavMapVO saved = navMapService.saveRobotGrid(target, target, grid);

        // 激活策略：机器人当前就在这张图上才置 ACTIVE（机器人是唯一事实源）
        boolean robotOnThisMap = telemetry.isStatusFresh(props.getStatusFreshMs())
                && telemetry.getStatus() != null
                && target.equals(telemetry.getStatus().mapName());
        NavMapVO result = robotOnThisMap ? navMapService.activate(saved.getId()) : saved;
        log.info("map imported from robot: navMapId={}, robotMapName={}, activated={}",
                result.getId(), target, robotOnThisMap);
        return result;
    }

    /**
     * 周期对账（对齐「机器人是唯一事实源」）：机器人 map_name 与 DB ACTIVE 不一致时，
     * 若库中存在该地图档案则自动对齐；不存在则只暴露状态，由前端提示导入。
     */
    @Scheduled(fixedDelay = 5000)
    public void alignActiveMapWithRobot() {
        if (!telemetry.isStatusFresh(props.getStatusFreshMs())) {
            return;
        }
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        String robotMap = status.mapName();
        if (robotMap == null || robotMap.isBlank()) {
            return;
        }
        NavMap active = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getStatus, NavMap.STATUS_ACTIVE).last("LIMIT 1"));
        if (active != null && robotMap.equals(active.getRobotMapName())) {
            return; // 已对齐
        }
        NavMap match = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getRobotMapName, robotMap)
                .orderByDesc(NavMap::getId).last("LIMIT 1"));
        if (match == null) {
            return; // 库里没有机器人当前地图，无法对齐（snapshot 会暴露 ROBOT_MAP_MISSING）
        }
        navMapService.activate(match.getId());
        log.info("map aligned to robot truth: robotMap={} -> navMapId={} ACTIVE", robotMap, match.getId());
        ssePublisher.publishEvent("map-align", Map.of(
                "aligned", true, "navMapId", match.getId(), "robotMapName", robotMap));
    }

    /**
     * 机器人当前地图与档案库的对齐状态（/robot/snapshot 的 map_align 字段）。
     * state：ALIGNED / MISMATCH（库里匹配但非 ACTIVE）/ ROBOT_MAP_MISSING（库里没有）/ UNKNOWN（未上报或状态过期）
     */
    public Map<String, Object> computeAlignment() {
        String robotMap = null;
        if (telemetry.isStatusFresh(props.getStatusFreshMs()) && telemetry.getStatus() != null) {
            robotMap = telemetry.getStatus().mapName();
        }
        NavMap active = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getStatus, NavMap.STATUS_ACTIVE).last("LIMIT 1"));
        NavMap robotRecord = null;
        if (robotMap != null && !robotMap.isBlank()) {
            robotRecord = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                    .eq(NavMap::getRobotMapName, robotMap)
                    .orderByDesc(NavMap::getId).last("LIMIT 1"));
        }

        String state;
        if (robotMap == null || robotMap.isBlank()) {
            state = "UNKNOWN";
        } else if (robotRecord == null) {
            state = "ROBOT_MAP_MISSING";
        } else if (active != null && (robotRecord.getId().equals(active.getId())
                || robotMap.equals(active.getRobotMapName()))) {
            state = "ALIGNED";
        } else {
            state = "MISMATCH";
        }

        Map<String, Object> align = new java.util.LinkedHashMap<>();
        align.put("robot_map_name", robotMap);
        align.put("state", state);
        align.put("robot_map_record_id", robotRecord == null ? null : robotRecord.getId());
        align.put("active_map_id", active == null ? null : active.getId());
        align.put("active_map_name", active == null ? null : active.getMapName());
        return align;
    }

    private void requireConnection() {
        if (!rosbridgeClient.isConnected()) {
            throw new IllegalStateException("rosbridge 未连接，无法访问机器人地图");
        }
    }

    private static String resolveRobotMapName(NavMap map, String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        if (map.getRobotMapName() != null && !map.getRobotMapName().isBlank()) {
            return map.getRobotMapName().trim();
        }
        return map.getMapName();
    }

    /** 与机器人侧 MapFileManager::isValidMapName 同构：非空、无路径分隔符、无 ".."。 */
    private static void requireValidRobotMapName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128
                || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("机器人地图名非法（非空且不含路径分隔符）: '" + name + "'");
        }
    }
}
