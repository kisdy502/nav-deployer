package com.agv.navdeployer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 路线的一段（边）：source→target，直线或贝塞尔曲线，可倒车/限速。 */
@TableName("nav_path_edge")
@Getter
@Setter
@NoArgsConstructor
public class NavPathEdge {

    public static final String TYPE_STRAIGHT = "STRAIGHT";
    public static final String TYPE_CURVE = "CURVE";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long pathId;

    /** 段序号，从 1 开始连续递增。 */
    private Integer seq;

    private Long sourcePointId;

    private Long targetPointId;

    private String edgeType;

    /** JSON 数组 [{"x":..,"y":..}]，曲线时 1~2 个贝塞尔控制点。 */
    private String controlPoints;

    private Double maxSpeed;

    private Boolean backUp;

    private Boolean reverse;
}
