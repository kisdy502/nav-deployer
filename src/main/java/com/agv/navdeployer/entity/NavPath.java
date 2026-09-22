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

/** 部署路线：有序边序列（nav_path_edge），按 seq 逐段执行。 */
@TableName("nav_path")
@Getter
@Setter
@NoArgsConstructor
public class NavPath {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_DEPLOYED = "DEPLOYED";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long mapId;

    private String pathCode;

    private String pathName;

    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
