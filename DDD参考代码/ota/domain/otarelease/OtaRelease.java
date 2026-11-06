package com.zioneer.robotmanager.robot.ota.domain.otarelease;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zioneer.robotmanager.common.mybatis.domain.BaseEntity;
import com.zioneer.robotmanager.common.mybatis.support.JsonbTypeHandler;
import com.zioneer.robotmanager.common.mybatis.support.SnowflakeIdGenerator;
import com.zioneer.robotmanager.robot.ota.application.cmd.CreateOtaReleaseCmd;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@TableName(value = "ota_release", autoResultMap = true)
public class OtaRelease extends BaseEntity {

    @TableField(exist = false)
    private Boolean deleted;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "安装包快照")
    @TableField(typeHandler = JsonbTypeHandler.class)
    private OtaPackageSnapshot packageSnapshot;

    @Schema(description = "目标维度")
    private OtaTargetDimension targetDimension;

    @Schema(description = "目标值")
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<String> targetValues;

    public OtaRelease() {
    }


    public OtaRelease(CreateOtaReleaseCmd cmd, OtaPackageSnapshot snapshot, SnowflakeIdGenerator snowflakeIdGenerator) {
        setId(snowflakeIdGenerator.nextId());
        this.name = trim(cmd.getName());
        this.packageSnapshot = snapshot;
        this.targetDimension = cmd.getTargetDimension();
        this.targetValues = cmd.getTargetValues();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
