package com.agv.navdeployer.rms.state;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;

/**
 * 宿主机真实指标（状态上报 system/memory 模块的数据源）。
 * CPU/内存走 JDK 内置 com.sun.management.OperatingSystemMXBean（物理机/容器均可读），
 * 无需额外依赖；读不到时返回兜底值。
 */
@Component
public class HostStats {

    private final com.sun.management.OperatingSystemMXBean os =
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
    private final long startAtMs = System.currentTimeMillis();

    public String arch() {
        return System.getProperty("os.arch", "");
    }

    public int cpuCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    /** 系统整体 CPU 占用百分比（0~100；JVM 采样未就绪返回 0） */
    public int cpuUsagePercent() {
        double load = os.getCpuLoad();
        return load >= 0 ? (int) Math.round(load * 100) : 0;
    }

    public String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "";
        }
    }

    public String os() {
        return System.getProperty("os.name", "") + " " + System.getProperty("os.version", "");
    }

    /** 本进程运行秒数 */
    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startAtMs) / 1000;
    }

    public long totalMemoryKb() {
        return os.getTotalMemorySize() / 1024;
    }

    public long availableMemoryKb() {
        return os.getFreeMemorySize() / 1024;
    }

    public long usedMemoryKb() {
        return totalMemoryKb() - availableMemoryKb();
    }
}
