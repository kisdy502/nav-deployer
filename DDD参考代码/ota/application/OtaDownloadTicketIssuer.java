package com.zioneer.robotmanager.robot.ota.application;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaDownloadTicketVO;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaStorageProperties;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.OtaDownloadTicket;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class OtaDownloadTicketIssuer {

    private static final long DEFAULT_TTL_SECONDS = 60;

    private final OtaStorageProperties properties;
    private String secret;

    public OtaDownloadTicketIssuer(OtaStorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initSecret() {
        String configured = properties.getDownloadTicketSecret();
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.trim();
            return;
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.secret = HexFormat.of().formatHex(random);
    }

    public OtaDownloadTicketVO issue(String packageId, String sourceKey) {
        long ttlSeconds = ttlSeconds();
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).toEpochMilli();
        String token = new OtaDownloadTicket(packageId, sourceKey, expiresAt).sign(secret);
        return new OtaDownloadTicketVO(token, ttlSeconds);
    }

    public void validate(String ticket, String packageId, String sourceKey) {
        if (!this.check(ticket, packageId, sourceKey)) {
            throw new BusinessException(RobotManagementErrorCode.OTA_DOWNLOAD_TICKET_INVALID);
        }
    }

    private boolean check(String token, String packageId, String sourceKey) {
        return OtaDownloadTicket.verify(token, secret, packageId, sourceKey, Instant.now()) != null;
    }

    private long ttlSeconds() {
        long configured = properties.getDownloadTicketTtlSeconds();
        return configured > 0 ? configured : DEFAULT_TTL_SECONDS;
    }
}
