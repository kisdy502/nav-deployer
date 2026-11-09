package com.agv.navdeployer.task.domain.port;

import java.util.List;

/**
 * follow_edge 单段导航指令。
 */
public record FollowEdgeCommand(String commandId,
                                String sourceCode,
                                String targetCode,
                                double x,
                                double y,
                                double theta,
                                String edgeType,
                                Double maxSpeed,
                                double step,
                                boolean backUp,
                                boolean reverse,
                                boolean endPoint,
                                List<FollowControlPoint> controlPoints) {
}
