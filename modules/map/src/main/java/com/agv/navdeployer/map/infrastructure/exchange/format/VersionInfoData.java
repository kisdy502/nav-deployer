package com.agv.navdeployer.map.infrastructure.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionInfoData(String version, String status, String lastModified, String modifiedBy) {
}
