package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 一次制品取回的流式视图：statusCode 为上游的 200 / 206；contentLength 在 206 时为该区间长度；
 * body 由持有方负责关闭。
 */
public record JfrogArtifactFetch(int statusCode, Long contentLength, String contentType,
        String contentRange, InputStream body) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        body.close();
    }
}
