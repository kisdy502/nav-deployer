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

/** 地图上的部署点位（map 系坐标，yaw 弧度）。 */
@TableName("nav_point")
@Getter
@Setter
@NoArgsConstructor
public class NavPoint {

    public static final String TYPE_NORMAL = "NORMAL";
    public static final String TYPE_CHARGER = "CHARGER";
    public static final String TYPE_HOME = "HOME";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private String pointCode;

    private String pointType;

    private Double x;

    private Double y;

    private Double yaw;

    private String remark;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
