package com.agv.navdeployer.service;

import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.sim.LiveMapCache;
import com.agv.navdeployer.sim.LocalizationOperation;
import com.agv.navdeployer.sim.LocalizationOperationTracker;
import com.agv.navdeployer.sim.RosCommandDispatcher;
import com.agv.navdeployer.sim.RosCommandDispatcher.GetMapResult;
import com.agv.navdeployer.sim.RosCommandDispatcher.MapServiceResult;
import com.agv.navdeployer.sim.RosCommandDispatcher.RelocalizeResult;
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

@Service
public class MapModeTaskService {

    private static final Logger log = LoggerFactory.getLogger(MapModeTaskService.class);
    private static final int HISTORY_LIMIT = 100;
    private static final String MODE_NAVIGATION = "NAVIGATION";

    private final NavMapMapper navMapMapper;
    private final NavMapService navMapService;
    private final com.agv.navdeployer.metadata.service.MapMetaService mapMetaService;
    private final RosCommandDispatcher dispatcher;
    private final RosbridgeClient rosbridgeClient;
    private final SimAgvTelemetry telemetry;
    private final SimAgvProperties props;
    private final SimAgvSsePublisher ssePublisher;
    private final MoveTaskService moveTaskService;
    private final LocalizationOperationTracker operationTracker;

    private final AtomicLong idSequence = new AtomicLong();
    private final Deque<MapModeStateMachine> history = new ArrayDeque<>();
    private MapModeStateMachine current;
    private int lastPushedProgressVersion;

