package com.agv.navdeployer.map.infrastructure.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapPackageFile(VersionInfoData versionInfo, MapInfoData mapInfo) {
}
