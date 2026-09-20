package com.basicframework.module.ai.adapter.connector.mysql;

import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 只读查询结果（D03）：列名 + 行（按列名取值）+ 截断标记。
 *
 * <p>行数据不进 {@code toString()}（避免把上游数据带进日志）；单值长度在读取时已按上限裁剪。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"rows"})
public class AiMysqlQueryResultDTO {

    /** 查询句柄（可用于取消；查询结束后句柄失效）。 */
    private String handleId;

    /** 列名（按结果集顺序）。 */
    private List<String> columns;

    /** 行数据（每行按列名取值；值已裁剪到单值长度上限）。 */
    private List<Map<String, Object>> rows;

    /** 返回行数（不含被截断的额外行）。 */
    private int rowCount;

    /** 是否被截断（还有更多行未返回）。 */
    private boolean truncated;

    /** 耗时（毫秒）。 */
    private long elapsedMillis;
}
