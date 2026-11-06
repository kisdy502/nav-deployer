package com.zioneer.robotmanager.robot.ota.application.dto;

import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageKind;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaReleaseStatus;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTargetDimension;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OtaReleaseDetailVO {

    @Schema(description = "主键")
    private String id;

    @Schema(description = "发布名称")
    private String name;

    @Schema(description = "安装包名称")
    private String packageName;

    @Schema(description = "安装包类型")
    private OtaPackageKind packageKind;

    @Schema(description = "目标版本")
    private String packageVersion;

    @Schema(description = "目标维度")
    private OtaTargetDimension targetDimension;

    @Schema(description = "所选的目标值：按型号时为型号编号，按机器人时为机器人ID")
    private List<String> targetValues;

    @Schema(description = "目标的展示文本：按型号时为型号编号，按机器人时为机器人名称")
    private List<String> targetLabels;

    @Schema(description = "发起人")
    private String createdBy;

    @Schema(description = "发起时间")
    private Instant createdAt;

    @Schema(description = "状态：由所属升级任务实时统计")
    private OtaReleaseStatus status;

    @Schema(description = "目标总数")
    private long total;

    @Schema(description = "待升级数量")
    private long pending;

    @Schema(description = "升级中数量")
    private long inProgress;

    @Schema(description = "已完成数量")
    private long succeeded;

    @Schema(description = "升级失败数量")
    private long failed;
}
