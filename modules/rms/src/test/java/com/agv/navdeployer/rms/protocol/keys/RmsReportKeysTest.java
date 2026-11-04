package com.agv.navdeployer.rms.protocol.keys;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RmsReportKeysTest {

    /** 任务结果上报 key 应拼成平铺形态（不含任务 ID 路径段），与云端订阅的 task/result_report 对齐。 */
    @Test
    void taskResultReportUsesFlatSegment() {
        RmsReportKeys keys = new RmsReportKeys("zioneer", "qc-robot", "RB-001");

        // 平铺 key（云端 taskflow 同时订阅 task/*/result_report 与 task/result_report 两种形态）
        assertThat(keys.taskResultReport("TASK-001"))
                .isEqualTo("zioneer/qc-robot/robot/RB-001/api/v1/task/result_report");
    }
}
