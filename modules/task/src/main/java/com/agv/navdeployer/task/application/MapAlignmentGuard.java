package com.agv.navdeployer.task.application;

import com.agv.navdeployer.metadata.entity.MapMeta;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import com.agv.navdeployer.sim.SimAgvProperties;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MapAlignmentGuard {

    private static final Logger log = LoggerFactory.getLogger(MapAlignmentGuard.class);

    private final SimAgvTelemetry telemetry;
    private final SimAgvProperties props;
    private final MapMetaMapper v2MapMapper;

    public MapAlignmentGuard(SimAgvTelemetry telemetry,
                             SimAgvProperties props,
                             MapMetaMapper v2MapMapper) {
        this.telemetry = telemetry;
        this.props = props;
        this.v2MapMapper = v2MapMapper;
    }

    public record Alignment(String state, String robotMapName, String refMapName,
                            String refSource, String message) {
        public static final String ALIGNED = "ALIGNED";
        public static final String MISMATCH = "MISMATCH";
        public static final String UNKNOWN = "UNKNOWN";
    }

    public Alignment current() {
        String robotMap = null;
        if (telemetry.isStatusFresh(props.getStatusFreshMs()) && telemetry.getStatus() != null) {
            robotMap = telemetry.getStatus().mapName();
        }
        if (robotMap == null || robotMap.isBlank()) {
            return new Alignment(Alignment.UNKNOWN, null, null, null,
                    "机器人未上报当前地图名（或遥测过期），无法校验地图一致性");
        }
        MapMeta published = v2MapMapper.selectOne(Wrappers.lambdaQuery(MapMeta.class)
                .eq(MapMeta::getStatus, MapMeta.STATUS_PUBLISHED).last("LIMIT 1"));
        if (published != null && !isBlank(published.getRobotMapName())) {
            String ref = published.getRobotMapName().trim();
            if (robotMap.equals(ref)) {
                return new Alignment(Alignment.ALIGNED, robotMap, ref,
                        "v2 已发布地图「" + published.getMapName() + "」", null);
            }
            return mismatch(robotMap, ref,
                    "v2 已发布地图「" + published.getMapName() + "」(PUBLISHED)");
        }
        return new Alignment(Alignment.UNKNOWN, robotMap, null, null,
                "上位机导航基准图未关联机器人地图名（robot_map_name 为空），无法校验地图一致性；"
                        + "请在地图管理中维护地图与机器人地图名的对应关系");
    }

    public void requireAligned() {
        Alignment alignment = current();
        if (Alignment.MISMATCH.equals(alignment.state())) {
            log.warn("map alignment gate rejected task: robot={} ref={} source={}",
                    alignment.robotMapName(), alignment.refMapName(), alignment.refSource());
            throw new IllegalStateException(alignment.message());
        }
    }

    private static Alignment mismatch(String robotMap, String ref, String source) {
        return new Alignment(Alignment.MISMATCH, robotMap, ref, source,
                "地图不一致：机器人当前加载「" + robotMap + "」，上位机导航基准为「" + ref + "」（" + source
                        + "）。两张地图中同名点位的坐标不对应，继续执行任务会导航到错误位置。"
                        + "请先把机器人切换到基准地图（或更新上位机基准图）后再下发任务");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
