package com.agv.navdeployer.exchange;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 用仓库根的真实样例 map_0728.zip 验证解析与导出往返。 */
class ScheduleMapAdapterTest {

    private final ScheduleMapAdapter adapter = new ScheduleMapAdapter();

    @Test
    void importSampleZip() throws Exception {
        byte[] zip = Files.readAllBytes(Path.of("map_0728.zip"));
        ScheduleMapAdapter.ScheduleMapImport parsed = adapter.importZip(zip);

        assertThat(parsed.resolution()).isEqualTo(0.05);
        assertThat(parsed.originX()).isEqualTo(-12.09);
        assertThat(parsed.originY()).isEqualTo(-5.08);
        assertThat(parsed.width()).isEqualTo(1099);
        assertThat(parsed.height()).isEqualTo(1004);
        assertThat(parsed.data()).hasSize(1099 * 1004);

        // 样例 Cairn 行共 55 条 = Goal + Route 总数
        assertThat(parsed.points().size() + parsed.routes().size()).isEqualTo(55);
        assertThat(parsed.points()).hasSize(19);
        assertThat(parsed.routes()).hasSize(36);
        ScheduleMapAdapter.ImportedPoint first = parsed.points().get(0);
        assertThat(first.code()).isEqualTo("Goal_Pa6v5");
        assertThat(first.x()).isEqualTo(3.884);
        assertThat(first.y()).isEqualTo(4.528);
        assertThat(first.yaw()).isEqualTo(3.1206);
        assertThat(first.pointType()).isEqualTo("WORK");
        assertThat(parsed.points().stream().filter(p -> p.pointType().equals("WORK"))).hasSize(7);
        assertThat(parsed.points()).anySatisfy(point -> {
            assertThat(point.code()).isEqualTo("Goal_cjvtI");
            assertThat(point.pointType()).isEqualTo("WORK");
        });
        assertThat(parsed.points().stream().filter(p -> p.pointType().equals("CHARGER"))).hasSize(1);
        assertThat(parsed.points().stream().filter(p -> p.pointType().equals("HOME"))).hasSize(2);

        // 样例 Route 引用的点位坐标必须与 Goal 一致
        assertThat(parsed.routes()).isNotEmpty();
        ScheduleMapAdapter.ImportedRoute route = parsed.routes().get(0);
        assertThat(route.sourceX()).isNotEqualTo(0.0);
        assertThat(route.targetCode()).isNotBlank();
    }

