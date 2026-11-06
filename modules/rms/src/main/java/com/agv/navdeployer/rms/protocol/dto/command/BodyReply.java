package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BodyReply(
        int status,
        String msg,
        long timestamp,
        Object data
) {
    public static BodyReply success(String msg, Object data) {
        return new BodyReply(0, msg, Instant.now().toEpochMilli(), data);
    }

    public static BodyReply failure(int status, String msg) {
        return new BodyReply(status, msg, Instant.now().toEpochMilli(), Map.of());
    }

    public static BodyReply failure(int status, String msg, Object data) {
        return new BodyReply(status, msg, Instant.now().toEpochMilli(), data);
    }
}
