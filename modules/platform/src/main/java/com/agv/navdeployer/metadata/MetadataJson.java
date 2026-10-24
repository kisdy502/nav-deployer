package com.agv.navdeployer.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 元数据 JSON 载荷专用 mapper（vertices/rampInfo/edges/controlPoints/connections 等
 * 存库 JSON 字段）。载荷形状与 map-info.json 一致（camelCase），
 * 必须与全局 SNAKE_CASE 的 Web mapper 区分开。
 */
public final class MetadataJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataJson() {
    }
}
