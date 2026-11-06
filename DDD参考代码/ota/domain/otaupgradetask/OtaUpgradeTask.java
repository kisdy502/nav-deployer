package com.zioneer.robotmanager.robot.ota.domain.otaupgradetask;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.common.mybatis.domain.BaseEntity;
import com.zioneer.robotmanager.common.mybatis.support.JsonbTypeHandler;
import com.zioneer.robotmanager.common.mybatis.support.SnowflakeIdGenerator;
import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.otamanifest.OtaManifest;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSnapshot;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaRelease;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTarget;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Slf4j
@TableName(value = "ota_upgrade_task", autoResultMap = true)
public class OtaUpgradeTask extends BaseEntity {

    /**
     * 升级任务在 Action 上下文中的类型标识，上下文值为任务 ID。
     */
    public static final String ACTION_CONTEXT_TYPE = "OTA_UPGRADE_TASK";

    private static final String FAILURE_DETAIL_TIMEOUT = "超时";

    @TableField(exist = false)
    private Boolean deleted;

    @Schema(description = "所属发布")
    private Long releaseId;

    @Schema(description = "目标类型")
    private OtaTargetType targetType;

    @Schema(description = "目标标识")
    private String targetId;

    @Schema(description = "目标编码")
    private String targetCode;

    @Schema(description = "安装包快照")
    @TableField(typeHandler = JsonbTypeHandler.class)
    private OtaPackageSnapshot packageSnapshot;

    @Schema(description = "当前清单快照")
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<RobotVersionManifestEntry> currentManifestSnapshot;

    @Schema(description = "状态")
    private OtaUpgradeTaskStatus status;

    @Schema(description = "完成来源")
    private OtaCompletionSource completionSource;

    @Schema(description = "失败原因")
    private String failureDetail;

    @Schema(description = "开始时间，即进入升级中的时刻")
    private Instant startedAt;

    @Schema(description = "结束时间，即进入终态的时刻")
    private Instant finishedAt;

    @Schema(description = "有效期")
    private Instant expiresAt;


    public OtaUpgradeTask() {
    }

    public OtaUpgradeTask(OtaRelease otaRelease, OtaTarget target, Instant expiresAt, SnowflakeIdGenerator snowflakeIdGenerator) {
        setId(snowflakeIdGenerator.nextId());
        this.releaseId = otaRelease.getId();
        this.targetType = OtaTargetType.ROBOT;
        this.targetId = target.id().toString();
        this.targetCode = target.code();
        this.packageSnapshot = otaRelease.getPackageSnapshot();
        this.currentManifestSnapshot = target.currentManifest();
        this.status = OtaUpgradeTaskStatus.PENDING;
        this.expiresAt = expiresAt;
    }

    public void startBy(Instant at) {
        if (status != OtaUpgradeTaskStatus.PENDING) {
            return;
        }
        this.status = OtaUpgradeTaskStatus.IN_PROGRESS;
        this.startedAt = at;
    }

    private void completeBy(OtaCompletionSource source, Instant at) {
        if (status.checkTerminal()) {
            return;
        }
        this.status = OtaUpgradeTaskStatus.COMPLETED;
        this.completionSource = source;
        this.finishedAt = at;
    }

    public void failBy(String failureDetail, Instant at) {
        if (status.checkTerminal()) {
            return;
        }
        this.status = OtaUpgradeTaskStatus.FAILED;
        this.failureDetail = failureDetail;
        this.finishedAt = at;
    }

    public boolean checkPending() {
        return status == OtaUpgradeTaskStatus.PENDING;
    }

    public boolean checkExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public void expired(Instant now) {
        if (status.checkTerminal()) {
            return;
        }
        if (expiresAt == null || !now.isAfter(expiresAt)) {
            return;
        }
        failBy(FAILURE_DETAIL_TIMEOUT, now);
    }

    public void validateActiveStatus() {
        if (!status.checkTerminal()) {
            return;
        }
        throw new BusinessException(RobotManagementErrorCode.OTA_UPGRADE_TASK_ALREADY_FINISHED);

    }

    public void compareManifest(List<RobotVersionManifestEntry> robotCurrentManifest) {
        var manifest = OtaManifest.of(packageSnapshot.manifest());
        if (!manifest.compare(robotCurrentManifest)) {
            throw new BusinessException(RobotManagementErrorCode.OTA_UPGRADE_MANIFEST_NOT_REACHED);
        }
        this.completeBy(OtaCompletionSource.MANIFEST, Instant.now());
    }

    public boolean checkAndChangeStatusBy(OtaAction otaAction) {
        OtaUpgradeTaskStatus before = status;
        changeStatusBy(otaAction);
        if (status == before) {
            log.info("upgrade task not changed, id={}, action_id={}, action_status={}, task_status={}",
                    getId(), otaAction.actionId(), otaAction.actionStatus(), status);
            return false;
        }
        log.info("upgrade task changed, id={}, action_id={}, from={}, to={}",
                getId(), otaAction.actionId(), before, status);
        return true;
    }

    private void changeStatusBy(OtaAction otaAction) {
        Instant reportedAt = otaAction.lastReportedAt();
        switch (otaAction.actionStatus()) {
            case IN_PROGRESS -> startBy(reportedAt);
            case COMPLETED -> completeBy(OtaCompletionSource.ACTION, reportedAt);
            case FAILED -> failBy(otaAction.failureDetail(), reportedAt);
            case PENDING -> {
            }
        }
    }
}
