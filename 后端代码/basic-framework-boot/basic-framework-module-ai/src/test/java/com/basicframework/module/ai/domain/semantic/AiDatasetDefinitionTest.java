package com.basicframework.module.ai.domain.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D04 语义定义：白名单键、权限策略必填、别名歧义、引用完整性与 schemaHash 稳定性。 */
class AiDatasetDefinitionTest {

    /** 一份合规定义：三个字段（含枚举）、一个指标、一个维度、时间语义与粒度。 */
    static final String VALID =
            """
            {
              "grain": "一行一单",
              "time": {"field": "created_at", "granularity": "DAY", "timezone": "Asia/Shanghai"},
              "fields": [
                {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "unit": "COUNT",
                 "aliases": ["订单号"], "visibility": "PUBLIC"},
                {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "unit": "CURRENCY",
                 "visibility": "INTERNAL"},
                {"name": "created_at", "sourceColumn": "created_at", "type": "DATETIME",
                 "visibility": "INTERNAL"},
                {"name": "status", "sourceColumn": "status", "type": "ENUM", "enumValues": ["PAID", "REFUNDED"],
                 "visibility": "RESTRICTED", "permission": "ai:dataset:field:status"}
              ],
              "metrics": [
                {"name": "total_amount", "field": "amount", "aggregation": "SUM", "unit": "CURRENCY",
                 "visibility": "INTERNAL"}
              ],
              "dimensions": [{"name": "order_status", "field": "status"}]
            }
            """;

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static void assertInvalid(String json) {
        assertThatThrownBy(() -> AiDatasetDefinition.parse(json))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DEFINITION_INVALID));
    }

    @Test
    void acceptsWellFormedDefinitionAndExposesSemantics() {
        AiDatasetDefinition definition = AiDatasetDefinition.parse(VALID);

        assertThat(definition.grain()).isEqualTo("一行一单");
        assertThat(definition.fields())
                .extracting(AiDatasetDefinition.Field::name)
                .containsExactly("order_id", "amount", "created_at", "status");
        assertThat(definition.metrics())
                .extracting(AiDatasetDefinition.Metric::aggregation)
                .containsExactly("SUM");
        assertThat(definition.dimensions())
                .extracting(AiDatasetDefinition.Dimension::field)
                .containsExactly("status");
        assertThat(definition.time())
                .isEqualTo(new AiDatasetDefinition.TimeSemantics("created_at", "DAY", "Asia/Shanghai"));
        assertThat(definition.field("status").enumValues()).containsExactly("PAID", "REFUNDED");
        assertThat(definition.field("order_id").aliases()).containsExactly("订单号");
        assertThat(definition.sourceColumns()).containsExactly("id", "amount", "created_at", "status");
        assertThat(definition.field("missing")).isNull();
    }

    @Test
    void schemaHashIsStableAndContentSensitive() {
        AiDatasetDefinition first = AiDatasetDefinition.parse(VALID);
        // 同一份语义、不同键序与空白：规范化后必须得到同一个哈希
        AiDatasetDefinition reordered = AiDatasetDefinition.parse(
                VALID.replace("\"grain\": \"一行一单\",", "\"grain\":\"一行一单\",").replace("\n", " "));

        assertThat(reordered.schemaHash()).isEqualTo(first.schemaHash());
        assertThat(first.schemaHash()).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(first.canonicalJson()).isEqualTo(reordered.canonicalJson());

        AiDatasetDefinition changed = AiDatasetDefinition.parse(VALID.replace("\"INTERNAL\"", "\"PUBLIC\""));
        assertThat(changed.schemaHash()).as("权限策略变化必须改变哈希").isNotEqualTo(first.schemaHash());
    }

    @Test
    void canonicalJsonRoundTripsThroughParse() {
        AiDatasetDefinition first = AiDatasetDefinition.parse(VALID);
        String canonical = first.canonicalJson();
        AiDatasetDefinition again = AiDatasetDefinition.parse(canonical);

        assertThat(again.schemaHash()).as("规范化结果必须可再次解析：%s", canonical).isEqualTo(first.schemaHash());
        assertThat(again.canonicalJson()).isEqualTo(canonical);
    }

    @Test
    void rejectsMissingPermissionPolicyAndUnknownKeys() {
        // 缺 visibility
        assertInvalid(VALID.replace("\"visibility\": \"INTERNAL\"", "\"visibility\": \"SECRET\""));
        // RESTRICTED 但没有权限码
        assertInvalid(VALID.replace("\"permission\": \"ai:dataset:field:status\"", "\"permission\": \"\""));
        // 未知键
        assertInvalid(VALID.replace("\"grain\": \"一行一单\",", "\"grain\": \"一行一单\", \"script\": \"drop\","));
        // 未知字段键
        assertInvalid(VALID.replace("\"unit\": \"CURRENCY\",", "\"unit\": \"CURRENCY\", \"masking\": \"none\","));
        // 枚举类型缺取值
        assertInvalid(VALID.replace("\"enumValues\": [\"PAID\", \"REFUNDED\"],", ""));
        // 非枚举类型却给了取值
        assertInvalid(VALID.replace(
                "\"type\": \"DECIMAL\", \"unit\": \"CURRENCY\"",
                "\"type\": \"DECIMAL\", \"unit\": \"CURRENCY\", \"enumValues\": [\"A\"]"));
    }

    @Test
    void rejectsBrokenReferencesAndBadShapes() {
        // 时间字段不是日期/时间类型
        assertInvalid(
                VALID.replace("\"field\": \"created_at\", \"granularity\"", "\"field\": \"amount\", \"granularity\""));
        // 时间粒度不在白名单
        assertInvalid(VALID.replace("\"granularity\": \"DAY\"", "\"granularity\": \"MINUTE\""));
        // 指标引用不存在的字段
        assertInvalid(
                VALID.replace("\"field\": \"amount\", \"aggregation\"", "\"field\": \"missing\", \"aggregation\""));
        // SUM 作用于非数值字段
        assertInvalid(VALID.replace(
                "\"field\": \"amount\", \"aggregation\": \"SUM\"", "\"field\": \"status\", \"aggregation\": \"SUM\""));
        // 维度引用不存在的字段
        assertInvalid(VALID.replace(
                "\"dimensions\": [{\"name\": \"order_status\", \"field\": \"status\"}]",
                "\"dimensions\": [{\"name\": \"order_status\", \"field\": \"nope\"}]"));
        // 字段与指标重名（同一命名空间）
        assertInvalid(VALID.replace("\"name\": \"total_amount\"", "\"name\": \"amount\""));
        // 来源列名非法
        assertInvalid(VALID.replace("\"sourceColumn\": \"id\"", "\"sourceColumn\": \"id;drop\""));
        // 逻辑名非法（大写）
        assertInvalid(VALID.replace("\"name\": \"order_id\"", "\"name\": \"Order_id\""));
        // 空字段列表
        assertInvalid(
                VALID.replaceAll("\"fields\": \\[[\\s\\S]*?\\],\\n  \"metrics\"", "\"fields\": [],\n  \"metrics\""));
        // 非对象
        assertInvalid("[1,2]");
        assertInvalid("");
        assertThatThrownBy(() -> AiDatasetDefinition.parse(null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DEFINITION_INVALID));
    }

    @Test
    void rejectsAmbiguousAliases() {
        // 两个字段共用同一别名（大小写不敏感）
        assertAmbiguous(
                fields(
                        """
                {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "aliases": ["订单号"],
                 "visibility": "PUBLIC"},
                {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "aliases": ["订单号"],
                 "visibility": "PUBLIC"}
                """));
        // 别名与另一字段的逻辑名冲突
        assertAmbiguous(
                fields(
                        """
                {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "aliases": ["amount"],
                 "visibility": "PUBLIC"},
                {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "visibility": "PUBLIC"}
                """));
        // 别名与指标名冲突
        assertAmbiguous("{\"grain\": \"g\", \"fields\": ["
                + "{\"name\": \"order_id\", \"sourceColumn\": \"id\", \"type\": \"NUMBER\","
                + " \"aliases\": [\"total_amount\"], \"visibility\": \"PUBLIC\"},"
                + "{\"name\": \"amount\", \"sourceColumn\": \"amount\", \"type\": \"DECIMAL\","
                + " \"visibility\": \"PUBLIC\"}],"
                + "\"metrics\": [{\"name\": \"total_amount\", \"field\": \"amount\","
                + " \"aggregation\": \"SUM\", \"visibility\": \"PUBLIC\"}]}");
        // 别名与维度名冲突
        assertAmbiguous("{\"grain\": \"g\", \"fields\": ["
                + "{\"name\": \"order_id\", \"sourceColumn\": \"id\", \"type\": \"NUMBER\","
                + " \"aliases\": [\"order_status\"], \"visibility\": \"PUBLIC\"},"
                + "{\"name\": \"status\", \"sourceColumn\": \"status\", \"type\": \"STRING\","
                + " \"visibility\": \"PUBLIC\"}],"
                + "\"dimensions\": [{\"name\": \"order_status\", \"field\": \"status\"}]}");

        // 无别名不产生歧义
        assertThatCode(
                        () -> AiDatasetDefinition.parse(
                                fields(
                                        """
                {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "visibility": "PUBLIC"},
                {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "visibility": "PUBLIC"}
                """)))
                .doesNotThrowAnyException();
    }

    /** 拼一个只有字段的定义（便于逐条构造非法组合）。 */
    private static String fields(String fieldJson) {
        return "{\"grain\": \"g\", \"fields\": [" + fieldJson + "]}";
    }

    private static void assertAmbiguous(String json) {
        assertThatThrownBy(() -> AiDatasetDefinition.parse(json))
                .as("别名歧义必须被识别")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_ALIAS_AMBIGUOUS));
    }

    @Test
    void enforcesLimitsAndPermissionPolicyHelper() {
        assertThat(AiDatasetDefinition.hasPermissionPolicy("PUBLIC", null)).isTrue();
        assertThat(AiDatasetDefinition.hasPermissionPolicy("INTERNAL", null)).isTrue();
        assertThat(AiDatasetDefinition.hasPermissionPolicy("RESTRICTED", "ai:dataset:field:x"))
                .isTrue();
        assertThat(AiDatasetDefinition.hasPermissionPolicy("RESTRICTED", null)).isFalse();
        assertThat(AiDatasetDefinition.hasPermissionPolicy("RESTRICTED", "UPPER"))
                .isFalse();
        assertThat(AiDatasetDefinition.hasPermissionPolicy("SECRET", "ai:x")).isFalse();

        StringBuilder tooMany = new StringBuilder("[");
        for (int index = 0; index < AiDatasetDefinition.MAX_FIELDS + 1; index++) {
            tooMany.append(index == 0 ? "" : ",")
                    .append("{\"name\": \"f")
                    .append(index)
                    .append("\", \"sourceColumn\": \"c")
                    .append(index)
                    .append("\", \"type\": \"STRING\", \"visibility\": \"PUBLIC\"}");
        }
        String oversized = "{\"grain\": \"g\", \"fields\": " + tooMany + "}";
        assertInvalid(oversized);

        // 超长定义（> 7000 字符）直接拒绝
        assertInvalid("{\"grain\": \"" + "x".repeat(7_001) + "\", \"fields\": []}");
    }

    @Test
    void driftReportSeparatesMissingTypeChangedAndAddedColumns() {
        AiDatasetDriftReport clean = new AiDatasetDriftReport(List.of(), List.of(), List.of(), "a", "b");
        assertThat(clean.isPublishable()).isTrue();
        assertThat(clean.hasDrift()).isFalse();
        assertThat(clean.summary()).isEqualTo("missing=;typeChanged=;added=");

        AiDatasetDriftReport addedOnly = new AiDatasetDriftReport(List.of(), List.of(), List.of("extra"), "a", "b");
        assertThat(addedOnly.isPublishable()).as("仅上游新增列不影响可执行范围").isTrue();
        assertThat(addedOnly.hasDrift()).isTrue();

        AiDatasetDriftReport missing = new AiDatasetDriftReport(List.of("gone"), List.of(), List.of(), "a", "b");
        assertThat(missing.isPublishable()).isFalse();
        assertThat(missing.summary()).isEqualTo("missing=gone;typeChanged=;added=");
    }

    @Test
    void semanticTypesMapOnlyKnownUpstreamTypes() {
        assertThat(AiSemanticTypes.isCompatible("NUMBER", "bigint")).isTrue();
        assertThat(AiSemanticTypes.isCompatible("number", "INT")).isTrue();
        assertThat(AiSemanticTypes.isCompatible("DECIMAL", "decimal")).isTrue();
        assertThat(AiSemanticTypes.isCompatible("STRING", "varchar")).isTrue();
        assertThat(AiSemanticTypes.isCompatible("DATETIME", "timestamp")).isTrue();
        assertThat(AiSemanticTypes.isCompatible("ENUM", "enum")).isTrue();
        assertThat(AiSemanticTypes.isCompatible("NUMBER", "varchar")).isFalse();
        assertThat(AiSemanticTypes.isCompatible("STRING", "geometry")).isFalse();
        assertThat(AiSemanticTypes.isCompatible("UNKNOWN", "varchar")).isFalse();
        assertThat(AiSemanticTypes.isCompatible(null, "varchar")).isFalse();
    }
}
