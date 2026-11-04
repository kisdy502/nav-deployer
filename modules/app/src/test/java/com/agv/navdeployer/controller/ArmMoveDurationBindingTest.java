package com.agv.navdeployer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmMoveDurationBindingTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** 验证 durationSec 字段同时兼容 snake_case（duration_sec）与 camelCase（durationSec）两种客户端拼写，均可正确反序列化 */
    @Test
    void retainsDurationFromBothClientSpellings() throws Exception {
        for (String key : new String[]{"duration_sec", "durationSec"}) {
            var dto = mapper.readValue("{\"arm\":\"left\",\"joints\":[0,0,0,0,0,0],\"" + key + "\":1.2}",
                    RobotController.ArmMoveDTO.class);
            assertEquals(1.2, dto.durationSec());
        }
    }

    /** 验证请求体中省略 duration 字段时可正常绑定，durationSec 保持 null 即该参数为可选 */
    @Test
    void omittedDurationRemainsOptional() throws Exception {
        var dto = mapper.readValue("{\"arm\":\"left\",\"joints\":[0,0,0,0,0,0]}",
                RobotController.ArmMoveDTO.class);
        assertNull(dto.durationSec());
    }
}