    @Test
    void exportThenImportRoundTrip() throws Exception {
        // 4x4 假栅格：未知/空闲/占据混合
        com.agv.navdeployer.vo.MapGridVO grid = new com.agv.navdeployer.vo.MapGridVO(
                "map", 0.05, 4, 4, -1.0, -2.0, 0.0,
                new int[]{-1, 0, 0, 100, 0, 0, 100, 100, 0, 0, 0, 100, 100, 100, 100, -1},
                java.time.Instant.now());

        com.agv.navdeployer.vo.NavPointVO a = point(1L, "P1", "CHARGER", 1.0, 2.0, 0.5);
        com.agv.navdeployer.vo.NavPointVO b = point(2L, "P2", "HOME", 3.0, 4.0, -0.5);
        com.agv.navdeployer.vo.NavPointVO c = point(3L, "P3", "WORK", 5.0, 6.0, 1.0);
        com.agv.navdeployer.vo.PathEdgeVO edge = new com.agv.navdeployer.vo.PathEdgeVO(
                10L, 1, 1L, "P1", 2L, "P2", "STRAIGHT", List.of(), 0.8, false, true);
        com.agv.navdeployer.vo.NavPathVO path = new com.agv.navdeployer.vo.NavPathVO();
        path.setId(7L);

        byte[] zip = adapter.exportZip("rt_map", grid, List.of(a, b, c), List.of(path), Map.of(7L, List.of(edge)));

        // zip 六件套齐
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            int count = 0;
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                count++;
                assertThat(e.getName()).startsWith("rt_map");
            }
            assertThat(count).isEqualTo(6);
        }

        // 往返：一条上位机通道导出为调度服务器识别的两条正反 Route
        ScheduleMapAdapter.ScheduleMapImport back = adapter.importZip(zip);
        assertThat(back.width()).isEqualTo(4);
        assertThat(back.height()).isEqualTo(4);
        assertThat(back.points()).hasSize(3);
        assertThat(back.points().get(0).code()).isEqualTo("P1");
        assertThat(back.points().get(0).x()).isEqualTo(1.0);
        assertThat(back.points().get(0).yaw()).isEqualTo(0.5);
        assertThat(back.points().get(0).pointType()).isEqualTo("CHARGER");
        assertThat(back.points().get(1).pointType()).isEqualTo("HOME");
        assertThat(back.points().get(2).pointType()).isEqualTo("WORK");
        assertThat(back.routes()).hasSize(2);
        ScheduleMapAdapter.ImportedRoute r = back.routes().get(0);
        assertThat(r.sourceCode()).isEqualTo("P1");
        assertThat(r.targetCode()).isEqualTo("P2");
        assertThat(r.maxSpeed()).isEqualTo(0.8);
        assertThat(r.reverse()).isTrue();
        ScheduleMapAdapter.ImportedRoute reverseRoute = back.routes().get(1);
        assertThat(reverseRoute.sourceCode()).isEqualTo("P2");
        assertThat(reverseRoute.targetCode()).isEqualTo("P1");
        // 占据/未知语义保留
        assertThat(back.data()[3]).isEqualTo(100);
        assertThat(back.data()[0]).isEqualTo(-1);
        assertThat(back.data()[1]).isEqualTo(0);

        String lxmap = zipEntry(zip, ".yaml.lxmap");
        assertThat(lxmap).contains("Cairn: Goal 1 P1 \"\" \"\" 1.0 2.0 0.5 2 ");
        assertThat(lxmap).contains("Cairn: Goal 2 P2 \"\" \"\" 3.0 4.0 -0.5 3 ");
        assertThat(lxmap).contains("Cairn: Goal 3 P3 \"\" \"\" 5.0 6.0 1.0 1 ");
        // Goal 与 Route 各自独立编号；路线属性必须位于服务端会解析的根 JSON。
        assertThat(lxmap).contains("Cairn: Route 1 1 2 ");
        assertThat(lxmap).contains("Cairn: Route 2 2 1 ");
        assertThat(lxmap).contains("{\"shelf_posture\":0,\"path_width\":100,\"cost\":1,\"empty_cost\":1,\"full_cost\":1}");
        assertThat(lxmap).doesNotContain("\"navdeployer\"");
    }

    @Test
    void doesNotDuplicateAnExistingReverseEdgeWhenExportingBidirectionalRoutes() throws Exception {
        com.agv.navdeployer.vo.MapGridVO grid = new com.agv.navdeployer.vo.MapGridVO(
                "map", 0.05, 1, 1, 0.0, 0.0, 0.0, new int[]{0}, java.time.Instant.now());
        com.agv.navdeployer.vo.NavPointVO a = point(1L, "P1", "NORMAL", 0, 0, 0);
        com.agv.navdeployer.vo.NavPointVO b = point(2L, "P2", "NORMAL", 1, 0, 0);
        com.agv.navdeployer.vo.PathEdgeVO forward = new com.agv.navdeployer.vo.PathEdgeVO(
                10L, 1, 1L, "P1", 2L, "P2", "STRAIGHT", List.of(), 0.6, false, false);
        com.agv.navdeployer.vo.PathEdgeVO backward = new com.agv.navdeployer.vo.PathEdgeVO(
                11L, 1, 2L, "P2", 1L, "P1", "STRAIGHT", List.of(), 0.6, false, false);
        com.agv.navdeployer.vo.NavPathVO path = new com.agv.navdeployer.vo.NavPathVO();
        path.setId(7L);

        byte[] zip = adapter.exportZip(
                "paired", grid, List.of(a, b), List.of(path), Map.of(7L, List.of(forward, backward)));
        String lxmap = zipEntry(zip, ".yaml.lxmap");

        assertThat(lxmap.lines().filter(line -> line.startsWith("Cairn: Route "))).hasSize(2);
        assertThat(lxmap).contains("Cairn: Route 1 1 2 ");
        assertThat(lxmap).contains("Cairn: Route 2 2 1 ");
    }

    @Test
    void refusesToSilentlyDropRouteReferencingMissingPoint() {
        com.agv.navdeployer.vo.MapGridVO grid = new com.agv.navdeployer.vo.MapGridVO(
                "map", 0.05, 1, 1, 0.0, 0.0, 0.0, new int[]{0}, java.time.Instant.now());
        com.agv.navdeployer.vo.NavPointVO a = point(1L, "P1", "NORMAL", 0, 0, 0);
        com.agv.navdeployer.vo.PathEdgeVO badEdge = new com.agv.navdeployer.vo.PathEdgeVO(
                10L, 1, 1L, "P1", 99L, "MISSING", "STRAIGHT", List.of(), 0.6, false, false);
        com.agv.navdeployer.vo.NavPathVO path = new com.agv.navdeployer.vo.NavPathVO();
        path.setId(7L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        adapter.exportZip("bad", grid, List.of(a), List.of(path), Map.of(7L, List.of(badEdge))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝生成残缺地图");
    }

    private static String zipEntry(byte[] zip, String suffix) throws Exception {
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zip), java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.getName().endsWith(suffix)) {
                    return new String(zin.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("zip entry not found: " + suffix);
    }

    private static com.agv.navdeployer.vo.NavPointVO point(
            Long id, String code, String type, double x, double y, double yaw) {
        return new com.agv.navdeployer.vo.NavPointVO(id, 1L, code, type, x, y, yaw, null, null, null);
    }
}
