package com.basicframework.module.ai.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.dal.mysql.tool.AiToolVersionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.tool.dto.AiToolSaveDTO;
import com.basicframework.module.ai.service.tool.dto.AiToolVersionSaveDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** D08 工具注册服务：CRUD/启停/删除、版本创建校验与分页（政策与发布规则见 AiToolPolicyGateTest）。 */
class AiToolServiceImplTest {

    private static final Long TOOL_ID = 91L;

    private static final Long CONNECTOR_ID = 71L;

    private final AiToolMapper toolMapper = mock(AiToolMapper.class);

    private final AiToolVersionMapper versionMapper = mock(AiToolVersionMapper.class);

    private final AiConnectorMapper connectorMapper = mock(AiConnectorMapper.class);

    private final AiConnectorOperationMapper operationMapper = mock(AiConnectorOperationMapper.class);

    private final AiToolServiceImpl service =
            new AiToolServiceImpl(toolMapper, versionMapper, connectorMapper, operationMapper, List.of());

    private static AiToolDO tool() {
        return new AiToolDO()
                .setId(TOOL_ID)
                .setCode("query-orders")
                .setName("查询订单")
                .setConnectorId(CONNECTOR_ID)
                .setStatus(AiToolDO.STATUS_ENABLED)
                .setLatestVersionNo(1)
                .setVersion(1);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @BeforeEach
    void setUp() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(new AiConnectorDO()
                        .setId(CONNECTOR_ID)
                        .setCode("crm-http")
                        .setConnectorType(AiConnectorDO.TYPE_HTTP)
                        .setStatus(AiConnectorDO.STATUS_ENABLED));
        when(toolMapper.selectById(TOOL_ID)).thenReturn(tool());
    }

    @Test
    void createsToolWithUniqueCodeAndExistingConnector() {
        doAnswer(invocation -> {
                    ((AiToolDO) invocation.getArgument(0)).setId(TOOL_ID);
                    return 1;
                })
                .when(toolMapper)
                .insert(any(AiToolDO.class));

        assertThat(service.create(new AiToolSaveDTO()
                        .setCode("query-orders")
                        .setName("查询订单")
                        .setConnectorId(CONNECTOR_ID)))
                .isEqualTo(TOOL_ID);

        when(toolMapper.selectByCode("query-orders")).thenReturn(tool());
        assertThatThrownBy(() -> service.create(new AiToolSaveDTO()
                        .setCode("query-orders")
                        .setName("查询订单")
                        .setConnectorId(CONNECTOR_ID)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_CODE_DUPLICATE));

        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.create(new AiToolSaveDTO()
                        .setCode("other-tool")
                        .setName("其他工具")
                        .setConnectorId(CONNECTOR_ID)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));

