package com.zioneer.robotmanager.robot.ota.application.dto;

import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogArtifactFetch;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 一次平台代下载会话：上游连接已建立、可预判的错误已前置，
 * 剩余工作是把制品流写给调用方；持有方负责 close。
 */
public class OtaSourceDownload implements AutoCloseable {

    private final JfrogArtifactFetch fetch;
    private final String filename;

    public OtaSourceDownload(JfrogArtifactFetch fetch, String filename) {
        this.fetch = fetch;
        this.filename = filename;
    }

    public int statusCode() {
        return fetch.statusCode();
    }

    public Long contentLength() {
        return fetch.contentLength();
    }

    public String contentType() {
        return fetch.contentType();
    }

    public String contentRange() {
        return fetch.contentRange();
    }

    public String contentDisposition() {
        return "attachment; filename*=UTF-8''" + UriUtils.encodeFragment(filename, StandardCharsets.UTF_8);
    }

    public void writeTo(OutputStream output) throws IOException {
        fetch.body().transferTo(output);
    }

    public StreamingResponseBody asStreamingBody() {
        return output -> {
            try {
                writeTo(output);
            } finally {
                closeQuietly();
            }
        };
    }

    public void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        fetch.close();
    }
}
