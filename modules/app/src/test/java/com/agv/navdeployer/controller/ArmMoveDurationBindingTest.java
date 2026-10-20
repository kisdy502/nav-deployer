package com.agv.navdeployer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmMoveDurationBindingTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void retainsDurationFromBothClientSpellings() throws Exception {
        for (String key : new String[]{"duration_sec", "durationSec"}) {
            var dto = mapper.readValue("{\"arm\":\"left\",\"joints\":[0,0,0,0,0,0],\"" + key + "\":1.2}",
                    RobotController.ArmMoveDTO.class);
            assertEquals(1.2, dto.durationSec());
        }
    }

    @Test
    void omittedDurationRemainsOptional() throws Exception {
        var dto = mapper.readValue("{\"arm\":\"left\",\"joints\":[0,0,0,0,0,0]}",
                RobotController.ArmMoveDTO.class);
        assertNull(dto.durationSec());
    }
}
