package com.agv.navdeployer.map.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@TableName("map_shelf")
@Getter
@Setter
@NoArgsConstructor
public class MapShelf {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private Long floorId;

    private String shelfCode;

    private String name;

    private String vertices;

    private Double height;

    private String edges;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
