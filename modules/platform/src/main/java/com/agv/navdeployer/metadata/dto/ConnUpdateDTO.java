package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/** 更新楼层间连接（字段全部可选；connections 非空时整体替换）。 */
public record ConnUpdateDTO(
        @Size(max = 128) String name,
        @Size(max = 16) String connType,
        List<ConnEndpointDTO> connections,
        Double capacity,
        Double maxSpeed,
        ConnRampInfoDTO rampInfo,
        @Size(max = 64) String modifiedBy) {
}
