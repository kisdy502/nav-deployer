package com.agv.navdeployer.rms.protocol.keys;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RmsReportKeysTest {

    @Test
    void taskResultReportUsesRealTaskIdAsPathSegment() {
        RmsReportKeys keys = new RmsReportKeys("zioneer", "qc-robot", "RB-001");

        assertThat(keys.taskResultReport("TASK-001"))
                .isEqualTo("zioneer/qc-robot/robot/RB-001/api/v1/task/TASK-001/result_report");
    }
}
