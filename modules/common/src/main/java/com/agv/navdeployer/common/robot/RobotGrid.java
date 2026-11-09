package com.agv.navdeployer.common.robot;

import java.time.Instant;

/**
 * 机器人侧 OccupancyGrid 栅格快照（跨 BC 共享数据契约：task 端口与 map 入库共用）。
 * data 行优先，第 0 行在世界 y 最下方；-1 未知 / 0 空闲 / 1~100 占据。
 */
public record RobotGrid(String frameId,
                        double resolution,
                        int width,
                        int height,
                        double originX,
                        double originY,
                        double originYaw,
                        int[] data,
                        Instant receivedAt) {
}
