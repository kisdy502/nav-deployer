package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.entity.MapPathSegment;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/** 路线段视图（含合并的曲线能力：segmentType/controlPoints/backUp/reverse）。 */
public record SegmentVO(Long id,
                        Integer seq,
                        Long sourceWaypointId,
                        Long targetWaypointId,
                        String segmentType,
                        List<double[]> controlPoints,
                        Double maxSpeed,
                        Boolean backUp,
                        Boolean reverse) {

    private static final TypeReference<List<double[]>> CONTROL_POINTS_TYPE = new TypeReference<>() {
    };

    public static SegmentVO from(MapPathSegment segment) {
        List<double[]> controlPoints = segment.getControlPoints() == null
                ? List.of()
                : ZoneVO.parseJson(segment.getControlPoints(), CONTROL_POINTS_TYPE);
        return new SegmentVO(segment.getId(), segment.getSeq(), segment.getSourceWaypointId(),
                segment.getTargetWaypointId(), segment.getSegmentType(), controlPoints,
                segment.getMaxSpeed(), segment.getBackUp(), segment.getReverse());
    }
}
