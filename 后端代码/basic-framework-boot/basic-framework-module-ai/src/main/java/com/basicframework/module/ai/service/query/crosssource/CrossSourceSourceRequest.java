package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.module.ai.domain.query.CompiledQuery;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 跨源执行的来源取数请求（Y04）：一个来源"按主键粒度预聚合后拉取有界中间结果"的完整描述。
 *
 * <p>本类是跨源执行与单数据集查询之间唯一的接缝：来源侧复用 D06 的 {@link CompiledQuery}，
 * 但**执行期**按 Y03 已校验的聚合顺序逐源推进，每源只取自己的预聚合结果，绝不把多个来源
 * 拼成一条多表 SQL 去 join——那样"扇出即重复计算"就又回来了（Y03 §3.1）。
 *
 * <p>{@code entityKeys} 是该来源的版本化实体键集合：跨源关联只能通过它进行，
 * 且所有来源的 {@code mappingRevision} 必须一致（见 {@link CrossSourceEntityKey}）。
 * 显式列出实体键而不是靠"同名字段自动对齐"，是因为"自动对齐"在跨系统场景下
 * 正是同名不同实体被误合并的入口。
 */
public record CrossSourceSourceRequest(
        String role,
        String datasetCode,
        Integer datasetVersion,
        Long connectorId,
        CompiledQuery preAggregation,
        List<CrossSourceEntityKey> entityKeys,
        Long mappingRevision,
        String entityKeyColumn,
        String amountColumn,
        LocalDateTimeColumn sourceAsOfColumn,
        boolean optional) {

    /** 来源角色 / 逻辑列名：与 D05 的逻辑码同一模式。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    public CrossSourceSourceRequest {
        if (role == null || !CODE_PATTERN.matcher(role).matches()) {
            throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
        }
        if (datasetCode == null || datasetCode.isBlank()) {
            throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
        }
        if (datasetVersion == null || datasetVersion < 1) {
            throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
        }
        if (connectorId == null || connectorId < 1) {
            throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
        }
        if (preAggregation == null) {
            throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
        }
        if (entityKeyColumn == null || !CODE_PATTERN.matcher(entityKeyColumn).matches()) {
            throw AiCrossSourceExecutionErrors.entityKeyMissing();
        }
        if (amountColumn == null || !CODE_PATTERN.matcher(amountColumn).matches()) {
            throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
        }
        entityKeys = entityKeys == null ? List.of() : List.copyOf(entityKeys);
        if (entityKeys.isEmpty()) {
            // 没有任何实体键就没有可关联的事实：放行会得到一个"空但看起来完整"的跨源结果
            throw AiCrossSourceExecutionErrors.entityKeyMissing();
        }
        // 所有键必须钉在同一映射版本上：跨版本合并会把两份事实并成一份
        CrossSourceEntityKey.requireSingleMappingRevision(entityKeys);
        if (mappingRevision == null
                || !mappingRevision.equals(CrossSourceEntityKey.requireSingleMappingRevision(entityKeys))) {
            throw AiCrossSourceExecutionErrors.entityKeyRevisionConflict();
        }
    }

    /**
     * 来源数据时间列（{@code MAX(<column>)} 的结果列名）。
     *
     * <p>用独立类型而不是裸 String：来源数据时间是跨源合计能不能出具的**前提**，
     * 让它在类型上不可省略，比在执行期"如果为 null 就当现在"要安全得多。
     */
    public record LocalDateTimeColumn(String code) {

        public LocalDateTimeColumn {
            if (code == null || !CODE_PATTERN.matcher(code).matches()) {
                throw AiCrossSourceExecutionErrors.planSourceNotDeclared();
            }
        }
    }

    /** 实体键数量（预算与扇出观测用）。 */
    public int entityKeyCount() {
        return entityKeys.size();
    }

    /** 请求摘要（不含 SQL 文本与数据值）。 */
    public String describe() {
        return String.format(
                Locale.ROOT,
                "role=%s, dataset=%s#%s, connector=%s, keys=%d, revision=%s, optional=%s",
                role,
                datasetCode,
                datasetVersion,
                connectorId,
                entityKeys.size(),
                mappingRevision,
                optional);
    }
}
