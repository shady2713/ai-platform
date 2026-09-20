package com.basicframework.module.ai.service.query.compiler;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED;

import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ResolvedDatasetVersion;
import com.basicframework.module.ai.domain.query.SqlParameter;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 查询计划 → 参数化 SQL 编译器（D06）：纯确定性，不调用模型、不访问网络。
 *
 * <p>编译期的四条硬约束：
 * <ol>
 *   <li><b>标识符只来自审核目录</b>：表/列名逐个对照数据集定义（物理列白名单）与标识符形状，
 *       任何来源不明的标识符直接拒绝——模型或调用方给不出新标识符；</li>
 *   <li><b>值全部绑定</b>：WHERE/LIMIT 的取值一律 {@code ?}，SQL 文本里不出现数据值，
 *       因此注入 payload 无法进入语句结构；</li>
 *   <li><b>行范围强制 AND</b>：授权域条件先拼进 WHERE，用户/模型的过滤条件只能追加，
 *       且空授权域直接拒绝（403）——不存在"没有行约束就查全库"的路径；</li>
 *   <li><b>结构固定</b>：单来源（审核对象或审核视图）、无 JOIN、无子查询拼接、无函数调用，
 *       聚合只允许 SUM/COUNT/COUNT DISTINCT/MIN/MAX/AVG；NULL 语义按 SQL 原生语义处理，
 *       不全局 COALESCE（是否转 0 属于指标定义，D06 不擅自决定）。</li>
 * </ol>
 *
 * <p>排序稳定性：显式排序键之后追加剩余维度（升序）作为并列时的稳定次序，
 * 保证分页在同一数据快照下可复现。
 */
@Component
public class QueryPlanSqlCompiler {

    /** 允许的聚合（与数据集定义的聚合白名单一致；这里是编译期二次校验）。 */
    private static final Set<String> AGGREGATIONS = Set.of("SUM", "AVG", "COUNT", "COUNT_DISTINCT", "MIN", "MAX");

