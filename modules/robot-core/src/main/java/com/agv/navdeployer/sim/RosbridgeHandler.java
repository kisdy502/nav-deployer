package com.agv.navdeployer.sim;

import com.fasterxml.jackson.databind.JsonNode;

public interface RosbridgeHandler {

    default void onConnected() {
    }

    default void onDisconnected(int statusCode, String reason) {
    }

    default void onMessage(JsonNode message) {
    }

    default void onError(Throwable error) {
    }
}
