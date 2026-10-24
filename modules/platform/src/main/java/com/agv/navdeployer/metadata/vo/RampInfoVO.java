package com.agv.navdeployer.metadata.vo;

/** 坡道信息视图（区域约束的 rampInfo）。 */
public record RampInfoVO(Double slope, String direction, Double startElevation, Double endElevation) {
}
