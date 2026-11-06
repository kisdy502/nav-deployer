package com.zioneer.robotmanager.robot.ota.domain.otapackage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;


@Schema(description = "OTA清单项")
public record OtaManifestEntry(
        String key,
        String name,
        String version,
        List<OtaManifestEntry> children
) {

}
