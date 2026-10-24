package com.agv.navdeployer.metadata.exchange.format;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 新格式地图包根节点（map-info.json）。
 *
 * <p>字段契约 camelCase（对齐《地图编辑json文件格式》）；序列化/解析必须使用
 * 默认命名策略的 ObjectMapper，不能用全局 SNAKE_CASE 配置的那个。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapPackageFile(VersionInfoData versionInfo, MapInfoData mapInfo) {
}
