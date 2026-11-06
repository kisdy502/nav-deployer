package com.zioneer.robotmanager.robot.ota.application;

import com.zioneer.robotmanager.common.mybatis.support.SnowflakeIdGenerator;
import com.zioneer.robotmanager.robot.action.application.ActionService;
import com.zioneer.robotmanager.robot.action.application.cmd.ActionCreateCmd;
import com.zioneer.robotmanager.robot.action.domain.Action;
import com.zioneer.robotmanager.robot.action.domain.ActionContext;
import com.zioneer.robotmanager.robot.action.domain.ActionEntityType;
import com.zioneer.robotmanager.robot.action.domain.ActionType;
import com.zioneer.robotmanager.robot.action.domain.repository.ActionRepository;
import com.zioneer.robotmanager.robot.domain.entity.RobotEntity;
import com.zioneer.robotmanager.robot.domain.model.RobotManifestReport;
import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;
import com.zioneer.robotmanager.robot.mapper.RobotMapper;
import com.zioneer.robotmanager.robot.ota.application.dto.ManifestVerificationVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaUpgradeTaskVO;
import com.zioneer.robotmanager.robot.ota.application.dto.PendingActionVO;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaUpgradeTaskProperties;
import com.zioneer.robotmanager.robot.ota.domain.otamanifest.OtaManifest;
import com.zioneer.robotmanager.robot.ota.domain.otamanifest.OtaManifestMismatch;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaRelease;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTarget;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaAction;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaTargetType;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTask;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaReleaseRepository;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaUpgradeTaskRepository;
import com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct.OtaReleaseMapstruct;
import com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct.OtaUpgradePayloadMapstruct;
import com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct.PendingActionMapstruct;
import com.zioneer.robotmanager.robot.util.SnowflakeIdParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OtaUpgradeTaskService {

    private static final Logger log = LoggerFactory.getLogger(OtaUpgradeTaskService.class);

    private final OtaReleaseRepository otaReleaseRepository;
    private final OtaUpgradeTaskRepository otaUpgradeTaskRepository;
    private final ActionRepository actionRepository;
    private final ActionService actionService;
    private final OtaUpgradeTaskProperties otaUpgradeTaskProperties;
    private final OtaReleaseMapstruct otaReleaseMapstruct;
    private final OtaUpgradePayloadMapstruct otaUpgradePayloadMapstruct;
    private final PendingActionMapstruct pendingActionMapstruct;
    private final RobotMapper robotMapper;
    private final OtaManifestPullService otaManifestPullService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Autowired
    public OtaUpgradeTaskService(OtaReleaseRepository otaReleaseRepository,
                                     OtaUpgradeTaskRepository otaUpgradeTaskRepository,
                                     ActionRepository actionRepository,
                                     ActionService actionService,
                                     OtaUpgradeTaskProperties otaUpgradeTaskProperties,
                                     OtaReleaseMapstruct otaReleaseMapstruct,
                                     OtaUpgradePayloadMapstruct otaUpgradePayloadMapstruct,
                                     PendingActionMapstruct pendingActionMapstruct,
                                     RobotMapper robotMapper,
                                     OtaManifestPullService otaManifestPullService,
                                     SnowflakeIdGenerator snowflakeIdGenerator) {
        this.otaReleaseRepository = otaReleaseRepository;
        this.otaUpgradeTaskRepository = otaUpgradeTaskRepository;
        this.actionRepository = actionRepository;
        this.actionService = actionService;
        this.otaUpgradeTaskProperties = otaUpgradeTaskProperties;
        this.otaReleaseMapstruct = otaReleaseMapstruct;
        this.otaUpgradePayloadMapstruct = otaUpgradePayloadMapstruct;
        this.pendingActionMapstruct = pendingActionMapstruct;
        this.robotMapper = robotMapper;
        this.otaManifestPullService = otaManifestPullService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createForRelease(OtaRelease otaRelease, List<OtaTarget> targets) {
        Instant expiresAt = Instant.now().plus(otaUpgradeTaskProperties.getTimeout());
        List<OtaUpgradeTask> tasks = targets.stream().map(target ->
                new OtaUpgradeTask(otaRelease, target, expiresAt, snowflakeIdGenerator)).toList();
        otaUpgradeTaskRepository.insertBatch(tasks);
        actionService.createBatch(tasks.stream().map(this::upgradeActionCmd).toList());
        log.info("Created {} upgrade tasks for release {}", tasks.size(), otaRelease.getId());
    }

    private ActionCreateCmd upgradeActionCmd(OtaUpgradeTask task) {
        return new ActionCreateCmd(ActionType.OTA_UPGRADE, ActionEntityType.ROBOT, task,
                otaUpgradePayloadMapstruct.toUpgradePayload(task.getPackageSnapshot()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatusBy(OtaUpgradeTask otaUpgradeTask, OtaAction otaAction) {
        boolean statusChanged = otaUpgradeTask.checkAndChangeStatusBy(otaAction);
        if (!statusChanged) {
            log.info("upgrade task not changed, id={}, action_id={}, action_status={}, task_status={}",
                    otaUpgradeTask.getId(), otaAction.actionId(), otaAction.actionStatus(), otaUpgradeTask.getStatus());
            return;
        }
        otaUpgradeTaskRepository.updateIfUnfinished(otaUpgradeTask);
    }

    public List<PendingActionVO> latestPendingUpgradeActions(String robotCode) {
        return otaUpgradeTaskRepository
                .findLatestPerKindByTargetCode(OtaTargetType.ROBOT, robotCode)
                .stream()
                .filter(OtaUpgradeTask::checkPending)
                .map(this::findPendingActionOf)
                .flatMap(Optional::stream)
                .map(pendingActionMapstruct::toVO)
                .toList();
    }

    private Optional<Action> findPendingActionOf(OtaUpgradeTask task) {
        List<Action> actions = actionRepository.findByContext(
                new ActionContext(OtaUpgradeTask.ACTION_CONTEXT_TYPE, String.valueOf(task.getId())));
        if (actions.isEmpty()) {
            log.warn("pending upgrade task has no action, task_id={}", task.getId());
            return Optional.empty();
        }
        Optional<Action> pendingAction = actions.stream().filter(Action::checkPending).findFirst();
        if (pendingAction.isEmpty()) {
            log.debug("pending upgrade task has no pending action, task_id={}, action_status={}",
                    task.getId(), actions.stream().map(Action::getActionStatus).toList());
        }
        return pendingAction;
    }


    public List<OtaUpgradeTaskVO> upgradeTasks(Long releaseId) {
        var otaRelease = otaReleaseRepository.findById(releaseId);

        List<OtaUpgradeTask> tasks = otaUpgradeTaskRepository.findByReleaseId(otaRelease.getId());
        Map<String, RobotEntity> robotsById = loadRobots(tasks);
        return tasks.stream()
                .map(task -> otaReleaseMapstruct.toUpgradeTaskVO(task, robotsById.get(task.getTargetId())))
                .toList();
    }

    private Map<String, RobotEntity> loadRobots(List<OtaUpgradeTask> tasks) {
        List<Long> robotIds = tasks.stream()
                .map(OtaUpgradeTask::getTargetId)
                .distinct()
                .map(SnowflakeIdParser::parse)
                .toList();
        return robotMapper.selectBatchIds(robotIds).stream()
                .collect(Collectors.toMap(robot -> robot.getId().toString(), Function.identity()));
    }


    @Transactional(rollbackFor = Exception.class)
    public void timeoutExpired(Instant now) {
        List<OtaUpgradeTask> expiredTasks = otaUpgradeTaskRepository.findUnfinished().stream()
                .filter(task -> task.checkExpired(now))
                .toList();
        expiredTasks.forEach(task -> task.expired(now));
        otaUpgradeTaskRepository.updateBatch(expiredTasks);
    }

    public List<String> findExpiredUnfinishedRobotCodes(Instant now) {
        return otaUpgradeTaskRepository.findUnfinished().stream()
                .filter(task -> task.checkExpired(now))
                .map(OtaUpgradeTask::getTargetCode)
                .distinct()
                .toList();
    }

    public List<String> findUnfinishedRobotCodes() {
        return otaUpgradeTaskRepository.findUnfinished().stream()
                .map(OtaUpgradeTask::getTargetCode)
                .distinct()
                .toList();
    }

    public ManifestVerificationVO verifyManifest(Long taskId) {
        OtaUpgradeTask task = otaUpgradeTaskRepository.findById(taskId);
        task.validateActiveStatus();
        Instant verifiedAt = Instant.now();
        Map<String, RobotManifestReport> pulled = otaManifestPullService.refreshCurrentManifests(
                List.of(task.getTargetCode()));
        RobotManifestReport report = pulled.get(task.getTargetCode());
        if (report == null) {
            return new ManifestVerificationVO(false, List.of(), verifiedAt);
        }
        List<OtaManifestMismatch> mismatches = OtaManifest.of(task.getPackageSnapshot().manifest())
                .findMismatches(report.manifest());
        return new ManifestVerificationVO(true, mismatches, verifiedAt);
    }

    public void compareManifest(Long taskId, List<RobotVersionManifestEntry> robotCurrentManifest) {
        OtaUpgradeTask task = otaUpgradeTaskRepository.findById(taskId);
        task.validateActiveStatus();
        task.compareManifest(robotCurrentManifest);
        otaUpgradeTaskRepository.updateIfUnfinished(task);
        log.info("upgrade task completed by manifest, id={}", taskId);
    }

    public OtaUpgradeTask findById(Long upgradeTaskId) {
        return otaUpgradeTaskRepository.findById(upgradeTaskId);
    }
}
