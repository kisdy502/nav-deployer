package com.agv.navdeployer.map.application.cmd;

import jakarta.validation.constraints.Size;

public record MapPublishCmd(@Size(max = 64) String modifiedBy) {
}
