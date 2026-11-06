package com.zioneer.robotmanager.robot.ota.application.dto;

import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaManifestEntry;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OtaUpgradeTaskVO {

    @Schema(description = "主键")
    private String id;

    @Schema(description = "目标标识，机器人为机器人ID")
    private String targetId;

    @Schema(description = "目标名称，按查询时刻的最新值")
    private String targetName;

    @Schema(description = "目标编码，按查询时刻的最新值")
    private String robotCode;

    @Schema(description = "目标型号编号")
    private String robotTypeNo;

    @Schema(description = "状态")
    private OtaUpgradeTaskStatus status;

    @Schema(description = "创建时间，即发布创建的时刻")
    private Instant createdAt;

    @Schema(description = "开始时间，即进入升级中的时刻")
    private Instant startedAt;

    @Schema(description = "结束时间，即进入终态的时刻")
    private Instant finishedAt;

    @Schema(description = "失败原因")
    private String failureDetail;

    @Schema(description = "目标清单快照")
    private List<OtaManifestEntry> packageManifest;

    @Schema(description = "当前清单快照，从未上报过时为空")
    private List<RobotVersionManifestEntry> currentManifest;
}
