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

/** 点位（waypoints）。类型词表（11 类）：path/charge/pickup/dropoff/storage/elevator/stairs/ramp/relocation/parking/workstation。 */
@TableName("map_waypoint")
@Getter
@Setter
@NoArgsConstructor
public class MapWaypoint {

    public static final String TYPE_PATH = "path";
    public static final String TYPE_CHARGE = "charge";
    public static final String TYPE_PICKUP = "pickup";
    public static final String TYPE_DROPOFF = "dropoff";
    public static final String TYPE_STORAGE = "storage";
    public static final String TYPE_ELEVATOR = "elevator";
    public static final String TYPE_STAIRS = "stairs";
    public static final String TYPE_RAMP = "ramp";
    public static final String TYPE_RELOCATION = "relocation";
    public static final String TYPE_PARKING = "parking";
    public static final String TYPE_WORKSTATION = "workstation";
    public static final String TYPE_DOCK = "dock";
    public static final String TYPE_ROLLER = "roller";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private Long floorId;

    /** 点位唯一标识（WP_001，地图内唯一）。 */
    private String waypointCode;

    private String name;

    private String wpType;

    /** map 系坐标（米）。 */
    private Double x;

    private Double y;

    /** 朝向（弧度）。 */
    private Double yaw;

    private String remark;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
