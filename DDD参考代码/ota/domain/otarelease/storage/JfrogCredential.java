package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import java.time.Instant;

/**
 * 一份可嵌入下载地址的 JFrog 短效凭据：{@code userInfo()} 即 URL 的 user:token 部分，
 * {@code expiresAt()} 即该地址的有效期截止时刻。
 */
public record JfrogCredential(String username, String token, Instant expiresAt) {

    public String userInfo() {
        return username + ":" + token;
    }
}
