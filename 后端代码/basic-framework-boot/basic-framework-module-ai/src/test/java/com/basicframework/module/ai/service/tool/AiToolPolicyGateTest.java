package com.basicframework.module.ai.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.http.AiHttpConnectorExecutor;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolVersionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D08 政策矩阵与受控执行边界：DENY/CONFIRM/AUTO、伪造工具名与参数、读政策不能被改成写。 */
class AiToolPolicyGateTest {

    private static final Long TOOL_ID = 91L;

    private static final Long CONNECTOR_ID = 71L;

    private static final String TOOL_CODE = "query-orders";

    private static final String INPUT_SCHEMA =
            """
            {"region": {"type": "string", "required": true},
             "limit": {"type": "number", "required": false}}
            """;

    private final AiToolMapper toolMapper = mock(AiToolMapper.class);

    private final AiToolVersionMapper versionMapper = mock(AiToolVersionMapper.class);

    private final AiConnectorMapper connectorMapper = mock(AiConnectorMapper.class);

    private final AiConnectorOperationMapper operationMapper = mock(AiConnectorOperationMapper.class);

    private final AiHttpConnectorExecutor httpConnectorExecutor = mock(AiHttpConnectorExecutor.class);

    private final AiToolServiceImpl toolService =
            new AiToolServiceImpl(toolMapper, versionMapper, connectorMapper, operationMapper, List.of());

    private final AiToolPolicyGate gate = new AiToolPolicyGate(toolMapper, toolService);

    private final AiToolExecutor executor = new AiToolExecutor(httpConnectorExecutor);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiToolVersionDO version(String policy, String toolType) {
        return new AiToolVersionDO()
                .setId(101L)
                .setToolId(TOOL_ID)
                .setVersionNo(1)
                .setStatus(AiToolVersionDO.STATUS_PUBLISHED)
                .setToolType(toolType)
                .setPolicy(policy)
                .setSourceKind(AiToolVersionDO.SOURCE_HTTP_OPERATION)
                .setSourceRef("getOrders")
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson("{\"columns\":[{\"code\":\"net_amount\"}]}")
                .setSchemaHash("a".repeat(64))
                .setVersion(1);
    }

    @BeforeEach
    void setUp() {
        when(toolMapper.selectByCode(TOOL_CODE))
                .thenReturn(new AiToolDO()
                        .setId(TOOL_ID)
                        .setCode(TOOL_CODE)
                        .setConnectorId(CONNECTOR_ID)
                        .setStatus(AiToolDO.STATUS_ENABLED)
                        .setLatestVersionNo(1)
                        .setVersion(1));
        when(toolMapper.selectById(TOOL_ID))
                .thenReturn(new AiToolDO()
                        .setId(TOOL_ID)
                        .setConnectorId(CONNECTOR_ID)
                        .setStatus(AiToolDO.STATUS_ENABLED));
        when(versionMapper.selectByTool(TOOL_ID)).thenReturn(List.of(version("AUTO", "READ")));
    }

    @Test
    void autoPolicyExecutesWithValidatedArgumentsOnly() {
        AiToolDecision decision = gate.decide(TOOL_CODE, Map.of("region", "EAST", "limit", "5"));

        assertThat(decision.executable()).isTrue();
        assertThat(decision.connectorId()).isEqualTo(CONNECTOR_ID);
        assertThat(decision.operationKey()).isEqualTo("getOrders");
        assertThat(decision.arguments()).containsOnlyKeys("region", "limit");

        when(httpConnectorExecutor.execute(any()))
                .thenReturn(new AiConnectorExecutionResultDTO()
                        .setStatus("COMPLETE")
                        .setItems(List.of("{}")));
        executor.execute(decision);

        ArgumentCaptor<AiConnectorExecutionRequestDTO> captor =
                ArgumentCaptor.forClass(AiConnectorExecutionRequestDTO.class);
        verify(httpConnectorExecutor).execute(captor.capture());
        assertThat(captor.getValue().getOperationKey())
                .as("方法/URL/请求头都来自版本快照，调用方只能给参数")
                .isEqualTo("getOrders");
        assertThat(captor.getValue().getArguments()).containsOnlyKeys("region", "limit");
    }

