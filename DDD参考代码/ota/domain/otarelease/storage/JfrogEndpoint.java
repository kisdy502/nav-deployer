package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaStorageProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 平台与 JFrog 交互时使用的端点与账号。
 */
public record JfrogEndpoint(String baseUrl, String username, String password) {

    public static JfrogEndpoint of(OtaStorageProperties.Jfrog jfrog) {
        if (jfrog.getBaseUrl() == null || jfrog.getBaseUrl().isBlank()
                || jfrog.getUsername() == null || jfrog.getUsername().isBlank()
                || jfrog.getPassword() == null || jfrog.getPassword().isBlank()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_CREDENTIAL_NOT_CONFIGURED);
        }
        String trimmed = jfrog.getBaseUrl().trim();
        return new JfrogEndpoint(
                trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed,
                jfrog.getUsername(), jfrog.getPassword());
    }

    public URI uri(String path) {
        return URI.create(baseUrl + "/" + path);
    }

    public String basicAuthorization() {
        String userInfo = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
    }
}
