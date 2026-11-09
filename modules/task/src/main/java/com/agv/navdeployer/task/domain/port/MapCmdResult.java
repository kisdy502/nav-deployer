package com.agv.navdeployer.task.domain.port;

/**
 * 机器人地图/建图类指令的受理结果（load_map / start_mapping / save_map / cancel_mapping / relocalize 共用）。
 */
public record MapCmdResult(boolean success, String message, String mapName, String operationId) {
}
