package com.agv.navdeployer.service;

import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.metadata.entity.MapMeta;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import com.agv.navdeployer.sim.SimAgvProperties;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 地图一致性闸门：机器人当前地图 vs 上位机导航基准图（v2 PUBLISHED 优先，v1 ACTIVE 兜底）。
 * 只拦「确认不一致」；无法确认（未上报地图名 / 基准图未关联 robot_map_name）放行。
 */
class MapAlignmentGuardTest {

    private final SimAgvTelemetry telemetry = new SimAgvTelemetry();
    private final SimAgvProperties props = new SimAgvProperties();
    private final MapMetaMapper v2MapMapper = mock(MapMetaMapper.class);
    private final NavMapMapper navMapMapper = mock(NavMapMapper.class);
    private final MapAlignmentGuard guard =
            new MapAlignmentGuard(telemetry, props, v2MapMapper, navMapMapper);

    private void robotOnMap(String mapName) {
        telemetry.setConnected(true);
        telemetry.setStatus(new SimAgvTelemetry.StatusSnapshot(
                "AGV001", "IDLE", 50.0, true, "", "", "NAVIGATION",
                mapName, null, null, null, null, Instant.now()));
    }

    private static MapMeta published(String mapName, String robotMapName) {
        MapMeta map = new MapMeta();
        map.setMapName(mapName);
        map.setStatus(MapMeta.STATUS_PUBLISHED);
        map.setRobotMapName(robotMapName);
        return map;
    }

    private static NavMap active(String mapName, String robotMapName) {
        NavMap map = new NavMap();
        map.setMapName(mapName);
        map.setStatus(NavMap.STATUS_ACTIVE);
        map.setRobotMapName(robotMapName);
        return map;
    }

    /** 事故场景：机器人加载 map1025L，v2 已发布基准图关联 map1027S → 拦截并给出两侧图名 */
    @Test
    void v2PublishedMismatchMustRejectWithBothMapNames() {
        robotOnMap("map1025L");
        when(v2MapMapper.selectOne(any())).thenReturn(published("map1027S", "map1027S"));
        assertThatThrownBy(guard::requireAligned)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("map1025L")
                .hasMessageContaining("map1027S")
                .hasMessageContaining("地图不一致");
        assertThat(guard.current().state()).isEqualTo(MapAlignmentGuard.Alignment.MISMATCH);
    }

    @Test
    void v2PublishedAlignedPasses() {
        robotOnMap("map1027S");
        when(v2MapMapper.selectOne(any())).thenReturn(published("map1027S", "map1027S"));
        assertThatCode(guard::requireAligned).doesNotThrowAnyException();
        assertThat(guard.current().state()).isEqualTo(MapAlignmentGuard.Alignment.ALIGNED);
    }

    /** v2 基准图未关联 robot_map_name（无法比对）→ 回退 v1 ACTIVE 基准 */
    @Test
    void v2WithoutLinkFallsBackToV1Active() {
        robotOnMap("map1025L");
        when(v2MapMapper.selectOne(any())).thenReturn(published("hand-imported", null));
        when(navMapMapper.selectOne(any())).thenReturn(active("map1025L", "map1025L"));
        assertThatCode(guard::requireAligned).doesNotThrowAnyException();
        assertThat(guard.current().state()).isEqualTo(MapAlignmentGuard.Alignment.ALIGNED);
    }

    @Test
    void v1MismatchMustReject() {
        robotOnMap("map1025L");
        when(v2MapMapper.selectOne(any())).thenReturn(null);
        when(navMapMapper.selectOne(any())).thenReturn(active("map1027S", "map1027S"));
        assertThatThrownBy(guard::requireAligned)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("地图不一致");
    }

    /** 机器人未上报地图名（旧版机器人/遥测缺失）→ 无法确认，放行 */
    @Test
    void unknownRobotMapSkipsGate() {
        assertThatCode(guard::requireAligned).doesNotThrowAnyException();
        assertThat(guard.current().state()).isEqualTo(MapAlignmentGuard.Alignment.UNKNOWN);
    }

    /** 无任何基准图关联（v2/v1 都没有 robot_map_name）→ 无法确认，放行 */
    @Test
    void noReferenceSkipsGate() {
        robotOnMap("mapX");
        when(v2MapMapper.selectOne(any())).thenReturn(published("no-link", null));
        when(navMapMapper.selectOne(any())).thenReturn(active("no-link-v1", null));
        assertThatCode(guard::requireAligned).doesNotThrowAnyException();
        assertThat(guard.current().state()).isEqualTo(MapAlignmentGuard.Alignment.UNKNOWN);
    }
}
