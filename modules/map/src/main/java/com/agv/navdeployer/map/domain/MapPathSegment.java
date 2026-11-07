package com.agv.navdeployer.map.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

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

    private Integer seq;

    private Long sourceWaypointId;

    private Long targetWaypointId;

    private String segmentType;

    private String controlPoints;

    private Double maxSpeed;

    private Boolean backUp;

    private Boolean reverse;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
