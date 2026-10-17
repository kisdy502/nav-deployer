package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RMS 重定位指令（agv/relocate）的 payload：map 坐标系下的目标位姿。
 * 字段名与协议一致（x / y / theta），缺一不可，由 RmsMapService 校验。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelocateRequest(Double x, Double y, Double theta) {

    @JsonCreator
    public RelocateRequest {
    }

    public boolean incomplete() {
        return x == null || y == null || theta == null;
    }
}
