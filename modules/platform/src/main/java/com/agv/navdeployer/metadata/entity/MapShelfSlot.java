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

@TableName("map_shelf_slot")
@Getter
@Setter
@NoArgsConstructor
public class MapShelfSlot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long layerId;

    private Long shelfId;

    private Integer slotIndex;

    private Double x;

    private Double y;

    private Double z;

    private Double width;

    private Double depth;

    private Double height;

    private String productId;

    private String productName;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
