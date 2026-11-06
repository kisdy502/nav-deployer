package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * 平台代下载的短效票据：自包含（包 ID + 来源 key + 过期时刻）并以 HMAC-SHA256 防伪，
 * 供浏览器在无法携带登录态的场景（window.open）换取一次下载流。
 * 票据只在下载开始前验证一次，下载开始后的持续时间不受其约束。
 */
public record OtaDownloadTicket(String packageId, String sourceKey, long expiresAtEpochMilli) {

    public String sign(String secret) {
        String payload = payload();
        return base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "." + hmac(payload, secret);
    }

    /**
     * 校验并解析票据：签名不符、绑定不匹配或已过期均返回 null。
     */
    public static OtaDownloadTicket verify(String token, String secret, String packageId, String sourceKey, Instant now) {
        if (token == null || token.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }
        int separatorIndex = token.lastIndexOf('.');
        if (separatorIndex <= 0 || separatorIndex == token.length() - 1) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, separatorIndex)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (!hmac(payload, secret).equals(token.substring(separatorIndex + 1))) {
            return null;
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 3 || !parts[0].equals(packageId) || !parts[1].equals(sourceKey)) {
            return null;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (!now.isBefore(Instant.ofEpochMilli(expiresAt))) {
            return null;
        }
        return new OtaDownloadTicket(parts[0], parts[1], expiresAt);
    }

    private String payload() {
        return packageId + "|" + sourceKey + "|" + expiresAtEpochMilli;
    }

    private static String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return base64Url(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HmacSHA256 not available", exception);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
