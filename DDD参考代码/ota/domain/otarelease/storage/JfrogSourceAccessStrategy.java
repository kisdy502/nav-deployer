package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaStorageProperties;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import org.springframework.stereotype.Component;

@Component
public class JfrogSourceAccessStrategy implements OtaSourceAccessStrategy {

    private final OtaStorageProperties properties;
    private final JfrogCredentialIssuer credentialIssuer;

    public JfrogSourceAccessStrategy(OtaStorageProperties properties, JfrogCredentialIssuer credentialIssuer) {
        this.properties = properties;
        this.credentialIssuer = credentialIssuer;
    }

    @Override
    public OtaStoragePlatform platform() {
        return OtaStoragePlatform.JFROG;
    }

    @Override
    public OtaSourceAccess resolve(OtaPackageSource source) {
        JfrogCredential credential = source.type() != null && source.type().checkDirectlyDownloadable()
                ? credentialIssuer.issue(properties.getJfrog())
                : null;
        JfrogSourceUrl sourceUrl = new JfrogSourceUrl(source, requireBaseUrl(), credential);
        return new OtaSourceAccess(sourceUrl.downloadUrl(), sourceUrl.repository(), sourceUrl.expiresAt());
    }

    private String requireBaseUrl() {
        String baseUrl = properties.getJfrog().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_NOT_CONFIGURED);
        }
        return baseUrl.trim();
    }
}
