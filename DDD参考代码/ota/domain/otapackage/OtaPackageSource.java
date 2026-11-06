package com.zioneer.robotmanager.robot.ota.domain.otapackage;


import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Locale;

/**
 * 安装包的一份原始制品。
 *
 * <p>制品在存储平台内的位置只由 {@code path} 表达，以仓库名开头、不含主机地址——
 * 主机地址随环境变化，归配置管理，不随数据走。</p>
 */
@Schema(description = "OTA安装包数据来源")
public record OtaPackageSource(
        @Schema(description = "包内唯一标识；APT 类型时必须等于 dpkg 包名") String key,
        @Schema(description = "来源类型") OtaSourceType type,
        @Schema(description = "制品在仓库内的文件路径（Repository Path），以仓库名开头，不含主机地址") String path,
        @Schema(description = "制品版本") String version,
        @Schema(description = "制品字节内容的 SHA-256") String sha256,
        @Schema(description = "制品字节数") Long size
) {

    private static final int SHA256_HEX_LENGTH = 64;

    /**
     * 规范化：去除文本字段首尾空格，SHA-256 统一按小写保存。
     */
    public OtaPackageSource normalized() {
        return new OtaPackageSource(
                trim(key),
                type,
                trim(path),
                trim(version),
                sha256 == null ? null : sha256.trim().toLowerCase(Locale.ROOT),
                size
        );
    }

    public boolean checkSha256Valid() {
        if (sha256 == null) {
            return false;
        }
        if (sha256.length() != SHA256_HEX_LENGTH) {
            return false;
        }
        return sha256.chars().allMatch(OtaPackageSource::checkHexDigit);
    }

    public void requireDirectlyDownloadable() {
        if (type == null || !type.checkDirectlyDownloadable()) {
            throw new BusinessException(RobotManagementErrorCode.OTA_SOURCE_NOT_DOWNLOADABLE);
        }
    }

    public String filename() {
        int separatorIndex = path.lastIndexOf('/');
        return separatorIndex < 0 ? path : path.substring(separatorIndex + 1);
    }

    public boolean checkFieldsValid() {
        return key != null && !key.isBlank()
                && type != null
                && path != null && !path.isBlank()
                && checkVersionValid()
                && checkSha256Valid()
                && size != null && size > 0;
    }

    private boolean checkVersionValid() {
        return type != OtaSourceType.APT || (version != null && !version.isBlank());
    }

    private static boolean checkHexDigit(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
