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

@TableName("map_zone")
@Getter
@Setter
@NoArgsConstructor
public class MapZone {

    public static final String TYPE_FORBIDDEN = "forbidden";
    public static final String TYPE_SLOW_DOWN = "slowDown";
    public static final String TYPE_RAMP = "ramp";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private Long floorId;

    private String zoneCode;

    private String name;

    private String zoneType;

    private Integer priority;

    private Double maxSpeed;

    private String shape;

    private String vertices;

    private Double height;

    private String rampInfo;

    private String description;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
