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

/** 楼层间连接（interFloorConnections）：elevator / stairs / ramp。 */
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

    /** 连接唯一标识（CONN_001）。 */
    private String connCode;

    private String name;

    /** elevator / stairs / ramp。 */
    private String connType;

    /** 端点 JSON [{floorId,waypointId}]（楼层/点位编码）。 */
    private String connections;

    /** 载重限制（kg，空 = 无限制）。 */
    private Double capacity;

    /** 通道内最大速度（m/s）。 */
    private Double maxSpeed;

    /** ramp 类型：JSON {slope,length,surface}。 */
    private String rampInfo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
