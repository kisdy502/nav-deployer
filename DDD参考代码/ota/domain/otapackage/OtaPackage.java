package com.zioneer.robotmanager.robot.ota.domain.otapackage;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.common.mybatis.domain.BaseEntity;
import com.zioneer.robotmanager.common.mybatis.support.JsonbTypeHandler;
import com.zioneer.robotmanager.common.mybatis.support.SnowflakeIdGenerator;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.application.cmd.OtaPackageCmd;
import com.zioneer.robotmanager.robot.ota.application.cmd.UpdateOtaPackageCmd;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;


@Getter
@Setter
@TableName(value = "ota_package", autoResultMap = true)
public class OtaPackage extends BaseEntity {

    @TableLogic(value = "false", delval = "true")
    @TableField(value = "is_deleted", fill = FieldFill.INSERT)
    private Boolean deleted;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "类型")
    private OtaPackageKind kind;

    @Schema(description = "版本")
    private String version;

    @Schema(description = "描述")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    @Schema(description = "来源")
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<OtaPackageSource> sources;

    @Schema(description = "清单")
    @TableField(typeHandler = JsonbTypeHandler.class)
    private List<OtaManifestEntry> manifest;

    public OtaPackage() {
    }

    public OtaPackage(OtaPackageCmd cmd, SnowflakeIdGenerator snowflakeIdGenerator) {
        this(cmd, snowflakeIdGenerator, null);
    }

    public OtaPackage(OtaPackageCmd cmd, SnowflakeIdGenerator snowflakeIdGenerator, String uploader) {
        setId(snowflakeIdGenerator.nextId());
        this.name = trim(cmd.getName());
        this.kind = cmd.getKind();
        this.version = trim(cmd.getVersion());
        this.description = trimToNull(cmd.getDescription());
        this.sources = normalizeSources(cmd.getSources());
        this.manifest = cmd.getManifest();
        this.validate();
        setCreatedBy(uploader);
        setUpdatedBy(uploader);
    }

    public void validate() {
        requireSourceFieldsValid();
        requireUniqueSourceKeys();
        requireManifestFieldsValid(manifest);
        requireUniqueManifestKeys(manifest);
    }

    private void requireSourceFieldsValid() {
        if (sources == null || sources.isEmpty()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_SOURCE_INVALID,
                    "ota package sources must not be empty");
        }
        for (OtaPackageSource source : sources) {
            if (source == null || !source.checkFieldsValid()) {
                throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_SOURCE_INVALID,
                        "ota package source fields invalid: " + (source == null ? "null" : source.key()));
            }
        }
    }

    private void requireUniqueSourceKeys() {
        var seen = new HashSet<String>();
        for (OtaPackageSource source : sources) {
            if (!seen.add(source.key())) {
                throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_SOURCE_KEY_DUPLICATED,
                        "ota package source key duplicated: " + source.key());
            }
        }
    }

    private void requireManifestFieldsValid(List<OtaManifestEntry> entries) {
        if (entries == null) {
            return;
        }
        for (OtaManifestEntry entry : entries) {
            if (entry == null || isBlank(entry.key()) || isBlank(entry.version())) {
                throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_MANIFEST_ENTRY_INVALID,
                        "ota package manifest entry requires key and version");
            }
            requireManifestFieldsValid(entry.children());
        }
    }

    private void requireUniqueManifestKeys(List<OtaManifestEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_MANIFEST_ENTRY_INVALID,
                    "ota package manifest must not be empty");
        }
        requireUniqueManifestKeys(entries, new HashSet<>());
    }

    private void requireUniqueManifestKeys(List<OtaManifestEntry> entries, Set<String> seen) {
        if (entries == null) {
            return;
        }
        for (OtaManifestEntry entry : entries) {
            if (!seen.add(entry.key())) {
                throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_MANIFEST_ENTRY_KEY_DUPLICATED,
                        "ota manifest entry key duplicated: " + entry.key());
            }
            requireUniqueManifestKeys(entry.children(), seen);
        }
    }

    public OtaPackageSource sourceOf(String key) {
        return sources.stream()
                .filter(source -> Objects.equals(source.key(), key))
                .findFirst()
                .orElseThrow(() -> new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_SOURCE_NOT_FOUND));
    }

    public void validateOtaPackageUnique(List<OtaPackage> exists) {
        var filter = exists.stream().filter(it -> !Objects.equals(it.getId(), this.getId())).toList();
        if (!filter.isEmpty()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_ALREADY_EXISTS);
        }
    }

    public String resolveDuplicateRegistration(OtaPackage existing) {
        Map<String, String> existingSha = existing.artifactShaByKey();
        Map<String, String> incomingSha = artifactShaByKey();
        if (incomingSha.equals(existingSha)) {
            return String.valueOf(existing.getId());
        }
        String key = firstDifferingKey(existingSha, incomingSha);
        throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_ALREADY_EXISTS,
                "名称与版本未变但制品已变更（key=" + key
                        + "，库内SHA: " + shaHint(existingSha.get(key))
                        + "，传入SHA: " + shaHint(incomingSha.get(key)) + "），制品变更请更新版本号");
    }

    private static String firstDifferingKey(Map<String, String> existingSha, Map<String, String> incomingSha) {
        Set<String> keys = new TreeSet<>(existingSha.keySet());
        keys.addAll(incomingSha.keySet());
        return keys.stream()
                .filter(key -> !Objects.equals(existingSha.get(key), incomingSha.get(key)))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, String> artifactShaByKey() {
        return sources.stream()
                .collect(Collectors.toMap(OtaPackageSource::key, OtaPackageSource::sha256));
    }

    private static String shaHint(String sha256) {
        return sha256 == null ? "无" : sha256.substring(0, 8) + "…";
    }

    public long calculateTotalSize() {
        if (sources == null) {
            return 0L;
        }
        return sources.stream()
                .filter(source -> source != null && source.size() != null)
                .mapToLong(OtaPackageSource::size)
                .sum();
    }

    public void update(UpdateOtaPackageCmd cmd) {
        this.name = trim(cmd.getName());
        this.version = trim(cmd.getVersion());
        this.description = trimToNull(cmd.getDescription());
        this.setUpdatedAt(Instant.now());
    }

    private static List<OtaPackageSource> normalizeSources(List<OtaPackageSource> sources) {
        if (sources == null) {
            return null;
        }
        return sources.stream().map(source -> source == null ? null : source.normalized()).toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
