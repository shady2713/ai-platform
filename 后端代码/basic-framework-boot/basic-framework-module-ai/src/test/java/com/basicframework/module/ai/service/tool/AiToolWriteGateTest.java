package com.basicframework.module.ai.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * X06 写工具准入闸门：发布期要求"政策非 AUTO + 声明合法 + 绑定真的能用"，
 * 执行期要求冻结绑定与当前已发布版本一致（绑定变化必须重新确认），核对期要求查询仍按业务键过滤。
 */
class AiToolWriteGateTest {

    private static final Long CONNECTOR_ID = 71L;

    private static final String INPUT_SCHEMA =
            """
            {"payment_no": {"type": "string", "required": true},
             "amount": {"type": "number", "required": true}}
            """;

    private static final String WRITE_OPERATION_PARAMETERS =
            "{\"payment_no\": {\"in\": \"body\", \"required\": true, \"type\": \"string\"},"
                    + " \"amount\": {\"in\": \"body\", \"required\": true, \"type\": \"number\"}}";

    private static final String RECONCILE_OPERATION_PARAMETERS =
            "{\"payment_no\": {\"in\": \"query\", \"required\": true, \"type\": \"string\"}}";

    private final AiConnectorOperationMapper operationMapper = mock(AiConnectorOperationMapper.class);

    private final AiToolWriteGate gate = new AiToolWriteGate(operationMapper);

    private static AiToolDO tool() {
        return new AiToolDO().setId(91L).setCode("create-payment").setConnectorId(CONNECTOR_ID);
    }

    private static AiToolVersionDO version(String policy, String outputSchemaJson) {
        return new AiToolVersionDO()
                .setId(101L)
                .setToolId(91L)
                .setVersionNo(1)
                .setStatus(AiToolVersionDO.STATUS_DRAFT)
                .setToolType("WRITE")
                .setPolicy(policy)
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("createPayment")
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson(outputSchemaJson)
                .setSchemaHash("a".repeat(64))
                .setVersion(1);
    }

