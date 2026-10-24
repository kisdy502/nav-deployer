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

/** 楼层（map-info.json 的 floors 元素）。 */
@TableName("map_floor")
@Getter
@Setter
@NoArgsConstructor
public class MapFloor {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    /** 楼层唯一标识（F1 / F2）。 */
    private String floorCode;

    private String name;

    /** 楼层基准高度（Z，米）。 */
    private Double elevation;

    /** 楼层默认最大速度（m/s）。 */
    private Double defaultMaxSpeed;

    private Integer sortOrder;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
