package com.agv.navdeployer.metadata.dto;

/** 坡道元信息（conn_type=ramp 时可选）。 */
public record ConnRampInfoDTO(Double slope, Double length, String surface) {
}
