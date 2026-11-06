package com.agv.navdeployer.rms.state;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;

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
