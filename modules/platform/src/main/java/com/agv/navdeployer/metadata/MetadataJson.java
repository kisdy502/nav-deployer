package com.agv.navdeployer.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class MetadataJson {

    // 地图载荷字段为 camelCase：此 mapper 独立于全局 SNAKE_CASE 策略，勿复用全局实例
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataJson() {
    }
}
