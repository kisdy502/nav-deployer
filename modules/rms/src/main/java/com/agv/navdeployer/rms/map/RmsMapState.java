package com.agv.navdeployer.rms.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RMS 地图链路的业务会话状态：start_mapping 预置、stop_mapping 兜底用的地图名。
 *
 * <p>持久化语义：两行文本（地图名 + 预置时刻毫秒时间戳）落盘，Java 后端重启后
 * 自动恢复——建图动辄几分钟，期间后端重启是现实场景；丢了名字，RMS 按协议惯例
 * stop_mapping 不带名就会失败，建图保存流程断在半路。文件损坏/不可读时降级为空
 * （等同重启前无暂存），不阻断启动。
 *
 * <p>与 zenoh 会话生命周期刻意解耦：RmsRobotSession 重建（断线重连 / robot_code
 * 变更）只重绑传输层（gateway.attach），本状态必须跨会话存活。
 *
 * <p>机器人重启的清理由 {@link RmsMapService} 依据 telemetry mode 判定
 * （建图会话已不存在时清空），本类只做存取与落盘，不做业务判断。
 *
 * <p>调用方约定（RmsMapService 内部调用，无并发竞争场景）：
 * <ul>
 *   <li>start_mapping 成功后 setPendingMapName（可空，记录当前时刻）；失败不落</li>
 *   <li>stop_mapping 成功后 clearPendingMapName；失败保留供重试</li>
 *   <li>再次 start_mapping 时直接覆盖（上一轮遗留自然失效）</li>
 * </ul>
 */
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

    /** 预置时刻（epoch 毫秒）；从未设置过或已清理时为 0。 */
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

    /** @return [name(可空), sinceMs]，文件缺失/损坏时 [null, 0]。 */
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
            // 落盘失败不回滚内存值：最坏退化为旧的纯内存行为（本次进程内仍可用）
            log.warn("暂存地图名落盘失败: {} msg={}", storeFile, exception.getMessage());
        }
    }
}
