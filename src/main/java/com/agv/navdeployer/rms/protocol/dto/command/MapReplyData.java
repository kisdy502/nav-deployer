package com.agv.navdeployer.rms.protocol.dto.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RMS 地图类指令响应的 data 载荷集合（嵌套 record 按需取用）。
 * 字段名与云平台接口文档的 snake_case 线上格式一一对应。
 */
public final class MapReplyData {

    private MapReplyData() {
    }

    /** mapping/status：work_mode 0=定位模式 1=建图模式 2=扩图模式（当前无扩图）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkModeData(@JsonProperty("work_mode") int workMode) {
    }

    /** mapping/list：本体侧地图名列表。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapNamesData(@JsonProperty("map_names") List<String> mapNames) {
    }

    /** mapping/get_current：当前地图名。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapNameData(@JsonProperty("map_name") String mapName) {
    }

    /** mode/get：运行模式（仿真机器人固定 auto）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModeData(String mode) {
    }
}
