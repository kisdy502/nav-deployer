package com.agv.navdeployer.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 路线段：相邻两个途经点位之间的段。
 * 合并旧 nav_path_edge 的曲线能力：STRAIGHT / CURVE + 贝塞尔控制点。
 */
@TableName("map_path_segment")
@Getter
@Setter
@NoArgsConstructor
public class MapPathSegment {

    public static final String TYPE_STRAIGHT = "STRAIGHT";
    public static final String TYPE_CURVE = "CURVE";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long pathId;

    /** 段序号，从 1 开始。 */
    private Integer seq;

    private Long sourceWaypointId;

    private Long targetWaypointId;

    /** STRAIGHT / CURVE。 */
    private String segmentType;

    /** CURVE 时 JSON [{x,y},...]，1~2 个贝塞尔控制点。 */
    private String controlPoints;

    /** 段级限速（空 = 用路线限速/楼层默认）。 */
    private Double maxSpeed;

    private Boolean backUp;

    private Boolean reverse;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
