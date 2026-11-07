package com.agv.navdeployer.rms.infrastructure.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelocateRequest(Double x, Double y, Double theta) {

    @JsonCreator
    public RelocateRequest {
    }

    public boolean incomplete() {
        return x == null || y == null || theta == null;
    }
}
