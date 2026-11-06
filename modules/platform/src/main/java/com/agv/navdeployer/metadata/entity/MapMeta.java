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

@TableName("map")
@Getter
@Setter
@NoArgsConstructor
public class MapMeta {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_ROBOT_SYNC = "ROBOT_SYNC";
    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_MIGRATED = "MIGRATED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String mapName;

    private String coordinateSystem;

    private String unit;

    private String version;

    private String status;

    private String modifiedBy;

    private String source;

    private String robotMapName;

    private Double resolution;

    private Integer width;

    private Integer height;

    private Double originX;

    private Double originY;

    private Double originYaw;

    private String objectKey;

    private Long dataSize;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
