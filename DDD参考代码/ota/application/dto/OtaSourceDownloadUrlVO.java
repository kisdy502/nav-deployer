package com.zioneer.robotmanager.robot.ota.application.dto;

import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaSourceType;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaSourceAccess;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaSourceAccessResolver;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "OTA安装包来源的下载地址")
public record OtaSourceDownloadUrlVO(
        @Schema(description = "来源标识，与安装包 sources 的 key 相同") String key,
        @Schema(description = "来源类型") OtaSourceType type,
        @Schema(description = "制品在仓库内的文件路径（Repository Path）") String path,
        @Schema(description = "制品版本") String version,
        @Schema(description = "下载直链，可直接 GET；OCI 为镜像引用") String downloadUrl,
        @Schema(description = "制品所在目录的地址；APT 类型配为 apt 源使用") String repository,
        @Schema(description = "下载地址的有效期；不会过期时为空") Instant expiresAt
) {

    public static OtaSourceDownloadUrlVO of(OtaPackageSource source, OtaSourceAccessResolver otaSourceAccessResolver) {
        OtaSourceAccess access = otaSourceAccessResolver.resolve(source);
        return new OtaSourceDownloadUrlVO(source.key(), source.type(), source.path(), source.version(),
                access.downloadUrl(), access.repository(), access.expiresAt());
    }
}
