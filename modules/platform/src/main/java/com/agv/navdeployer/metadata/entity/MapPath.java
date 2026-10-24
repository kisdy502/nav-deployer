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

/** 路线（paths）：from/to + 途经点位链（链在段表）+ 限速/避障。 */
@TableName("map_path")
@Getter
@Setter
@NoArgsConstructor
public class MapPath {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_DEPLOYED = "DEPLOYED";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private Long floorId;

    /** 路线唯一标识（PATH_001）。 */
    private String pathCode;

    private String name;

    private Long fromWaypointId;

    private Long toWaypointId;

    private Boolean isBidirectional;

    /** 路线限速（空 = 使用楼层默认）。 */
    private Double maxSpeed;

    private Boolean enableObstacleAvoidance;

    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
