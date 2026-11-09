package com.agv.navdeployer.task.application;

import com.agv.navdeployer.common.error.BusinessException;
import com.agv.navdeployer.common.robot.RobotGrid;
import com.agv.navdeployer.map.application.MapMetaService;
import com.agv.navdeployer.map.application.dto.MapV2DTO;
import com.agv.navdeployer.map.domain.MapMeta;
import com.agv.navdeployer.task.application.dto.MapModeTaskDTO;
import com.agv.navdeployer.task.domain.mapmodetask.MapModeStateMachine;
import com.agv.navdeployer.task.domain.movetask.TaskErrorCode;
import com.agv.navdeployer.task.domain.port.MapCmdResult;
import com.agv.navdeployer.task.domain.port.RobotCommandPort;
import com.agv.navdeployer.task.domain.port.RobotConnectionPort;
import com.agv.navdeployer.task.domain.port.RobotEventPort;
import com.agv.navdeployer.task.domain.port.RobotOperationPort;
import com.agv.navdeployer.task.domain.port.RobotTelemetryPort;
import com.agv.navdeployer.task.domain.port.RobotTuning;
import com.agv.navdeployer.task.infrastructure.robot.RobotTuningProvider;
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

    private final MapMetaService mapMetaService;
    private final RobotCommandPort robotCommands;
    private final RobotConnectionPort robotConnection;
    private final RobotTelemetryPort telemetry;
    private final RobotEventPort events;
    private final RobotTuning tuning;
    private final MoveTaskService moveTaskService;
    private final RobotOperationPort operations;

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

    public MapModeTaskService(MapMetaService mapMetaService,
                              RobotCommandPort robotCommands,
                              RobotConnectionPort robotConnection,
                              RobotTelemetryPort telemetry,
                              RobotEventPort events,
                              RobotTuningProvider tuningProvider,
                              MoveTaskService moveTaskService,
                              RobotOperationPort operations) {
        this.mapMetaService = mapMetaService;
        this.robotCommands = robotCommands;
        this.robotConnection = robotConnection;
        this.telemetry = telemetry;
        this.events = events;
        this.tuning = tuningProvider.tuning();
        this.moveTaskService = moveTaskService;
        this.operations = operations;
    }

    public MapModeTaskDTO switchV2Map(Long mapId, String robotMapNameOverride) {
        requireIdleAndGates(MODE_NAVIGATION, true);

        MapMeta map = mapMetaService.getMeta(mapId);
        if (map == null) {
            throw new BusinessException(TaskErrorCode.MOVE_TASK_NOT_FOUND, "地图 " + mapId + " 不存在");
        }
        String robotMapName = robotMapNameOverride != null && !robotMapNameOverride.isBlank()
                ? robotMapNameOverride.trim()
                : (map.getRobotMapName() != null && !map.getRobotMapName().isBlank()
                        ? map.getRobotMapName().trim() : map.getMapName());
        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SWITCH_MAP,
                map.getId(), map.getMapName(), robotMapName,
                Instant.now().plusMillis(tuning.modeTaskTimeoutMs()));
        return dispatchSwitchLike(machine, robotMapName);
    }

    public MapModeTaskDTO switchNativeMap(String mapName, String robotMapName) {
        requireIdleAndGates(MODE_NAVIGATION, true);
        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SWITCH_MAP,
                null, mapName, robotMapName,
                Instant.now().plusMillis(tuning.modeTaskTimeoutMs()));
        return dispatchSwitchLike(machine, robotMapName);
    }

    private MapModeTaskDTO dispatchSwitchLike(MapModeStateMachine machine, String robotMapName) {
        requireValidRobotMapName(robotMapName);

        var status = telemetry.status();
        if (status != null && robotMapName.equals(status.mapName())) {
            machine.onStatus(MODE_NAVIGATION, robotMapName, 0, Long.MAX_VALUE);
        } else {
            MapCmdResult result;
            try {
                result = robotCommands.loadMap(robotMapName).join();
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

    public MapModeTaskDTO startMapping() {
        requireIdleAndGates(MODE_NAVIGATION, true);
        var status = telemetry.status();

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.START_MAPPING,
                null, null, null,
                Instant.now().plusMillis(tuning.modeTaskTimeoutMs()));

        MapCmdResult result;
        try {
            result = robotCommands.startMapping().join();
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

    public MapModeTaskDTO discardMapping() {
        requireIdleAndGates("MAPPING");

        // 机器人新固件在 MAPPING 态拒绝 load_map，放弃建图必须走 /agv/cancel_mapping：
        // 机器人侧自记录建图前地图名并恢复定位，响应携带 map_name 与 operation_id（权威终态通道）
        MapCmdResult result;
        try {
            result = robotCommands.cancelMapping().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("cancel_mapping 调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝放弃建图: " + result.message());
        }
        String target = result.mapName() == null || result.mapName().isBlank()
                ? (mappingPreMapName == null ? "" : mappingPreMapName) : result.mapName();
        Long targetV2MapId = null;
        if (!target.isBlank()) {
            MapMeta v2Match = mapMetaService.findByUploadName(target);
            if (v2Match != null) {
                targetV2MapId = v2Match.getId();
            }
        }

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.DISCARD_MAPPING,
                targetV2MapId, target, target,
                Instant.now().plusMillis(tuning.localizationTaskTimeoutMs()));
        bindOperation(machine, result.operationId());

        return registerAndPublish(machine);
    }

    public MapModeTaskDTO relocalize(String mapName, double x, double y, double yaw) {
        requireIdleAndGates(MODE_NAVIGATION, true);

        String target = mapName == null ? "" : mapName.trim();
        if (target.isEmpty()) {
            var status = telemetry.status();
            target = status == null ? null : status.mapName();
        }
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("无法确定目标地图（未指定 map_name 且 /agv/status 无当前地图），请稍后重试或显式指定地图");
        }

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.RELOCALIZE,
                null, target, target, Instant.now().plusMillis(tuning.modeTaskTimeoutMs()));

        MapCmdResult result;
        try {
            result = robotCommands.relocalize(target, x, y, yaw).join();
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
        Instant extended = Instant.now().plusMillis(tuning.localizationTaskTimeoutMs());
        if (extended.isAfter(machine.getDeadlineAt())) {
            machine.extendDeadlineTo(extended);
        }
        operations.refresh(operationId);
    }

    public MapModeTaskDTO saveMap(String mapName) {
        String target = mapName == null ? "" : mapName.trim();
        requireValidRobotMapName(target);
        requireIdleAndGates("MAPPING");

        MapModeStateMachine machine = new MapModeStateMachine(
                idSequence.incrementAndGet(), MapModeStateMachine.Type.SAVE_MAP,
                null, target, target,
                // 机器人侧保存(最长 300s)+定位重启(120s 就绪窗)远超通用任务超时，SAVE_MAP 单独放宽到定位级超时
                Instant.now().plusMillis(tuning.localizationTaskTimeoutMs()));

        MapCmdResult result;
        try {
            result = robotCommands.saveMap(target).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("save_map 调用失败: " + cause.getMessage(), cause);
        }
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝保存: " + result.message());
        }

        return registerAndPublish(machine);
    }

    public synchronized MapModeTaskDTO currentTask() {
        return current == null ? null : MapModeTaskDTO.from(current);
    }

    public synchronized boolean hasActiveTask() {
        return current != null && !current.isTerminal();
    }

    public synchronized List<MapModeTaskDTO> recent(int limit) {
        int bounded = Math.clamp(limit, 1, HISTORY_LIMIT);
        List<MapModeTaskDTO> result = new ArrayList<>();
        var iterator = history.descendingIterator();
        while (iterator.hasNext() && result.size() < bounded) {
            result.add(MapModeTaskDTO.from(iterator.next()));
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
            var operation = operations.query(machine.getOperationId());
            if (operation != null && machine.getOperationId().equals(operation.operationId())) {
                transition = machine.onOperation(operation.operationId(), operation.terminal(),
                        operation.succeeded(),
                        operation.stage(), operation.message(), operation.failureReason());
            }
        }

        if (transition.isEmpty()) {
            var status = telemetry.status();
            long staleLimit = Math.max(tuning.statusFreshMs() * 6, 30_000L);
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
            events.publish("map-task", MapModeTaskDTO.from(machine));
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
        if (!robotConnection.isConnected()) {
            throw new IllegalStateException("机器人连接未就绪，无法执行模式任务");
        }
        if (!telemetry.statusFresh()) {
            log.info("websocket 已连接，但超过 {}s 未收到机器人状态（agv_nav_server 未启动 / 白名单未放行 / 话题名不匹配），拒绝执行",
                    tuning.statusFreshMs() / 1000);
        }
        var status = telemetry.status();
        if (status == null || status.mode() == null) {
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

    private MapModeTaskDTO registerAndPublish(MapModeStateMachine machine) {
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
        return MapModeTaskDTO.from(machine);
    }

    private void publishAndFinalize(MapModeStateMachine machine) {
        if (machine.getStatus() == MapModeStateMachine.Status.SUCCEEDED
                && machine.getV2MapId() != null
                && (machine.getType() == MapModeStateMachine.Type.SWITCH_MAP
                        || machine.getType() == MapModeStateMachine.Type.DISCARD_MAPPING)) {
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
        } else if (machine.getStatus() == MapModeStateMachine.Status.FAILED) {
            log.warn("map mode task failed: id={}, type={}, map={}, reason={}",
                    machine.getId(), machine.getType(), machine.getRobotMapName(), machine.getErrorMessage());
        }

        if (machine.getStatus() == MapModeStateMachine.Status.SUCCEEDED
                && machine.getType() == MapModeStateMachine.Type.SAVE_MAP) {
            worker.execute(() -> syncRobotMapV2(machine));
        }
        events.publish("map-task", MapModeTaskDTO.from(machine));
    }

    private void syncRobotMapV2(MapModeStateMachine machine) {
        String robotMapName = machine.getRobotMapName();
        try {
            RobotGrid grid = fetchRobotGrid(robotMapName);
            MapMeta existing = mapMetaService.findByUploadName(robotMapName);
            MapV2DTO saved;
            if (existing != null) {
                saved = mapMetaService.replaceRobotGrid(existing.getId(), robotMapName, grid);
            } else {
                saved = mapMetaService.createFromRobotGrid(robotMapName, grid);
            }
            mapMetaService.publish(saved.id(), "map-mode-sync");
            log.info("v2 map synced from robot after save: mapId={}, robotMapName={}",
                    saved.id(), robotMapName);
            events.publish("map-sync", Map.of(
                    "success", true, "mapId", saved.id(), "mapName", saved.mapName(),
                    "robotMapName", robotMapName));
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : e;
            log.error("sync v2 map {} from robot failed: {}", robotMapName, cause.getMessage(), cause);
            events.publish("map-sync", Map.of(
                    "success", false, "robotMapName", robotMapName,
                    "message", String.valueOf(cause.getMessage())));
        }
    }

    private RobotGrid fetchRobotGrid(String robotMapName) {
        String target = robotMapName == null ? "" : robotMapName.trim();
        requireValidRobotMapName(target);
        requireConnection();
        try {
            return robotCommands.getMap(target);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("get_map 调用失败: " + cause.getMessage(), cause);
        }
    }

    public List<String> listRobotMaps() {
        requireConnection();
        var result = robotCommands.listMaps().join();
        if (!result.success()) {
            throw new IllegalStateException("机器人拒绝 list_maps: " + result.message());
        }
        return result.mapNames();
    }

    @Scheduled(fixedDelay = 5000)
    public void alignActiveMapWithRobot() {
        if (!telemetry.statusFresh()) {
            return;
        }
        var status = telemetry.status();
        String robotMap = status == null ? null : status.mapName();
        if (robotMap == null || robotMap.isBlank()) {
            return;
        }
        MapMeta published = mapMetaService.findPublished();
        if (published == null) {
            return;
        }
        String ref = published.getRobotMapName() != null && !published.getRobotMapName().isBlank()
                ? published.getRobotMapName() : published.getMapName();
        if (robotMap.equals(ref)) {
            return;
        }
        MapMeta match = mapMetaService.findByUploadName(robotMap);
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
        events.publish("map-align", Map.of(
                "aligned", true, "mapId", match.getId(), "robotMapName", robotMap));
    }

    public Map<String, Object> computeAlignment() {
        String robotMap = null;
        if (telemetry.statusFresh()) {
            var status = telemetry.status();
            robotMap = status == null ? null : status.mapName();
        }

        MapMeta published = mapMetaService.findPublished();
        String ref = published == null ? null
                : (published.getRobotMapName() != null && !published.getRobotMapName().isBlank()
                        ? published.getRobotMapName() : published.getMapName());
        String state;
        if (robotMap == null || robotMap.isBlank()) {
            state = "UNKNOWN";
        } else if (published == null) {
            state = "ROBOT_MAP_MISSING";
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
        align.put("active_map_id", published == null ? null : published.getId());
        align.put("active_map_name", published == null ? null : published.getMapName());
        return align;
    }

    private void requireConnection() {
        if (!robotConnection.isConnected()) {
            throw new IllegalStateException("机器人连接未就绪，无法访问机器人地图");
        }
    }

    private static void requireValidRobotMapName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128
                || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("机器人地图名非法（非空且不含路径分隔符）: '" + name + "'");
        }
    }
}
