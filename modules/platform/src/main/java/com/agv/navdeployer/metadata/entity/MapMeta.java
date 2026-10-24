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

/** 多楼层地图主表：版本信息 + 全局栅格元数据（栅格数据 gzip 存 MinIO）。 */
@TableName("map")
@Getter
@Setter
@NoArgsConstructor
public class MapMeta {

    /** DRAFT：编辑中；PUBLISHED：已发布使用中（全局唯一）；ARCHIVED：被替换归档。 */
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

    /** 坐标系类型，固定 map。 */
    private String coordinateSystem;

    /** 坐标单位，固定 meters。 */
    private String unit;

    /** 语义化版本号 v0.0.1 起，每次变更自动 +1。 */
    private String version;

    private String status;

    /** 最后修改人。 */
    private String modifiedBy;

    /** 来源：LIVE / ROBOT_SYNC / IMPORT / MIGRATED。 */
    private String source;

    /** 机器人侧地图名（切图时下发给 /agv/load_map）。 */
    private String robotMapName;

    /** 米/格。 */
    private Double resolution;

    private Integer width;

    private Integer height;

    private Double originX;

    private Double originY;

    private Double originYaw;

    /** MinIO 对象 key（gzip 的 OccupancyGrid JSON）。 */
    private String objectKey;

    private Long dataSize;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
