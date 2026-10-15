package com.agv.navdeployer.rms.protocol.keys;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RmsReportKeysTest {

    @Test
    void taskResultReportUsesFlatSegment() {
        RmsReportKeys keys = new RmsReportKeys("zioneer", "qc-robot", "RB-001");

        // 平铺 key（云端 taskflow 同时订阅 task/*/result_report 与 task/result_report 两种形态）
        assertThat(keys.taskResultReport("TASK-001"))
                .isEqualTo("zioneer/qc-robot/robot/RB-001/api/v1/task/result_report");
    }
}
