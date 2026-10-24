package com.agv.navdeployer.metadata.exchange;

import com.agv.navdeployer.metadata.entity.MapMeta;
import com.agv.navdeployer.metadata.service.MapMetaService;
import com.agv.navdeployer.metadata.vo.*;
import com.agv.navdeployer.vo.MapGridVO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LegacyTopologyExporterTest {
    @Test
    void retainsLegacyTypesAndDefaultsNewTypes() {
        assertThat(List.of("NORMAL", "WORK", "CHARGER", "HOME", "path", "workstation",
                        "charge", "parking", "pickup", "dropoff", "storage", "elevator", "stairs",
                        "ramp", "relocation", "unknown").stream().map(LegacyTopologyExporter::legacyType))
                .containsExactly("NORMAL", "WORK", "CHARGER", "HOME", "NORMAL", "WORK",
                        "CHARGER", "HOME", "NORMAL", "NORMAL", "NORMAL", "NORMAL", "NORMAL",
                        "NORMAL", "NORMAL", "NORMAL");
        assertThat(LegacyTopologyExporter.legacyType(null)).isEqualTo("NORMAL");
    }

    @Test
    void v2ZipContainsLegacyTopologyAndOriginalMetadata() throws Exception {
        var grid = new MapGridVO("map", 0.05, 1, 1, -1.0, -2.0, 0.0,
                new int[]{0}, Instant.now());
        var floor = new FloorVO(1L, 1L, "F1", "Floor", 0.0, 0.4, 0);
        var points = List.of(point(1L, "charge"), point(2L, "parking"), point(3L, "pickup"));
        var full = new MapFullVO(null, List.of(new FloorBundleVO(floor, points, List.of(), List.of(),
                List.of(path(1L, 1L, 2L, false, 0.2), path(2L, 2L, 3L, true, null)))), List.of());
        MapMetaService service = mock(MapMetaService.class);
        MapMeta map = new MapMeta();
        map.setMapName("test");
        when(service.loadFull(1L)).thenReturn(full);
        when(service.requireMap(1L)).thenReturn(map);
        when(service.getGrid(1L)).thenReturn(grid);
        var packages = new MapPackageService(service, null, null, null, null, null,
                null, null, null, null, null, null, null);
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(packages.exportZip(1L)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).containsKeys("map_info.json", "global_map.yaml", "global_map.pgm",
                "global_map.png", "global_map.yaml.lxmap");
        assertThat(entries.get("map_info.json")).contains("pickup");
        String topology = entries.get("global_map.yaml.lxmap");
        assertThat(topology).contains("Rect: -1.0 -2.0 0.05 0.05",
                "Cairn: Goal 1 P1 \"\" \"\" 1.0 0.0 0.0 2 ",
                "Cairn: Goal 2 P2 \"\" \"\" 2.0 0.0 0.0 3 ",
                "Cairn: Goal 3 P3 \"\" \"\" 3.0 0.0 0.0 0 ",
                "Cairn: Route 1 1 2 1.0 0.0 2.0 0.0 1 0.2",
                "Cairn: Route 2 2 3", "Cairn: Route 3 3 2");
        assertThat(topology.lines().filter(line -> line.startsWith("Cairn: Route"))).hasSize(3);
    }

    private static WaypointVO point(Long id, String type) {
        return new WaypointVO(id, 1L, 1L, "P" + id, "P" + id, type, id.doubleValue(), 0.0, 0.0, null);
    }

    private static PathV2VO path(Long id, Long source, Long target, boolean bidirectional, Double speed) {
        return new PathV2VO(id, 1L, 1L, "R" + id, "R" + id, source, target, List.of(source, target),
                bidirectional, 0.5, true, "DRAFT", List.of(new SegmentVO(id, 1, source, target,
                "STRAIGHT", List.of(), speed, false, true)));
    }
}
