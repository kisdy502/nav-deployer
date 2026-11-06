package com.zioneer.robotmanager.robot.ota.domain.otamanifest;

import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaManifestEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 安装包声明的目标版本清单，整棵树内 key 唯一。
 */
public record OtaManifest(List<OtaManifestEntry> entries) {

    public static OtaManifest of(List<OtaManifestEntry> entries) {
        return new OtaManifest(entries);
    }

    public boolean compare(List<RobotVersionManifestEntry> currentManifest) {
        return findMismatches(currentManifest).isEmpty();
    }

    public List<OtaManifestMismatch> findMismatches(List<RobotVersionManifestEntry> currentManifest) {
        Map<String, String> currentVersions = flattenRobotManifest(currentManifest);
        return flattenedEntries().entrySet().stream()
                .filter(target -> !Objects.equals(target.getValue().version(), currentVersions.get(target.getKey())))
                .map(target -> new OtaManifestMismatch(target.getKey(), target.getValue().name(),
                        target.getValue().version(), currentVersions.get(target.getKey())))
                .toList();
    }

    private Map<String, OtaManifestEntry> flattenedEntries() {
        Map<String, OtaManifestEntry> entriesByKey = new HashMap<>();
        collect(entries, entriesByKey);
        return entriesByKey;
    }

    private static void collect(List<OtaManifestEntry> entries, Map<String, OtaManifestEntry> entriesByKey) {
        if (entries == null) {
            return;
        }
        for (OtaManifestEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            entriesByKey.put(entry.key(), entry);
            collect(entry.children(), entriesByKey);
        }
    }

    private static Map<String, String> flattenRobotManifest(List<RobotVersionManifestEntry> entries) {
        Map<String, String> versions = new HashMap<>();
        collectRobotManifest(entries, versions);
        return versions;
    }

    private static void collectRobotManifest(List<RobotVersionManifestEntry> entries, Map<String, String> versions) {
        if (entries == null) {
            return;
        }
        for (RobotVersionManifestEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            versions.put(entry.key(), entry.version());
            collectRobotManifest(entry.children(), versions);
        }
    }
}
