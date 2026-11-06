package com.zioneer.robotmanager.robot.ota.application.dto;

import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaManifestEntry;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageKind;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OtaPackageVO {

    @Schema(description = "主键")
    private String id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "类型")
    private OtaPackageKind kind;

    @Schema(description = "版本")
    private String version;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "来源")
    private List<OtaPackageSource> sources;

    @Schema(description = "清单")
    private List<OtaManifestEntry> manifest;

    @Schema(description = "体积：全部来源制品字节数之和")
    private Long totalSize;

    @Schema(description = "上传者")
    private String createdBy;

    @Schema(description = "创建时间")
    private Instant createdAt;

    @Schema(description = "更新时间")
    private Instant updatedAt;
}