    /** 标识符：只允许字面量标识符字符，杜绝反引号/点号/空格等注入面。 */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    /** 来源对象：schema.table。 */
    private static final Pattern SOURCE_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}\\.[A-Za-z0-9_]{1,64}$");

    /** 行数预算上限（与 D03 执行器一致；LIMIT 参数在此范围内）。 */
    private static final int MAX_ROWS = 1_000;

    /** 语句超时（毫秒）：编译结果自带预算，执行器不得放大。 */
    private static final int TIMEOUT_MILLIS = 10_000;

    /** 编译查询计划（scope 为授权层给出的行范围；为空一律拒绝）。 */
    public CompiledQuery compile(ValidatedQueryPlan plan, ResolvedDatasetVersion dataset, QueryScope scope) {
        if (plan == null || dataset == null || scope == null || !scope.isEffective()) {
            throw exception(AI_QUERY_SCOPE_REQUIRED);
        }
        if (!dataset.datasetId().equals(plan.datasetId())
                || !dataset.datasetVersionId().equals(plan.datasetVersionId())) {
            // 计划与数据集版本必须一一对应：错配意味着"用旧目录编译新计划"
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        String sourceObject = requireSource(dataset.sourceObject());
        Set<String> allowedColumns = allowedColumns(dataset.definition());

        List<SqlParameter> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");

        // 1) 维度与指标（指标聚合白名单 + 物理列白名单）
        List<String> selectItems = new ArrayList<>();
        List<CompiledQuery.ResultColumn> resultColumns = new ArrayList<>();
        for (ValidatedQueryPlan.Dimension dimension : plan.dimensions()) {
            String column = requireColumn(dimension.sourceColumn(), allowedColumns);
            // 维度也起别名：ORDER BY 只用逻辑码，避免"排序键引用了未出现在结果里的列"
            selectItems.add(column + " AS " + dimension.code());
            resultColumns.add(new CompiledQuery.ResultColumn(dimension.code(), dimension.code(), "STRING"));
        }
        for (ValidatedQueryPlan.Metric metric : plan.metrics()) {
            String column = requireColumn(metric.sourceColumn(), allowedColumns);
            String aggregation =
                    metric.aggregation() == null ? null : metric.aggregation().toUpperCase(Locale.ROOT);
            if (aggregation == null || !AGGREGATIONS.contains(aggregation)) {
                throw exception(AI_QUERY_COMPILE_FAILED);
            }
            String expression = "COUNT_DISTINCT".equals(aggregation)
                    ? "COUNT(DISTINCT " + column + ")"
                    : aggregation + "(" + column + ")";
            selectItems.add(expression + " AS " + metric.code());
            resultColumns.add(new CompiledQuery.ResultColumn(metric.code(), metric.code(), "DECIMAL"));
        }
        if (selectItems.isEmpty()) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        sql.append(String.join(", ", selectItems)).append(" FROM ").append(sourceObject);

        // 2) WHERE：授权域先行，用户过滤与时间窗口只能追加（AND）
        List<String> predicates = new ArrayList<>();
        for (QueryScope.Condition condition : scope.conditions()) {
            String column = requireColumn(condition.sourceColumn(), allowedColumns);
            predicates.add(predicate(column, condition.operator(), condition.values(), parameters));
        }
        for (ValidatedQueryPlan.Filter filter : plan.filters()) {
            String column = requireColumn(filter.sourceColumn(), allowedColumns);
            predicates.add(predicate(column, filter.operator(), filter.values(), parameters));
        }
        if (plan.timeWindow() != null) {
            String column = requireColumn(plan.timeWindow().sourceColumn(), allowedColumns);
            ZoneId zone = ZoneId.of(plan.timeWindow().timezone());
            predicates.add(column + " >= ?");
            parameters.add(timestampParameter(plan.timeWindow().startInclusive(), zone));
            predicates.add(column + " < ?");
            parameters.add(timestampParameter(plan.timeWindow().endExclusive(), zone));
        }
        sql.append(" WHERE ").append(String.join(" AND ", predicates));

        // 3) GROUP BY（有维度即分组；无维度表示整体聚合）
        if (!plan.dimensions().isEmpty()) {
            List<String> groupColumns = new ArrayList<>();
            for (ValidatedQueryPlan.Dimension dimension : plan.dimensions()) {
                groupColumns.add(requireColumn(dimension.sourceColumn(), allowedColumns));
            }
            sql.append(" GROUP BY ").append(String.join(", ", groupColumns));
        }

        // 4) ORDER BY：显式排序键 + 剩余维度作为稳定次序（分页可复现）
        List<String> orderItems = new ArrayList<>();
        Set<String> ordered = new LinkedHashSet<>();
        for (ValidatedQueryPlan.Order order : plan.orderBy()) {
            orderItems.add(orderExpression(order, plan, allowedColumns));
            ordered.add(order.code());
        }
        for (ValidatedQueryPlan.Dimension dimension : plan.dimensions()) {
            if (ordered.add(dimension.code())) {
                orderItems.add(dimension.code() + " ASC");
            }
        }
        if (orderItems.isEmpty() && !plan.metrics().isEmpty()) {
            orderItems.add(plan.metrics().get(0).code() + " ASC");
        }
        if (!orderItems.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderItems));
        }

        // 5) LIMIT 绑定参数（行数预算不超过执行器上限）
        int maxRows = Math.min(plan.limit(), MAX_ROWS);
        sql.append(" LIMIT ?");
        parameters.add(SqlParameter.number(maxRows));

        return new CompiledQuery(
                sql.toString(),
                parameters,
                resultColumns,
                maxRows,
                TIMEOUT_MILLIS,
                dataset.datasetVersionId(),
                plan.planHash(),
                sourceObject);
    }

    /** 排序表达式：只允许本次结果里已选中的指标或维度（与计划校验一致，编译期再确认）。 */
    private static String orderExpression(
            ValidatedQueryPlan.Order order, ValidatedQueryPlan plan, Set<String> allowedColumns) {
        String direction = order.direction() == null ? null : order.direction().toUpperCase(Locale.ROOT);
        if (!"ASC".equals(direction) && !"DESC".equals(direction)) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        boolean isDimension = plan.dimensions().stream().anyMatch(d -> d.code().equals(order.code()));
        if (isDimension) {
            return order.code() + " " + direction;
        }
        boolean isMetric = plan.metrics().stream().anyMatch(m -> m.code().equals(order.code()));
        if (!isMetric) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        return order.code() + " " + direction;
    }

    /** 单条条件 → 谓词片段（值全部走绑定参数）。 */
    private static String predicate(
            String column, String operator, List<Object> values, List<SqlParameter> parameters) {
        String normalized = operator == null ? null : operator.toUpperCase(Locale.ROOT);
        if (normalized == null) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        switch (normalized) {
            case "EQ", "NE", "GT", "GE", "LT", "LE" -> {
                requireSingleValue(values);
                parameters.add(parameter(values.get(0)));
                return column + " " + comparisonSymbol(normalized) + " ?";
            }
            case "IN" -> {
                if (values.isEmpty() || values.size() > 100) {
                    throw exception(AI_QUERY_COMPILE_FAILED);
                }
                values.forEach(value -> parameters.add(parameter(value)));
                return column + " IN (" + placeholders(values.size()) + ")";
            }
            case "BETWEEN" -> {
                if (values.size() != 2) {
                    throw exception(AI_QUERY_COMPILE_FAILED);
                }
                return betweenPredicate(column, values, parameters);
            }
            case "IS_NULL" -> {
                if (!values.isEmpty()) {
                    throw exception(AI_QUERY_COMPILE_FAILED);
                }
                return column + " IS NULL";
            }
            default -> throw exception(AI_QUERY_COMPILE_FAILED);
        }
    }

    /** BETWEEN 由计划校验器归一为两个边界值：这里按 [lower, upper] 展开为两个绑定参数。 */
    private static String betweenPredicate(String column, List<Object> values, List<SqlParameter> parameters) {
        parameters.add(parameter(values.get(0)));
        parameters.add(parameter(values.get(1)));
        return column + " BETWEEN ? AND ?";
    }

    private static String comparisonSymbol(String operator) {
        return switch (operator) {
            case "EQ" -> "=";
            case "NE" -> "<>";
            case "GT" -> ">";
            case "GE" -> ">=";
            case "LT" -> "<";
            default -> "<=";
        };
    }

    private static void requireSingleValue(List<Object> values) {
        if (values.size() != 1) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /** 取值 → 绑定参数（类型来自值的 Java 类型；数值保持十进制精度）。 */
    private static SqlParameter parameter(Object value) {
        if (value == null) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        if (value instanceof Boolean flag) {
            return new SqlParameter(flag, SqlParameter.Type.BOOLEAN, SqlParameter.Sensitivity.INTERNAL);
        }
        if (value instanceof BigDecimal decimal) {
            return SqlParameter.decimal(decimal);
        }
        if (value instanceof Double || value instanceof Float) {
            return SqlParameter.decimal(BigDecimal.valueOf(((Number) value).doubleValue()));
        }
        if (value instanceof Number number) {
            return SqlParameter.number(number.longValue());
        }
        return SqlParameter.string(String.valueOf(value));
    }

    /** 时间窗口：按数据集声明的时区换算为本地时间戳（列是 DATETIME，无时区）。 */
    private static SqlParameter timestampParameter(String isoValue, ZoneId zone) {
        try {
            LocalDateTime local =
                    OffsetDateTime.parse(isoValue).atZoneSameInstant(zone).toLocalDateTime();
            return new SqlParameter(local, SqlParameter.Type.TIMESTAMP, SqlParameter.Sensitivity.INTERNAL);
        } catch (RuntimeException invalid) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
    }

    private static String requireSource(String sourceObject) {
        if (sourceObject == null || !SOURCE_PATTERN.matcher(sourceObject).matches()) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        return sourceObject;
    }

    /** 物理列白名单：数据集定义里声明的列（去重）。 */
    private static Set<String> allowedColumns(AiDatasetDefinition definition) {
        Set<String> columns = new LinkedHashSet<>();
        definition.fields().forEach(field -> columns.add(field.sourceColumn()));
        return columns;
    }

    private static String requireColumn(String column, Set<String> allowedColumns) {
        if (column == null || !IDENTIFIER_PATTERN.matcher(column).matches() || !allowedColumns.contains(column)) {
            // 不在审核目录里的列名一律拒绝（模型/调用方无法引入新标识符）
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        return column;
    }
}
