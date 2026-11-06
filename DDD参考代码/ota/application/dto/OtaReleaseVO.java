package com.zioneer.robotmanager.robot.ota.application.dto;

import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaReleaseStatus;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTargetDimension;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OtaReleaseVO {

    @Schema(description = "主键")
    private String id;

    @Schema(description = "发布名称")
    private String name;

    @Schema(description = "安装包名称")
    private String packageName;

    @Schema(description = "安装包版本")
    private String packageVersion;

    @Schema(description = "目标维度")
    private OtaTargetDimension targetDimension;

    @Schema(description = "所选的目标值：按型号时为型号编号，按机器人时为机器人ID")
    private List<String> targetValues;

    @Schema(description = "目标的展示文本：按型号时为型号编号，按机器人时为机器人名称")
    private List<String> targetLabels;

    @Schema(description = "状态：由所属升级任务实时统计")
    private OtaReleaseStatus status;

    @Schema(description = "目标总数")
    private long total;

    @Schema(description = "已完成数量")
    private long succeeded;

    @Schema(description = "升级失败数量")
    private long failed;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "创建时间")
    private Instant createdAt;
}
