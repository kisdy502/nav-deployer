package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaSourceType;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public record JfrogSourceUrl(OtaPackageSource source, String baseUrl, JfrogCredential credential) {

    public String downloadUrl() {
        if (OtaSourceType.OCI == source.type()) {
            return authority() + "/" + source.path();
        }
        // encodePath 逐段编码且保留 /；不要用 URLEncoder，它会把 / 也编码、拆坏路径
        return withCredential(trimmedBaseUrl() + "/" + UriUtils.encodePath(source.path(), StandardCharsets.UTF_8));
    }

    public String repository() {
        return withCredential(trimmedBaseUrl() + "/" + directoryOf(source.path()));
    }

    public Instant expiresAt() {
        return credential == null ? null : credential.expiresAt();
    }

    // 凭据只嵌入可直接 GET 的地址；OCI 为镜像引用，调用方对其不签发凭据
    private String withCredential(String plainUrl) {
        if (credential == null) {
            return plainUrl;
        }
        URI uri = URI.create(plainUrl);
        String authority = uri.getAuthority();
        if (authority == null) {
            throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_BASE_URL_INVALID);
        }
        // getRawPath 保持百分号编码原样；getPath 会解码，中文与空格路径会被拆坏
        return uri.getScheme() + "://" + credential.userInfo() + "@" + authority + uri.getRawPath();
    }

    private String trimmedBaseUrl() {
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String authority() {
        String authority = URI.create(baseUrl.trim()).getAuthority();
        if (authority == null || authority.isBlank()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_BASE_URL_INVALID);
        }
        return authority;
    }

    // flat 布局：索引（Packages/Release）与 deb 同目录平铺，apt 源地址即 deb 所在目录
    private static String directoryOf(String ref) {
        int separatorIndex = ref.lastIndexOf('/');
        return separatorIndex < 0 ? ref : ref.substring(0, separatorIndex);
    }
}