    private volatile String mappingPreMapName;

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
                              com.agv.navdeployer.metadata.service.MapMetaService mapMetaService,
                              RosCommandDispatcher dispatcher,
                              RosbridgeClient rosbridgeClient,
                              SimAgvTelemetry telemetry,
                              SimAgvProperties props,
                              SimAgvSsePublisher ssePublisher,
                              MoveTaskService moveTaskService,
                              LocalizationOperationTracker operationTracker) {
        this.navMapMapper = navMapMapper;
        this.navMapService = navMapService;
        this.mapMetaService = mapMetaService;
        this.dispatcher = dispatcher;
        this.rosbridgeClient = rosbridgeClient;
        this.telemetry = telemetry;
        this.props = props;
        this.ssePublisher = ssePublisher;
        this.moveTaskService = moveTaskService;
        this.operationTracker = operationTracker;
    }

    public MapModeTaskVO switchMap(Long navMapId, String robotMapNameOverride) {
        requireIdleAndGates(MODE_NAVIGATION, true);

        NavMap map = navMapMapper.selectById(navMapId);
        if (map == null) {
            throw new NotFoundException("地图 " + navMapId + " 不存在");
        }
        String robotMapName = resolveRobotMapName(map, robotMapNameOverride);
        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SWITCH_MAP,
                map.getId(), map.getMapName(), robotMapName,
                Instant.now().plusMillis(props.getModeTaskTimeoutMs()));
        return dispatchSwitchLike(machine, robotMapName);
    }

    public MapModeTaskVO switchV2Map(Long mapId, String robotMapNameOverride) {
        requireIdleAndGates(MODE_NAVIGATION, true);

        com.agv.navdeployer.metadata.entity.MapMeta map = mapMetaService.getMeta(mapId);
        if (map == null) {
            throw new NotFoundException("地图 " + mapId + " 不存在");
        }
        String robotMapName = robotMapNameOverride != null && !robotMapNameOverride.isBlank()
                ? robotMapNameOverride.trim()
                : (map.getRobotMapName() != null && !map.getRobotMapName().isBlank()
                        ? map.getRobotMapName().trim() : map.getMapName());
        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SWITCH_MAP,
                null, map.getId(), map.getMapName(), robotMapName,
                Instant.now().plusMillis(props.getModeTaskTimeoutMs()));
        return dispatchSwitchLike(machine, robotMapName);
    }

    public MapModeTaskVO switchNativeMap(String mapName, String robotMapName) {
        requireIdleAndGates(MODE_NAVIGATION, true);
        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SWITCH_MAP,
                null, mapName, robotMapName,
                Instant.now().plusMillis(props.getModeTaskTimeoutMs()));
        return dispatchSwitchLike(machine, robotMapName);
    }

    private MapModeTaskVO dispatchSwitchLike(MapModeStateMachine machine, String robotMapName) {
        requireValidRobotMapName(robotMapName);

        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        if (status != null && robotMapName.equals(status.mapName())) {
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
            bindOperation(machine, result.operationId());
        }

        return registerAndPublish(machine);
    }

    public MapModeTaskVO startMapping() {
        requireIdleAndGates(MODE_NAVIGATION, true);
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();

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

        mappingPreMapName = status == null ? null : status.mapName();
        if (mappingPreMapName != null && mappingPreMapName.isBlank()) {
            mappingPreMapName = null;
        }

        return registerAndPublish(machine);
    }

    public MapModeTaskVO discardMapping() {
        requireIdleAndGates("MAPPING");

        String target = mappingPreMapName;
        Long targetNavMapId = null;
        Long targetV2MapId = null;
        if (target == null) {
            com.agv.navdeployer.metadata.entity.MapMeta published = mapMetaService.findPublished();
            if (published != null) {
                target = published.getRobotMapName() != null && !published.getRobotMapName().isBlank()
                        ? published.getRobotMapName() : published.getMapName();
                targetV2MapId = published.getId();
            }
        } else {
            com.agv.navdeployer.metadata.entity.MapMeta v2Match = mapMetaService.findByUploadName(target);
            if (v2Match != null) {
                targetV2MapId = v2Match.getId();
            }
        }
        if (target == null) {
            NavMap active = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                    .eq(NavMap::getStatus, NavMap.STATUS_ACTIVE).last("LIMIT 1"));
            if (active != null) {
                target = active.getRobotMapName() != null && !active.getRobotMapName().isBlank()
                        ? active.getRobotMapName() : active.getMapName();
                targetNavMapId = active.getId();
            }
        } else if (targetV2MapId == null) {
            NavMap match = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                    .eq(NavMap::getRobotMapName, target)
                    .orderByDesc(NavMap::getId).last("LIMIT 1"));
            targetNavMapId = match == null ? null : match.getId();
        }
        if (target == null || target.isBlank()) {
            throw new IllegalStateException(
                    "建图前没有已定位地图（冷启动建图），无旧图可回，请使用「完成建图并保存」");
        }
        requireValidRobotMapName(target);

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.DISCARD_MAPPING,
                targetNavMapId, targetV2MapId, target, target,
                Instant.now().plusMillis(props.getModeTaskTimeoutMs()));

        MapServiceResult result;
        try {
            result = dispatcher.loadMap(target).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("load_map（放弃建图回旧图）调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝放弃建图回载旧图 " + target + ": " + result.message());
        }
        bindOperation(machine, result.operationId());

        return registerAndPublish(machine);
    }

    public MapModeTaskVO relocalize(String mapName, double x, double y, double yaw) {
        requireIdleAndGates(MODE_NAVIGATION, true);

        String target = mapName == null ? "" : mapName.trim();
        if (target.isEmpty()) {
            SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
            target = status == null ? null : status.mapName();
        }
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("无法确定目标地图（未指定 map_name 且 /agv/status 无当前地图），请稍后重试或显式指定地图");
        }

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.RELOCALIZE,
                null, target, target, Instant.now().plusMillis(props.getModeTaskTimeoutMs()));

        RelocalizeResult result;
        try {
            result = dispatcher.callRelocalize(target, x, y, yaw).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("relocalize 调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝重定位: " + result.message());
        }
        bindOperation(machine, result.operationId());

        return registerAndPublish(machine);
    }

    private void bindOperation(MapModeStateMachine machine, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            log.info("robot accepted {} without operation_id (legacy bridge), fall back to mode tracking",
                    machine.getType());
            return;
        }
        machine.attachOperation(operationId, null, null);
        Instant extended = Instant.now().plusMillis(props.getLocalizationTaskTimeoutMs());
        if (extended.isAfter(machine.getDeadlineAt())) {
            machine.extendDeadlineTo(extended);
        }
        operationTracker.refreshFromRobot(operationId);
    }

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

    public synchronized MapModeTaskVO currentTask() {
        return current == null ? null : MapModeTaskVO.from(current);
    }

    public synchronized boolean hasActiveTask() {
        return current != null && !current.isTerminal();
    }

    public synchronized List<MapModeTaskVO> recent(int limit) {
        int bounded = Math.clamp(limit, 1, HISTORY_LIMIT);
        List<MapModeTaskVO> result = new ArrayList<>();
        var iterator = history.descendingIterator();
        while (iterator.hasNext() && result.size() < bounded) {
            result.add(MapModeTaskVO.from(iterator.next()));
        }
        return result;
    }

    @Scheduled(fixedDelay = 500)
    public void pollStatus() {
        MapModeStateMachine machine;
        synchronized (this) {
            machine = current;
        }
        if (machine == null || machine.isTerminal()) {
            return;
        }

        Optional<MapModeStateMachine.Status> transition = Optional.empty();

        if (machine.hasOperation()) {
            LocalizationOperation operation = operationTracker.get(machine.getOperationId());
            if (operation != null) {
                transition = machine.onOperation(operation);
            }
        }

        if (transition.isEmpty()) {
            SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
            long staleLimit = Math.max(props.getStatusFreshMs() * 6, 30_000L);
            if (status == null) {
                transition = machine.onStatus(null, null, Long.MAX_VALUE, staleLimit);
            } else {
                transition = machine.onStatus(status.mode(), status.mapName(),
                        telemetry.statusAgeMs(), staleLimit);
            }
        }

        if (transition.isEmpty()) {
            transition = machine.onDeadline(Instant.now());
        }

        if (transition.isPresent()) {
            publishAndFinalize(machine);
            return;
        }

        if (machine.getProgressVersion() != lastPushedProgressVersion) {
            lastPushedProgressVersion = machine.getProgressVersion();
            ssePublisher.publishEvent("map-task", MapModeTaskVO.from(machine));
        }
    }

    private void requireIdleAndGates(String requiredMode) {
        requireIdleAndGates(requiredMode, false);
    }

    private void requireIdleAndGates(String requiredMode, boolean allowUnlocalizedColdStart) {
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
            log.info("websocket 已连接，但超过 {}s 未收到 /agv/status，机器人业务层不可达" +
                    "（agv_nav_server 未启动 / rosbridge topics_sub_glob 未放行 / status 话题名不匹配），拒绝执行", props.getStatusFreshMs() / 1000);
        }
        SimAgvTelemetry.StatusSnapshot status = telemetry.getStatus();
        if (status.mode() == null) {
            throw new IllegalStateException("机器人未上报 mode 字段（旧版 agv_bridge_v2），请升级机器人固件到 v0.3.0+");
        }
        if (!requiredMode.equals(status.mode())) {
            if (allowUnlocalizedColdStart && !status.poseInitialized()
                    && !"MAPPING".equals(status.mode()) && !"RELOCALIZING".equals(status.mode())) {
                // 未定位冷启动放行：正是要靠 load_map / start_mapping 把定位拉起来
                log.info("cold start accepted for mode task: mode={}, pose_initialized=false", status.mode());
            } else {
                String hint = MODE_NAVIGATION.equals(requiredMode)
                        ? "（切图/建图中），请等待回到 NAVIGATION"
                        : "，仅建图模式（MAPPING）下可执行，请先 start-mapping";
                throw new IllegalStateException("机器人当前 mode=" + status.mode() + hint);
            }
        }
        if (moveTaskService.active() != null) {
            throw new IllegalStateException("存在在途移动任务，请先取消或等待完成后再执行");
        }
    }

    private MapModeTaskVO registerAndPublish(MapModeStateMachine machine) {
        synchronized (this) {
            current = machine;
            history.addLast(machine);
            while (history.size() > HISTORY_LIMIT) {
                history.pollFirst();
            }
            lastPushedProgressVersion = machine.getProgressVersion();
        }
        publishAndFinalize(machine);
        log.info("map mode task created: id={}, type={}, map={}, status={}, operationId={}",
                machine.getId(), machine.getType(), machine.getRobotMapName(), machine.getStatus(),
                machine.getOperationId());
        return MapModeTaskVO.from(machine);
    }

    private void publishAndFinalize(MapModeStateMachine machine) {
        if (machine.getStatus() == MapModeStateMachine.Status.SUCCEEDED
                && (machine.getNavMapId() != null || machine.getV2MapId() != null)
                && (machine.getType() == MapModeStateMachine.Type.SWITCH_MAP
                        || machine.getType() == MapModeStateMachine.Type.DISCARD_MAPPING)) {
            if (machine.getV2MapId() != null) {
                try {
                    mapMetaService.publish(machine.getV2MapId(), "map-mode-task");
                    log.info("{} task succeeded: id={}, v2Map={} published as PUBLISHED",
                            machine.getType(), machine.getId(), machine.getV2MapId());
                } catch (Exception e) {
                    log.error("publish v2 map {} failed after {} success: {}",
                            machine.getV2MapId(), machine.getType(), e.getMessage(), e);
                    machine.fail((machine.getType() == MapModeStateMachine.Type.DISCARD_MAPPING
                            ? "放弃建图已回旧图" : "切图已成功") + "，但发布 V2 地图失败: " + e.getMessage());
                }
            } else if (machine.getNavMapId() != null) {
                try {
                    navMapService.activate(machine.getNavMapId());
                    log.info("{} task succeeded: id={}, map={} activated as ACTIVE",
                            machine.getType(), machine.getId(), machine.getMapName());
                } catch (Exception e) {
                    log.error("activate map {} failed after {} success: {}",
                            machine.getNavMapId(), machine.getType(), e.getMessage(), e);
                    machine.fail((machine.getType() == MapModeStateMachine.Type.DISCARD_MAPPING
                            ? "放弃建图已回旧图" : "切图已成功") + "，但激活地图档案失败: " + e.getMessage());
                }
            }
        } else if (machine.getStatus() == MapModeStateMachine.Status.FAILED) {
            log.warn("map mode task failed: id={}, type={}, map={}, reason={}",
                    machine.getId(), machine.getType(), machine.getRobotMapName(), machine.getErrorMessage());
        }

        if (machine.getStatus() == MapModeStateMachine.Status.SUCCEEDED
                && machine.getType() == MapModeStateMachine.Type.SAVE_MAP) {
            worker.execute(() -> syncRobotMapV2(machine));
        }
        ssePublisher.publishEvent("map-task", MapModeTaskVO.from(machine));
    }

    private void syncRobotMapV2(MapModeStateMachine machine) {
        String robotMapName = machine.getRobotMapName();
        try {
            LiveMapCache.OccupancyGrid grid = fetchRobotGrid(robotMapName);
            com.agv.navdeployer.metadata.entity.MapMeta existing = mapMetaService.findByUploadName(robotMapName);
            com.agv.navdeployer.metadata.vo.MapV2VO saved;
            if (existing != null) {
                saved = mapMetaService.replaceRobotGrid(existing.getId(), robotMapName, grid);
            } else {
                saved = mapMetaService.createFromRobotGrid(robotMapName, grid);
            }
            mapMetaService.publish(saved.id(), "map-mode-sync");
            log.info("v2 map synced from robot after save: mapId={}, robotMapName={}",
                    saved.id(), robotMapName);
            ssePublisher.publishEvent("map-sync", Map.of(
                    "success", true, "mapId", saved.id(), "mapName", saved.mapName(),
                    "robotMapName", robotMapName));
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : e;
            log.error("sync v2 map {} from robot failed: {}", robotMapName, cause.getMessage(), cause);
            ssePublisher.publishEvent("map-sync", Map.of(
                    "success", false, "robotMapName", robotMapName,
                    "message", String.valueOf(cause.getMessage())));
        }
    }

    private LiveMapCache.OccupancyGrid fetchRobotGrid(String robotMapName) {
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
        return grid;
    }

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

    public NavMapVO importRobotMap(String robotMapName) {
        String target = robotMapName == null ? "" : robotMapName.trim();
        LiveMapCache.OccupancyGrid grid = fetchRobotGrid(target);
        NavMapVO saved = navMapService.saveRobotGrid(target, target, grid);

        boolean robotOnThisMap = telemetry.isStatusFresh(props.getStatusFreshMs())
                && telemetry.getStatus() != null
                && target.equals(telemetry.getStatus().mapName());
        NavMapVO result = robotOnThisMap ? navMapService.activate(saved.getId()) : saved;
        log.info("map imported from robot: navMapId={}, robotMapName={}, activated={}",
                result.getId(), target, robotOnThisMap);
        return result;
    }

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
        com.agv.navdeployer.metadata.entity.MapMeta published = mapMetaService.findPublished();
        if (published != null) {
            String ref = published.getRobotMapName() != null && !published.getRobotMapName().isBlank()
                    ? published.getRobotMapName() : published.getMapName();
            if (robotMap.equals(ref)) {
                return;
            }
            com.agv.navdeployer.metadata.entity.MapMeta match = mapMetaService.findByUploadName(robotMap);
            if (match == null || match.getId().equals(published.getId())) {
                return;
            }
            try {
                mapMetaService.publish(match.getId(), "map-align");
            } catch (Exception e) {
                log.warn("align: publish v2 map {} failed: {}", match.getId(), e.getMessage());
                return;
            }
            log.info("v2 map aligned to robot truth: robotMap={} -> mapId={} PUBLISHED",
                    robotMap, match.getId());
            ssePublisher.publishEvent("map-align", Map.of(
                    "aligned", true, "mapId", match.getId(), "robotMapName", robotMap));
            return;
        }
        alignV1WithRobot(robotMap);
    }

    private void alignV1WithRobot(String robotMap) {
        NavMap active = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getStatus, NavMap.STATUS_ACTIVE).last("LIMIT 1"));
        if (active != null && robotMap.equals(active.getRobotMapName())) {
            return;
        }
        NavMap match = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getRobotMapName, robotMap)
                .orderByDesc(NavMap::getId).last("LIMIT 1"));
        if (match == null) {
            return;
        }
        navMapService.activate(match.getId());
        log.info("map aligned to robot truth: robotMap={} -> navMapId={} ACTIVE", robotMap, match.getId());
        ssePublisher.publishEvent("map-align", Map.of(
                "aligned", true, "navMapId", match.getId(), "robotMapName", robotMap));
    }

    public Map<String, Object> computeAlignment() {
        String robotMap = null;
        if (telemetry.isStatusFresh(props.getStatusFreshMs()) && telemetry.getStatus() != null) {
            robotMap = telemetry.getStatus().mapName();
        }

        com.agv.navdeployer.metadata.entity.MapMeta published = mapMetaService.findPublished();
        if (published != null) {
            String ref = published.getRobotMapName() != null && !published.getRobotMapName().isBlank()
                    ? published.getRobotMapName() : published.getMapName();
            String state;
            if (robotMap == null || robotMap.isBlank()) {
                state = "UNKNOWN";
            } else if (robotMap.equals(ref)) {
                state = "ALIGNED";
            } else if (mapMetaService.findByUploadName(robotMap) == null) {
                state = "ROBOT_MAP_MISSING";
            } else {
                state = "MISMATCH";
            }
            Map<String, Object> align = new java.util.LinkedHashMap<>();
            align.put("robot_map_name", robotMap);
            align.put("state", state);
            align.put("published_source", "v2");
            align.put("active_map_id", published.getId());
            align.put("active_map_name", published.getMapName());
            return align;
        }
        return computeV1Alignment(robotMap);
    }

    private Map<String, Object> computeV1Alignment(String robotMap) {
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
        align.put("published_source", "v1");
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

    private static void requireValidRobotMapName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128
                || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("机器人地图名非法（非空且不含路径分隔符）: '" + name + "'");
        }
    }
}
