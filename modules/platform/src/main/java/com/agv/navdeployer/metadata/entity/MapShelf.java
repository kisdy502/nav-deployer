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

/** 货架（shelves）：footprint 占地多边形 + 边定义，层/格位在子表。 */
@TableName("map_shelf")
@Getter
@Setter
@NoArgsConstructor
public class MapShelf {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private Long floorId;

    /** 货架唯一标识（SH_001）。 */
    private String shelfCode;

    private String name;

    /** footprint 多边形顶点 JSON [[x,y],...]。 */
    private String vertices;

    /** 货架总高度（米）。 */
    private Double height;

    /** 边定义 JSON [{edgeIndex,vertexStart,vertexEnd,direction?}]。 */
    private String edges;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
