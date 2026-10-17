package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * rosbridge 消息回调。所有回调都运行在 WebSocket IO 线程上，实现方必须快速返回，
 * 耗时处理请自行转交业务线程池。
 */
public interface RosbridgeHandler {

    /** WebSocket 连接建立（含自动重连后的再次建立）。 */
    default void onConnected() {
    }

    /** 连接断开。自动重连由 RosbridgeClient 负责。 */
    default void onDisconnected(int statusCode, String reason) {
    }

    /** 收到一条完整的 rosbridge 协议消息（分片已重组），例如 {"op":"publish",...}。 */
    default void onMessage(JsonNode message) {
    }

    /** 传输层或解析异常。 */
    default void onError(Throwable error) {
    }
}
