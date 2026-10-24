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

/** 区域约束（zoneConstraints）：forbidden 禁行 / slowDown 减速 / ramp 坡道。 */
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

    /** 区域唯一标识（ZC_001）。 */
    private String zoneCode;

    private String name;

    private String zoneType;

    /** 优先级，数字越小越高。 */
    private Integer priority;

    /** 区域内最大速度（禁行区 0）。 */
    private Double maxSpeed;

    private String shape;

    /** 多边形顶点 JSON [[x,y],...]。 */
    private String vertices;

    /** 区域高度（米），可空。 */
    private Double height;

    /** ramp 类型：JSON {slope,direction,startElevation,endElevation}。 */
    private String rampInfo;

    private String description;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
