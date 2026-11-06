package com.zioneer.robotmanager.robot.ota.application.cmd;

import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTargetDimension;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CreateOtaReleaseCmd {

    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过128")
    @Schema(description = "发布名称")
    private String name;

    @NotNull(message = "安装包不能为空")
    @Schema(description = "安装包ID")
    private Long packageId;

    @NotNull(message = "目标维度不能为空")
    @Schema(description = "目标维度：ROBOT_TYPE 按机器人型号 / ROBOT 按机器人")
    private OtaTargetDimension targetDimension;

    @NotEmpty(message = "目标不能为空")
    @Schema(description = "所选维度下的目标值：按型号时为型号编号，按机器人时为机器人ID")
    private List<String> targetValues;
}
