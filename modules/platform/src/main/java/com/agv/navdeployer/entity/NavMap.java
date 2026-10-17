package com.agv.navdeployer.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 部署地图（Cartographer 建图快照），栅格数据存 MinIO。 */
@TableName("nav_map")
@Getter
@Setter
@NoArgsConstructor
public class NavMap {

    /** DRAFT：已保存；ACTIVE：当前部署地图（全局唯一）；ARCHIVED：被替换归档。 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String mapName;

    private String status;

    private String source;

    /** 机器人侧地图名（maps_dir 三件套文件名主干，切图时下发给 /agv/load_map）。 */
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
