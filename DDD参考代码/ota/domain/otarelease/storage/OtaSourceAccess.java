package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import java.time.Instant;

/**
 * 一个制品来源在当前存储平台上的访问视图：downloadUrl 为可直接 GET 的直链（OCI 为镜像引用）；
 * repository 为制品所在目录的地址（APT 类型配为 apt 源使用）；
 * expiresAt 为直链所嵌凭据的截止时刻，不嵌凭据时为空。
 */
public record OtaSourceAccess(String downloadUrl, String repository, Instant expiresAt) {
}