    @Test
    void denyPolicyBlocksExecution() {
        when(versionMapper.selectByTool(TOOL_ID)).thenReturn(List.of(version("DENY", "READ")));

        assertThatThrownBy(() -> gate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));
        verify(httpConnectorExecutor, never()).execute(any());
    }

    @Test
    void confirmPolicyRequiresConfirmationAndOnlyConfirmationPathExecutes() {
        when(versionMapper.selectByTool(TOOL_ID)).thenReturn(List.of(version("CONFIRM", "READ")));

        assertThatThrownBy(() -> gate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED));

        // 确认后执行：参数重新校验，来源仍来自版本
        AiToolDecision confirmed = gate.decideAfterConfirmation(TOOL_CODE, Map.of("region", "EAST"));
        assertThat(confirmed.executable()).isTrue();
        assertThat(confirmed.arguments()).containsEntry("region", "EAST");

        // 非 CONFIRM 政策不能走"确认后执行"（避免把确认流程当政策绕过手段）
        when(versionMapper.selectByTool(TOOL_ID)).thenReturn(List.of(version("DENY", "READ")));
        assertThatThrownBy(() -> gate.decideAfterConfirmation(TOOL_CODE, Map.of("region", "EAST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));
    }

    @Test
    void forgedToolNameDisabledToolAndUnpublishedVersionAreRejected() {
        when(toolMapper.selectByCode("forged-tool")).thenReturn(null);
        assertThatThrownBy(() -> gate.decide("forged-tool", Map.of()))
                .as("伪造工具名按不存在处理")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_NOT_FOUND));

        when(toolMapper.selectByCode(TOOL_CODE))
                .thenReturn(new AiToolDO()
                        .setId(TOOL_ID)
                        .setStatus(AiToolDO.STATUS_DISABLED)
                        .setVersion(1));
        assertThatThrownBy(() -> gate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .as("停用工具与不存在同码，避免探测")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_NOT_FOUND));

        when(toolMapper.selectByCode(TOOL_CODE))
                .thenReturn(new AiToolDO()
                        .setId(TOOL_ID)
                        .setStatus(AiToolDO.STATUS_ENABLED)
                        .setVersion(1));
        when(versionMapper.selectByTool(TOOL_ID))
                .thenReturn(List.of(version("AUTO", "READ").setStatus(AiToolVersionDO.STATUS_DRAFT)));
        assertThatThrownBy(() -> gate.decide(TOOL_CODE, Map.of("region", "EAST")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_VERSION_NOT_PUBLISHED));
    }

    @Test
    void forgedArgumentsAreRejectedBeforeAnyExecution() {
        assertThatThrownBy(() -> gate.decide(TOOL_CODE, Map.of("region", "EAST", "drop_table", "true")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID));
        assertThatThrownBy(() -> gate.decide(TOOL_CODE, Map.of()))
                .as("必填参数缺失")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID));
        assertThatThrownBy(() -> gate.decide(TOOL_CODE, Map.of("region", Map.of("x", 1))))
                .as("参数类型不符")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID));
        verify(httpConnectorExecutor, never()).execute(any());
    }

    @Test
    void executorRefusesDecisionsWithoutExecuteOutcome() {
        assertThatThrownBy(() -> executor.execute(null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));
        assertThatThrownBy(() -> executor.execute(new AiToolDecision(
                        AiToolDecision.Outcome.CONFIRM,
                        version("CONFIRM", "READ"),
                        CONNECTOR_ID,
                        "getOrders",
                        Map.of())))
                .as("确认判定本身不是执行许可")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));
        assertThatThrownBy(() -> executor.execute(new AiToolDecision(
                        AiToolDecision.Outcome.EXECUTE, version("AUTO", "READ"), null, "getOrders", Map.of())))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_POLICY_DENIED));
        verify(httpConnectorExecutor, never()).execute(any());
    }

    @Test
    void readPolicyCannotBeTurnedIntoAWriteByTheCall() {
        // 版本快照是 READ + AUTO：调用方（模型）能给的只有参数，没有任何字段能改方法/URL/政策/类型
        AiToolDecision decision = gate.decide(TOOL_CODE, Map.of("region", "EAST"));
        assertThat(decision.version().getToolType()).isEqualTo("READ");
        assertThat(decision.version().getPolicy()).isEqualTo("AUTO");
        assertThat(decision.operationKey()).isEqualTo("getOrders");

        // 写工具版本无法发布（首期）：来源绑定与类型都来自版本
        when(versionMapper.selectById(101L))
                .thenReturn(version("AUTO", "WRITE").setStatus(AiToolVersionDO.STATUS_DRAFT));
        when(toolMapper.selectById(TOOL_ID))
                .thenReturn(new AiToolDO()
                        .setId(TOOL_ID)
                        .setConnectorId(CONNECTOR_ID)
                        .setVersion(1));
        assertThatThrownBy(() -> toolService.publishVersion(101L, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_TYPE_UNSUPPORTED));

        // 来源 operation 未发布时同样拒绝发布
        when(versionMapper.selectById(101L))
                .thenReturn(version("AUTO", "READ").setStatus(AiToolVersionDO.STATUS_DRAFT));
        when(operationMapper.selectByKey(CONNECTOR_ID, "getOrders"))
                .thenReturn(new AiConnectorOperationDO().setStatus(AiConnectorOperationDO.STATUS_DRAFT));
        assertThatThrownBy(() -> toolService.publishVersion(101L, 1))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED));

        when(operationMapper.selectByKey(CONNECTOR_ID, "getOrders")).thenReturn(null);
        assertThatThrownBy(() -> toolService.publishVersion(101L, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND));
    }

    @Test
    void versionCreationDefaultsToDenyAndRejectsUnsupportedSource() {
        when(toolMapper.selectById(TOOL_ID))
                .thenReturn(new AiToolDO()
                        .setId(TOOL_ID)
                        .setConnectorId(CONNECTOR_ID)
                        .setVersion(1));
        when(versionMapper.selectLatest(TOOL_ID)).thenReturn(null);
        when(toolMapper.updateWithVersion(any(), any())).thenReturn(1);

        toolService.createVersion(new com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO()
                .setToolId(TOOL_ID)
                .setToolType("READ")
                .setSourceRef("getOrders")
                .setInputSchemaJson(INPUT_SCHEMA)
                .setOutputSchemaJson("{\"columns\":[]}"));

        ArgumentCaptor<AiToolVersionDO> captor = ArgumentCaptor.forClass(AiToolVersionDO.class);
        verify(versionMapper).insert(captor.capture());
        assertThat(captor.getValue().getPolicy()).as("不写政策就是 DENY").isEqualTo("DENY");
        assertThat(captor.getValue().getToolType())
                .as("显式声明 READ 才能发布；不写类型默认按 WRITE 处理（更严格）")
                .isEqualTo("READ");
        assertThat(captor.getValue().getSchemaHash()).hasSize(64);

        // 不支持的来源类型
        assertThatThrownBy(() -> toolService.createVersion(
                        new com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO()
                                .setToolId(TOOL_ID)
                                .setSourceKind("MYSQL_DATASET")
                                .setSourceRef("crm.orders")
                                .setInputSchemaJson(INPUT_SCHEMA)
                                .setOutputSchemaJson("{\"columns\":[]}")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_TYPE_UNSUPPORTED));
    }

    @Test
    void deleteIsBlockedWhileReferenced() {
        AiToolServiceImpl withChecker = new AiToolServiceImpl(
                toolMapper,
                versionMapper,
                connectorMapper,
                operationMapper,
                List.of(toolId -> Optional.of("服务发布版本 s-1 正在使用该工具")));
        when(toolMapper.selectById(TOOL_ID))
                .thenReturn(new AiToolDO()
                        .setId(TOOL_ID)
                        .setConnectorId(CONNECTOR_ID)
                        .setVersion(1));

        assertThatThrownBy(() -> withChecker.delete(TOOL_ID, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_REFERENCED));
        verify(toolMapper, never()).deleteById(any());
    }
}
