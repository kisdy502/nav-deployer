package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.domain.MapPathSegment;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

public record SegmentDTO(Long id,
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

    public static SegmentDTO from(MapPathSegment segment) {
        List<double[]> controlPoints = segment.getControlPoints() == null
                ? List.of()
                : ZoneDTO.parseJson(segment.getControlPoints(), CONTROL_POINTS_TYPE);
        return new SegmentDTO(segment.getId(), segment.getSeq(), segment.getSourceWaypointId(),
                segment.getTargetWaypointId(), segment.getSegmentType(), controlPoints,
                segment.getMaxSpeed(), segment.getBackUp(), segment.getReverse());
    }
}
