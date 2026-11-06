package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenericPayload(
        String templateCode,
        String templateId,
        String templateFileUrl,
        String templateDescription,
        Map<String, Object> parameters
) {
    @JsonCreator
    public GenericPayload {
        parameters = parameters == null ? new LinkedHashMap<>() : parameters;
    }

    @JsonAnySetter
    public void putParameter(String key, Object value) {
        parameters.put(key, value);
    }

    public String parameterText(String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
