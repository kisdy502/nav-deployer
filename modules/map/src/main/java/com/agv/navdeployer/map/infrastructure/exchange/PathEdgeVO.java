package com.agv.navdeployer.map.infrastructure.exchange;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PathEdgeVO {

    private Long id;

    private Integer seq;

    private Long sourcePointId;

    private String sourcePointCode;

    private Long targetPointId;

    private String targetPointCode;

    private String edgeType;

    private List<ControlPointVO> controlPoints;

    private Double maxSpeed;

    private Boolean backUp;

    private Boolean reverse;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ControlPointVO {

        private Double x;

        private Double y;
    }
}
