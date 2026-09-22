package com.agv.navdeployer.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** 路线边（含点位编码冗余，便于前端直接画图）。 */
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

    /** [{"x":..,"y":..}]，解析后的控制点列表（直线为空）。 */
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
