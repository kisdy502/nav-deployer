package com.zioneer.robotmanager.robot.ota.application.cmd;

import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaManifestEntry;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageKind;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
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
public class OtaPackageCmd {

    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过128")
    @Schema(description = "名称")
    private String name;

    @NotNull(message = "类型不能为空")
    @Schema(description = "类型")
    private OtaPackageKind kind;

    @NotBlank(message = "版本不能为空")
    @Size(max = 64, message = "版本长度不能超过64")
    @Schema(description = "版本")
    private String version;

    @Size(max = 512, message = "描述长度不能超过512")
    @Schema(description = "描述")
    private String description;

    @NotEmpty(message = "来源不能为空")
    @Schema(description = "来源")
    private List<OtaPackageSource> sources;

    @NotEmpty(message = "清单不能为空")
    @Schema(description = "清单")
    private List<OtaManifestEntry> manifest;
}