    private static String writeSchema(String reconcileOperation) {
        return "{\"columns\":[],\"write\":{\"idempotencyParam\":\"payment_no\",\"reconcileOperation\":\""
                + reconcileOperation + "\"}}";
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiConnectorOperationDO operation(String status, String parameterJson) {
        return new AiConnectorOperationDO()
                .setId(1L)
                .setConnectorId(CONNECTOR_ID)
                .setStatus(status)
                .setParameterJson(parameterJson);
    }

    private void mockOperations(String writeStatus, String reconcileStatus) {
        when(operationMapper.selectByKey(CONNECTOR_ID, "createPayment"))
                .thenReturn(operation(writeStatus, WRITE_OPERATION_PARAMETERS));
        when(operationMapper.selectByKey(CONNECTOR_ID, "getPayment"))
                .thenReturn(operation(reconcileStatus, RECONCILE_OPERATION_PARAMETERS));
    }

    @BeforeEach
    void setUp() {
        mockOperations(AiConnectorOperationDO.STATUS_PUBLISHED, AiConnectorOperationDO.STATUS_PUBLISHED);
    }

    @Test
    void publishRejectsAutoPolicyAndReadVersionsWithWriteBinding() {
        // 写调用必须人工确认：AUTO 写版本不能发布
        assertThatThrownBy(() -> gate.requirePublishable(tool(), version("AUTO", writeSchema("getPayment"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_POLICY_UNSUPPORTED));

        // 读版本不得声明写绑定
        AiToolVersionDO readWithBinding =
                version("AUTO", writeSchema("getPayment")).setToolType("READ");
        assertThatThrownBy(() -> gate.requirePublishable(tool(), readWithBinding))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));

        // 无写声明的读版本正常返回 null
        assertThat(gate.requirePublishable(
                        tool(), version("AUTO", "{\"columns\":[]}").setToolType("READ")))
                .isNull();
    }

    @Test
    void publishRequiresIdempotencyKeyAndReconcileQueryToBeReallyUsable() {
        AiToolWriteBinding binding = gate.requirePublishable(tool(), version("CONFIRM", writeSchema("getPayment")));
        assertThat(binding.idempotencyParam()).isEqualTo("payment_no");

        // 写操作没有声明幂等键参数：键发不出去，上游无法去重
        when(operationMapper.selectByKey(CONNECTOR_ID, "createPayment"))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"amount\": {\"in\": \"body\"}}"));
        assertThatThrownBy(() -> gate.requirePublishable(tool(), version("CONFIRM", writeSchema("getPayment"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));

        // 核对操作未导入 / 未发布：核对查不到键
        mockOperations(AiConnectorOperationDO.STATUS_PUBLISHED, AiConnectorOperationDO.STATUS_PUBLISHED);
        when(operationMapper.selectByKey(CONNECTOR_ID, "getPayment"))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_DRAFT, RECONCILE_OPERATION_PARAMETERS));
        assertThatThrownBy(() -> gate.requirePublishable(tool(), version("CONFIRM", writeSchema("getPayment"))))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED));

        when(operationMapper.selectByKey(CONNECTOR_ID, "getPayment")).thenReturn(null);
        assertThatThrownBy(() -> gate.requirePublishable(tool(), version("CONFIRM", writeSchema("getPayment"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND));

        // 核对操作没有声明核对参数：核对查的是无关查询
        when(operationMapper.selectByKey(CONNECTOR_ID, "getPayment"))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"other\": {\"in\": \"query\"}}"));
        assertThatThrownBy(() -> gate.requirePublishable(tool(), version("CONFIRM", writeSchema("getPayment"))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));
    }

    @Test
    void executionRequiresFrozenBindingToMatchCurrentPublishedVersion() {
        AiToolVersionDO published =
                version("CONFIRM", writeSchema("getPayment")).setStatus(AiToolVersionDO.STATUS_PUBLISHED);

        assertThat(gate.requireExecutableBinding(tool(), published, "payment_no", "getPayment"))
                .satisfies(binding -> assertThat(binding.reconcileOperation()).isEqualTo("getPayment"));

        // 新版本把核对查询换掉：旧确认不能继续
        AiToolVersionDO switched =
                version("CONFIRM", writeSchema("getOtherPayment")).setStatus(AiToolVersionDO.STATUS_PUBLISHED);
        assertThatThrownBy(() -> gate.requireExecutableBinding(tool(), switched, "payment_no", "getPayment"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_CHANGED));

        // 工具被换成读版本（写语义消失）
        AiToolVersionDO readVersion =
                version("CONFIRM", "{\"columns\":[]}").setToolType("READ").setStatus(AiToolVersionDO.STATUS_PUBLISHED);
        assertThatThrownBy(() -> gate.requireExecutableBinding(tool(), readVersion, "payment_no", "getPayment"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_CHANGED));

        // 声明的参数在重新导入后消失：绑定等于失效
        when(operationMapper.selectByKey(CONNECTOR_ID, "createPayment"))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"amount\": {\"in\": \"body\"}}"));
        assertThatThrownBy(() -> gate.requireExecutableBinding(tool(), published, "payment_no", "getPayment"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_CHANGED));
    }

    @Test
    void reconcileQueryMustStillFilterByBusinessKey() {
        gate.requireReconcileKeyed(CONNECTOR_ID, "getPayment", "payment_no");

        when(operationMapper.selectByKey(CONNECTOR_ID, "getPayment"))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"other\": {\"in\": \"query\"}}"));
        assertThatThrownBy(() -> gate.requireReconcileKeyed(CONNECTOR_ID, "getPayment", "payment_no"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_CHANGED));

        // 参数声明不是 JSON 对象（脏数据）：按"没有声明"处理，而不是猜
        when(operationMapper.selectByKey(CONNECTOR_ID, "getPayment"))
                .thenReturn(operation(AiConnectorOperationDO.STATUS_PUBLISHED, "not-json"));
        assertThatThrownBy(() -> gate.requireReconcileKeyed(CONNECTOR_ID, "getPayment", "payment_no"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_CHANGED));

        assertThatThrownBy(() -> gate.requireReconcileKeyed(null, null, "payment_no"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID));
    }
}
