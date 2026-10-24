package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 版本管理信息：version 语义化、status 仅已发布版本为 published、lastModified ISO 8601。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionInfoData(String version, String status, String lastModified, String modifiedBy) {
}
