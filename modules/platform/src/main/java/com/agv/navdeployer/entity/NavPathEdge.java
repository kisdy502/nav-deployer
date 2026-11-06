package com.agv.navdeployer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    private Integer seq;

    private Long sourcePointId;

    private Long targetPointId;

    private String edgeType;

    private String controlPoints;

    private Double maxSpeed;

    private Boolean backUp;

    private Boolean reverse;
}
