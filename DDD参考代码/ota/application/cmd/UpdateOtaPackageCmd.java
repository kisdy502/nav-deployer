package com.zioneer.robotmanager.robot.ota.application.cmd;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateOtaPackageCmd {

    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过128")
    @Schema(description = "名称")
    private String name;

    @NotBlank(message = "版本不能为空")
    @Size(max = 64, message = "版本长度不能超过64")
    @Schema(description = "版本")
    private String version;

    @Size(max = 512, message = "描述长度不能超过512")
    @Schema(description = "描述，不提供即清空")
    private String description;
}
