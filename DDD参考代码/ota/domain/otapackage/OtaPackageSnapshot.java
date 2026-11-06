package com.zioneer.robotmanager.robot.ota.domain.otapackage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "OTA安装包快照")
public record OtaPackageSnapshot(
        @Schema(description = "安装包ID") Long packageId,
        @Schema(description = "名称") String name,
        @Schema(description = "类型") OtaPackageKind kind,
        @Schema(description = "版本") String version,
        @Schema(description = "来源") List<OtaPackageSource> sources,
        @Schema(description = "清单") List<OtaManifestEntry> manifest
) {

    public static OtaPackageSnapshot of(OtaPackage otaPackage) {
        return new OtaPackageSnapshot(
                otaPackage.getId(),
                otaPackage.getName(),
                otaPackage.getKind(),
                otaPackage.getVersion(),
                otaPackage.getSources(),
                otaPackage.getManifest()
        );
    }
}
