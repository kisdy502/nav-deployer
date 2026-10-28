package com.agv.navdeployer.service;

import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.metadata.entity.MapMeta;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import com.agv.navdeployer.sim.SimAgvProperties;
import com.agv.navdeployer.sim.SimAgvTelemetry;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 地图一致性闸门：机器人当前加载的地图（/agv/status 遥测 map_name）必须与
 * 上位机导航基准图一致，否则同名点位坐标对不上，任务会导航到错误物理位置（乱走乱撞）。
 *
 * <p>导航基准图优先级：v2 PUBLISHED 地图（RMS 任务 / 充电 / 智能导航的规划基准，
 * SmartNavigationService 只在 PUBLISHED 地图上规划路线）→ v1 ACTIVE 地图（TO_POINT 基准）。
 * 基准图未关联 robot_map_name（无法比对）或机器人未上报地图名（遥测缺失/过期）时放行——
 * 只拦截「确认不一致」，不拦「无法确认」（避免误伤不上报 map_name 的旧机器人）。
 */
@Component
public class MapAlignmentGuard {

    private static final Logger log = LoggerFactory.getLogger(MapAlignmentGuard.class);

    private final SimAgvTelemetry telemetry;
    private final SimAgvProperties props;
    private final MapMetaMapper v2MapMapper;
    private final NavMapMapper navMapMapper;

    public MapAlignmentGuard(SimAgvTelemetry telemetry,
                             SimAgvProperties props,
                             MapMetaMapper v2MapMapper,
                             NavMapMapper navMapMapper) {
        this.telemetry = telemetry;
        this.props = props;
        this.v2MapMapper = v2MapMapper;
        this.navMapMapper = navMapMapper;
    }

    /** 对齐状态（/robot/operational 与前端警示用）。 */
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
        // v2 PUBLISHED：RMS / 充电 / 智能导航的规划基准
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
        // 回退 v1 ACTIVE：TO_POINT 基准（周期对账通常已自动对齐）
        NavMap active = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getStatus, NavMap.STATUS_ACTIVE).last("LIMIT 1"));
        if (active != null && !isBlank(active.getRobotMapName())) {
            String ref = active.getRobotMapName().trim();
            if (robotMap.equals(ref)) {
                return new Alignment(Alignment.ALIGNED, robotMap, ref,
                        "v1 激活地图「" + active.getMapName() + "」", null);
            }
            return mismatch(robotMap, ref, "v1 激活地图「" + active.getMapName() + "」(ACTIVE)");
        }
        return new Alignment(Alignment.UNKNOWN, robotMap, null, null,
                "上位机导航基准图未关联机器人地图名（robot_map_name 为空），无法校验地图一致性；"
                        + "请在地图管理中维护地图与机器人地图名的对应关系");
    }

    /**
     * 任务下发闸门：确认不一致时抛 {@link IllegalStateException}（消息随任务错误透传）。
     * 无法确认（UNKNOWN）时放行。
     */
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
