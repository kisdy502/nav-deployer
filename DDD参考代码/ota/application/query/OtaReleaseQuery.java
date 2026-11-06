package com.zioneer.robotmanager.robot.ota.application.query;

import com.zioneer.robotmanager.common.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class OtaReleaseQuery extends PageQuery {

    /**
     * 排序字段白名单：前端传入名 → 实际数据库列名，防止 ORDER BY 拼接注入。
     */
    private static final Map<String, String> ORDER_BY_MAPPING = Map.of(
            "name", "name",
            "created_at", "created_at");
    private static final String DEFAULT_ORDER_COLUMN = "created_at";

    @Schema(description = "关键词：模糊匹配发布名称与安装包名称")
    private String keyword;

    @Schema(description = "目标值筛选：型号编号或机器人 ID，匹配发布所选的目标值")
    private String targetValue;

    @Schema(description = "排序字段：name / created_at，默认 created_at")
    private String orderBy;

    @Schema(description = "排序方向：ASC / DESC，默认 DESC")
    private String sortDirection;

    @Schema(hidden = true)
    private String orderByColumn;

    public void normalizeOrderBy() {
        // Map.of 不接受 null key，未传 orderBy 时直接走默认列
        this.orderByColumn = orderBy == null
                ? DEFAULT_ORDER_COLUMN
                : ORDER_BY_MAPPING.getOrDefault(orderBy.trim(), DEFAULT_ORDER_COLUMN);
        this.sortDirection = "ASC".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
    }
}
