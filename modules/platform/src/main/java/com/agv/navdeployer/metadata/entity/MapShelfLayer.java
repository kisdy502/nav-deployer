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

/** 货架层（layers）：层索引 + 相对高度 + 存取边。 */
@TableName("map_shelf_layer")
@Getter
@Setter
@NoArgsConstructor
public class MapShelfLayer {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long shelfId;

    /** 层索引，从 0 开始（底层 0）。 */
    private Integer layerIndex;

    /** 相对楼层基准面的高度偏移（米）。 */
    private Double elevation;

    /** 存取边索引（指向 footprint.edges 的 edgeIndex）。 */
    private Integer accessEdge;

    private Integer numSlots;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
