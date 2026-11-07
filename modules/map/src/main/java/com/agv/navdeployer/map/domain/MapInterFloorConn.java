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

@TableName("map_inter_floor_conn")
@Getter
@Setter
@NoArgsConstructor
public class MapInterFloorConn {

    public static final String TYPE_ELEVATOR = "elevator";
    public static final String TYPE_STAIRS = "stairs";
    public static final String TYPE_RAMP = "ramp";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private String connCode;

    private String name;

    private String connType;

    private String connections;

    private Double capacity;

    private Double maxSpeed;

    private String rampInfo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