        // 入参校验：标识模式、缺名称、缺连接器
        for (AiToolSaveDTO invalid : List.of(
                new AiToolSaveDTO().setCode("1bad").setName("x").setConnectorId(CONNECTOR_ID),
                new AiToolSaveDTO().setCode("ok-tool").setName("  ").setConnectorId(CONNECTOR_ID),
                new AiToolSaveDTO().setCode("ok-tool").setName("x"),
                new AiToolSaveDTO().setCode("ok-tool").setName("x".repeat(129)).setConnectorId(CONNECTOR_ID))) {
            assertThatThrownBy(() -> service.create(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        }
    }

    @Test
    void updatesStatusAndDeletesWithOptimisticLock() {
        when(toolMapper.updateWithVersion(any(), any())).thenReturn(1);

        service.update(new AiToolSaveDTO()
                .setId(TOOL_ID)
                .setName("新名称")
                .setDescription("说明")
                .setVersion(1));
        service.updateStatus(TOOL_ID, 2, false);
        service.delete(TOOL_ID, 3);
        verify(toolMapper).deleteById(TOOL_ID);

        when(toolMapper.updateWithVersion(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.update(
                        new AiToolSaveDTO().setId(TOOL_ID).setName("x").setVersion(1)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
        assertThatThrownBy(() -> service.updateStatus(TOOL_ID, 1, true))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
        assertThatThrownBy(() -> service.delete(TOOL_ID, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));

        assertThatThrownBy(() -> service.updateStatus(TOOL_ID, 1, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.delete(TOOL_ID, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));

        when(toolMapper.selectById(TOOL_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.getTool(TOOL_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_NOT_FOUND));
    }

    @Test
    void referenceCheckerBlocksDeletion() {
        AiToolServiceImpl withChecker = new AiToolServiceImpl(
                toolMapper,
                versionMapper,
                connectorMapper,
                operationMapper,
                List.of(toolId -> Optional.of("分析步骤 st-1 正在使用该工具")));

        assertThatThrownBy(() -> withChecker.delete(TOOL_ID, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_REFERENCED));
        verify(toolMapper, never()).deleteById(anyLong());
    }

    @Test
    void versionCreationValidatesSchemasAndBumpsLatestVersion() {
        when(versionMapper.selectLatest(TOOL_ID))
                .thenReturn(
                        new AiToolVersionDO().setToolId(TOOL_ID).setVersionNo(1).setStatus("PUBLISHED"));
        doAnswer(invocation -> {
                    ((AiToolVersionDO) invocation.getArgument(0)).setId(101L);
                    return 1;
                })
                .when(versionMapper)
                .insert(any(AiToolVersionDO.class));
        when(toolMapper.updateWithVersion(any(), any())).thenReturn(1);

        assertThat(service.createVersion(new AiToolVersionSaveDTO()
                        .setToolId(TOOL_ID)
                        .setToolType("READ")
                        .setPolicy("AUTO")
                        .setSourceRef("getOrders")
                        .setInputSchemaJson("{\"region\":{\"type\":\"string\",\"required\":true}}")
                        .setOutputSchemaJson("{\"columns\":[]}")))
                .isEqualTo(101L);

        // 输出 schema 必须是 JSON 对象
        assertThatThrownBy(() -> service.createVersion(new AiToolVersionSaveDTO()
                        .setToolId(TOOL_ID)
                        .setToolType("READ")
                        .setSourceRef("getOrders")
                        .setInputSchemaJson("{\"region\":{\"type\":\"string\"}}")
                        .setOutputSchemaJson("not-json")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));

        // 缺来源 / 缺工具：入参问题
        for (AiToolVersionSaveDTO invalid : List.of(
                new AiToolVersionSaveDTO()
                        .setToolId(TOOL_ID)
                        .setInputSchemaJson("{}")
                        .setOutputSchemaJson("{}"),
                new AiToolVersionSaveDTO()
                        .setSourceRef("getOrders")
                        .setInputSchemaJson("{}")
                        .setOutputSchemaJson("{}"))) {
            assertThatThrownBy(() -> service.createVersion(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        }
        // 缺输入 schema：参数定义层拒绝（AI_TOOL_ARGUMENT_INVALID），与"入参问题"区分
        assertThatThrownBy(() -> service.createVersion(new AiToolVersionSaveDTO()
                        .setToolId(TOOL_ID)
                        .setSourceRef("getOrders")
                        .setOutputSchemaJson("{}")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID));
    }

    @Test
    void pagesAndVersionLookupDelegate() {
        when(toolMapper.selectPage(any(PageParam.class), any(Long.class), any(String.class)))
                .thenReturn(new PageResult<>(List.of(tool()), 1L));
        when(versionMapper.selectPage(
                        any(PageParam.class), any(Long.class), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new PageResult<>(
                        List.of(new AiToolVersionDO()
                                .setId(101L)
                                .setToolId(TOOL_ID)
                                .setVersionNo(1)),
                        1L));

        assertThat(service.getToolPage(new PageParam(), CONNECTOR_ID, AiToolDO.STATUS_ENABLED)
                        .getTotal())
                .isEqualTo(1L);
        assertThat(service.getVersionPage(TOOL_ID, new PageParam()).getTotal()).isEqualTo(1L);
        when(versionMapper.selectById(101L))
                .thenReturn(new AiToolVersionDO().setId(101L).setToolId(TOOL_ID).setVersionNo(1));
        assertThat(service.getVersion(101L).getVersionNo()).isEqualTo(1);

        when(versionMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getVersion(404L))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_TOOL_VERSION_NOT_FOUND));
        assertThatThrownBy(() -> service.getVersionPage(TOOL_ID, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }
}
