package com.agv.navdeployer.sim;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.regex.Pattern;

public final class NativeMapClient {
    private static final Pattern MAP_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");
    private static final int MAX_BYTES = 128 * 1024 * 1024;
    private final String baseUrl;
    private final String token;

    public NativeMapClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.token = token;
        if (enabled() == token.isBlank()) {
            throw new IllegalArgumentException("Configure native-map.base-url and native-map.token together");
        }
    }

    public boolean enabled() {
        return !baseUrl.isEmpty();
    }

    public byte[] exportMap(String name) {
        return transfer(name, null);
    }

    public byte[] exportIfConfigured(String mapName, String robotMapName) {
        if (!enabled()) {
            return null;
        }
        return exportMap(robotMapName == null || robotMapName.isBlank() ? mapName : robotMapName);
    }

    public void installMap(String name, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Lightning 原生地图包大小无效");
        }
        transfer(name, bytes);
    }

    private byte[] transfer(String name, byte[] bytes) {
        if (!enabled()) {
            throw new TransferException("上位机未配置 Lightning 地图服务", false);
        }
        if (!MAP_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Lightning 地图名格式无效");
        }
        try {
            var connection = (HttpURLConnection) URI.create(baseUrl + "/native/lightning/" + name)
                    .toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(90_000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            try {
                if (bytes != null) {
                    connection.setRequestMethod("PUT");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (var output = connection.getOutputStream()) {
                        output.write(bytes);
                    }
                }
                int status = connection.getResponseCode();
                if (bytes != null && status == HttpURLConnection.HTTP_NO_CONTENT) {
                    return null;
                }
                if (bytes != null || status != HttpURLConnection.HTTP_OK) {
                    throw new TransferException("Lightning 地图传输失败，机器人 HTTP " + status,
                            status == HttpURLConnection.HTTP_CONFLICT);
                }
                try (var input = connection.getInputStream()) {
                    byte[] result = input.readNBytes(MAX_BYTES + 1);
                    if (result.length == 0 || result.length > MAX_BYTES) {
                        throw new IOException("native ZIP size out of bounds");
                    }
                    return result;
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException exception) {
            throw new TransferException("Lightning 地图服务连接或传输失败", false);
        }
    }

    public static final class TransferException extends RuntimeException {
        private final boolean conflict;

        private TransferException(String message, boolean conflict) {
            super(message);
            this.conflict = conflict;
        }

        public boolean conflict() {
            return conflict;
        }
    }
}
