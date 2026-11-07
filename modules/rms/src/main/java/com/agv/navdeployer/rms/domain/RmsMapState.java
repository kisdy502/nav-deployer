package com.agv.navdeployer.rms.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RmsMapState {

    private static final Logger log = LoggerFactory.getLogger(RmsMapState.class);

    private final Path storeFile;
    private volatile String pendingMapName;
    private volatile long pendingSinceEpochMs;

    public RmsMapState() {
        this.storeFile = Path.of(System.getProperty("user.home"),
                ".nav-deployer", "rms-pending-map-name");
        String[] loaded = load();
        this.pendingMapName = loaded[0];
        this.pendingSinceEpochMs = Long.parseLong(loaded[1]);
    }

    public String getPendingMapName() {
        return pendingMapName;
    }

    public long getPendingSinceEpochMs() {
        return pendingSinceEpochMs;
    }

    public void setPendingMapName(String mapName) {
        this.pendingMapName = mapName;
        this.pendingSinceEpochMs = mapName == null ? 0L : System.currentTimeMillis();
        persist();
    }

    public void clearPendingMapName() {
        this.pendingMapName = null;
        this.pendingSinceEpochMs = 0L;
        persist();
    }

    private String[] load() {
        try {
            if (!Files.exists(storeFile)) {
                return new String[]{null, "0"};
            }
            String content = Files.readString(storeFile, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R", 2);
            String name = lines[0].trim();
            if (name.isEmpty()) {
                return new String[]{null, "0"};
            }
            long since = 0L;
            if (lines.length > 1) {
                try {
                    since = Long.parseLong(lines[1].trim());
                } catch (NumberFormatException ignored) {
                    // 旧格式单行文件：无时间戳，按 0 处理
                }
            }
            return new String[]{name, String.valueOf(since)};
        } catch (IOException exception) {
            log.warn("读取暂存地图名失败（降级为空）: {} msg={}", storeFile, exception.getMessage());
            return new String[]{null, "0"};
        }
    }

    private void persist() {
        try {
            if (pendingMapName == null) {
                Files.deleteIfExists(storeFile);
            } else {
                Files.createDirectories(storeFile.getParent());
                Files.writeString(storeFile,
                        pendingMapName + System.lineSeparator() + pendingSinceEpochMs,
                        StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            log.warn("暂存地图名落盘失败: {} msg={}", storeFile, exception.getMessage());
        }
    }
}
