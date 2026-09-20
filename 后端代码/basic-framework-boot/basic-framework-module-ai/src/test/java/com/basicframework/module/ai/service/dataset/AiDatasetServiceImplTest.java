package com.basicframework.module.ai.service.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlObjectMetadata;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetVersionDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetMapper;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetVersionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionVerifyResultDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D04 服务层：来源授权、版本不可变、漂移判定、发布前重校验与引用保护。 */
class AiDatasetServiceImplTest {

    private static final Long DATASET_ID = 81L;

    private static final Long CONNECTOR_ID = 71L;

    private static final Long VERSION_ID = 91L;

    private static final String DEFINITION =
            """
            {"grain": "一行一单",
             "fields": [
               {"name": "order_id", "sourceColumn": "id", "type": "NUMBER", "unit": "COUNT",
                "visibility": "PUBLIC"},
               {"name": "amount", "sourceColumn": "amount", "type": "DECIMAL", "unit": "CURRENCY",
                "visibility": "INTERNAL"}],
             "metrics": [{"name": "total_amount", "field": "amount", "aggregation": "SUM",
                          "unit": "CURRENCY", "visibility": "INTERNAL"}],
             "dimensions": [{"name": "order_amount", "field": "amount"}]}
            """;

    private final AiDatasetMapper datasetMapper = mock(AiDatasetMapper.class);

    private final AiDatasetVersionMapper versionMapper = mock(AiDatasetVersionMapper.class);

    private final AiConnectorMapper connectorMapper = mock(AiConnectorMapper.class);

    private final AiMysqlConnectorService mysqlConnectorService = mock(AiMysqlConnectorService.class);

    private final AiDatasetVersionReferenceChecker referenceChecker = mock(AiDatasetVersionReferenceChecker.class);

    private final AiDatasetVersionDriftWriter driftWriter = mock(AiDatasetVersionDriftWriter.class);

    private final AiDatasetServiceImpl service = new AiDatasetServiceImpl(
            datasetMapper,
            versionMapper,
            connectorMapper,
            mysqlConnectorService,
            List.of(referenceChecker),
            driftWriter);

    private static AiConnectorDO connector() {
        return new AiConnectorDO()
                .setId(CONNECTOR_ID)
                .setCode("crm-readonly")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setStatus(AiConnectorDO.STATUS_ENABLED)
                .setConfigJson("{\"host\":\"db.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"ro\","
                        + "\"allowedObjects\":[\"crm.orders\"]}")
                .setCredentialCiphertext("v1:encrypted")
                .setCredentialRevision(1);
    }

    private static AiDatasetDO dataset() {
        return new AiDatasetDO()
                .setId(DATASET_ID)
                .setCode("crm-orders")
                .setName("CRM 订单")
                .setConnectorId(CONNECTOR_ID)
                .setSourceObject("crm.orders")
                .setStatus(AiDatasetDO.STATUS_ENABLED)
                .setLatestVersionNo(1)
                .setPublishedVersionNo(0)
                .setVersion(1);
    }

    private static AiDatasetVersionDO version(String status, String verificationStatus) {
        return new AiDatasetVersionDO()
                .setId(VERSION_ID)
                .setDatasetId(DATASET_ID)
                .setVersionNo(1)
                .setStatus(status)
                .setDefinitionJson(DEFINITION)
                .setSchemaHash("a".repeat(64))
                .setVerificationStatus(verificationStatus)
                .setVersion(2);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    /** 上游对象（列名 + 类型）。 */
    private static AiMysqlObjectMetadata upstream(String... columns) {
        List<AiMysqlObjectMetadata.Column> parsed = new java.util.ArrayList<>();
        for (String column : columns) {
            String[] parts = column.split(":");
            parsed.add(new AiMysqlObjectMetadata.Column(parts[0], parts[1], true));
        }
        return new AiMysqlObjectMetadata("crm", "orders", AiMysqlObjectMetadata.TYPE_TABLE, parsed);
    }

    @BeforeEach
    void setUp() {
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector());
        when(datasetMapper.selectById(DATASET_ID)).thenReturn(dataset());
        when(versionMapper.selectById(VERSION_ID)).thenReturn(version(AiDatasetVersionDO.STATUS_DRAFT, null));
        when(mysqlConnectorService.discoverObjects(CONNECTOR_ID))
                .thenReturn(List.of(upstream("id:bigint", "amount:decimal", "note:varchar")));
        when(referenceChecker.findReference(any())).thenReturn(Optional.empty());
    }

    @Test
    void createRequiresAuthorizedMysqlSourceAndUniqueCode() {
        doAnswer(invocation -> {
                    ((AiDatasetDO) invocation.getArgument(0)).setId(DATASET_ID);
                    return 1;
                })
                .when(datasetMapper)
                .insert(any(AiDatasetDO.class));

        assertThat(service.create(new AiDatasetSaveDTO()
                        .setCode("crm-orders")
                        .setName("CRM 订单")
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject("CRM.Orders")))
                .isEqualTo(DATASET_ID);

        ArgumentCaptor<AiDatasetDO> captor = ArgumentCaptor.forClass(AiDatasetDO.class);
        verify(datasetMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceObject()).as("来源对象规范化为小写").isEqualTo("crm.orders");
        assertThat(captor.getValue().getStatus()).isEqualTo(AiDatasetDO.STATUS_ENABLED);

        // 未授权来源
        assertThatThrownBy(() -> service.create(new AiDatasetSaveDTO()
                        .setCode("crm-customers")
                        .setName("CRM 客户")
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject("crm.customers")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_SOURCE_NOT_AUTHORIZED));

        // 连接器不存在 / 类型不是 MySQL
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.create(new AiDatasetSaveDTO()
                        .setCode("crm-orders")
                        .setName("CRM 订单")
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject("crm.orders")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector().setConnectorType("HTTP"));
        assertThatThrownBy(() -> service.create(new AiDatasetSaveDTO()
                        .setCode("crm-orders")
                        .setName("CRM 订单")
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject("crm.orders")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));

        // 标识重复
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector());
        when(datasetMapper.selectByCode("crm-orders")).thenReturn(dataset());
        assertThatThrownBy(() -> service.create(new AiDatasetSaveDTO()
                        .setCode("crm-orders")
                        .setName("CRM 订单")
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject("crm.orders")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_CODE_DUPLICATE));

        // 入参非法（标识模式、缺来源、超长名称）
        for (AiDatasetSaveDTO invalid : List.of(
                new AiDatasetSaveDTO()
                        .setCode("1bad")
                        .setName("x")
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject("crm.orders"),
                new AiDatasetSaveDTO().setCode("crm-orders").setName("x").setConnectorId(CONNECTOR_ID),
                new AiDatasetSaveDTO()
                        .setCode("crm-orders")
                        .setName("x".repeat(129))
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject("crm.orders"),
                new AiDatasetSaveDTO()
                        .setCode("crm-orders")
                        .setName("x")
                        .setConnectorId(CONNECTOR_ID)
                        .setSourceObject(""))) {
            assertThatThrownBy(() -> service.create(invalid))
                    .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        }
    }

    @Test
    void updateAndStatusUseOptimisticLock() {
        assertThat(service.getDataset(DATASET_ID).getCode()).isEqualTo("crm-orders");
        when(datasetMapper.updateWithVersion(any(), any())).thenReturn(1);
        service.update(new AiDatasetSaveDTO()
                .setId(DATASET_ID)
                .setName("新名称")
                .setDescription("说明")
                .setVersion(1));
        service.updateStatus(DATASET_ID, 2, false);

        when(datasetMapper.updateWithVersion(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.update(
                        new AiDatasetSaveDTO().setId(DATASET_ID).setName("新名称").setVersion(1)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
        assertThatThrownBy(() -> service.updateStatus(DATASET_ID, 2, null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.updateStatus(DATASET_ID, null, true))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));

        when(datasetMapper.selectById(DATASET_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.getDataset(DATASET_ID))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_NOT_FOUND));
    }

    @Test
    void deleteIsBlockedWhileAnyVersionIsReferenced() {
        when(versionMapper.selectByDataset(DATASET_ID))
                .thenReturn(List.of(
                        version(AiDatasetVersionDO.STATUS_PUBLISHED, AiDatasetVersionDO.VERIFICATION_VERIFIED)));
        when(referenceChecker.findReference(VERSION_ID)).thenReturn(Optional.of("报表 r-1 的版本 2 正在使用"));
        assertThatThrownBy(() -> service.delete(DATASET_ID, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_REFERENCED));

        when(referenceChecker.findReference(VERSION_ID)).thenReturn(Optional.empty());
        when(datasetMapper.updateWithVersion(any(), any())).thenReturn(1);
        service.delete(DATASET_ID, 1);
        verify(datasetMapper).deleteById(DATASET_ID);
    }

    @Test
    void createVersionValidatesDefinitionAndBumpsLatestVersion() {
        when(versionMapper.selectLatest(DATASET_ID))
                .thenReturn(version(AiDatasetVersionDO.STATUS_DRAFT, AiDatasetVersionDO.VERIFICATION_VERIFIED));
        doAnswer(invocation -> {
                    ((AiDatasetVersionDO) invocation.getArgument(0)).setId(VERSION_ID);
                    return 1;
                })
                .when(versionMapper)
                .insert(any(AiDatasetVersionDO.class));
        when(datasetMapper.updateWithVersion(any(), any())).thenReturn(1);

        assertThat(service.createVersion(
                        new AiDatasetVersionSaveDTO().setDatasetId(DATASET_ID).setDefinitionJson(DEFINITION)))
                .isEqualTo(VERSION_ID);

        ArgumentCaptor<AiDatasetVersionDO> captor = ArgumentCaptor.forClass(AiDatasetVersionDO.class);
        verify(versionMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersionNo()).as("版本号在最新版本上递增").isEqualTo(2);
        assertThat(captor.getValue().getStatus()).isEqualTo(AiDatasetVersionDO.STATUS_DRAFT);
        assertThat(captor.getValue().getVerificationStatus()).isEqualTo(AiDatasetVersionDO.VERIFICATION_UNVERIFIED);
        assertThat(captor.getValue().getSchemaHash()).hasSize(64);
        assertThat(captor.getValue().getDefinitionJson()).doesNotContain("\n");

        assertThatThrownBy(() -> service.createVersion(
                        new AiDatasetVersionSaveDTO().setDatasetId(DATASET_ID).setDefinitionJson("{\"grain\":\"g\"}")))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DEFINITION_INVALID));
        assertThatThrownBy(() -> service.createVersion(new AiDatasetVersionSaveDTO().setDatasetId(DATASET_ID)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_REQUEST_INVALID));

        when(datasetMapper.selectById(DATASET_ID)).thenReturn(dataset().setStatus(AiDatasetDO.STATUS_DISABLED));
        assertThatThrownBy(() -> service.createVersion(
                        new AiDatasetVersionSaveDTO().setDatasetId(DATASET_ID).setDefinitionJson(DEFINITION)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_DISABLED));
    }

    @Test
    void verifyMarksDriftWhenUpstreamLosesColumnOrChangesType() {
        when(versionMapper.updateWithVersion(any(), any())).thenReturn(1);

        // 上游完整：验证通过
        AiDatasetVersionVerifyResultDTO verified = service.verifyVersion(VERSION_ID, 2);
        assertThat(verified.getVerificationStatus()).isEqualTo(AiDatasetVersionDO.VERIFICATION_VERIFIED);
        assertThat(verified.isPublishable()).isTrue();
        assertThat(verified.getAddedColumns()).containsExactly("note");
        assertThat(verified.getSourceSchemaHash()).hasSize(64);

        // 上游缺列：置 DRIFTED 并记录缺失列
        when(mysqlConnectorService.discoverObjects(CONNECTOR_ID)).thenReturn(List.of(upstream("id:bigint")));
        AiDatasetVersionVerifyResultDTO missing = service.verifyVersion(VERSION_ID, 3);
        assertThat(missing.getVerificationStatus()).isEqualTo(AiDatasetVersionDO.VERIFICATION_DRIFTED);
        assertThat(missing.getMissingColumns()).containsExactly("amount");
        assertThat(missing.isPublishable()).isFalse();

        // 上游类型变化：同样不可发布
        when(mysqlConnectorService.discoverObjects(CONNECTOR_ID))
                .thenReturn(List.of(upstream("id:bigint", "amount:varchar")));
        AiDatasetVersionVerifyResultDTO changed = service.verifyVersion(VERSION_ID, 4);
        assertThat(changed.getTypeChangedColumns()).containsExactly("amount");
        assertThat(changed.isPublishable()).isFalse();

        // 上游对象整体消失：定义引用的列全部视为缺失
        when(mysqlConnectorService.discoverObjects(CONNECTOR_ID)).thenReturn(List.of());
        AiDatasetVersionVerifyResultDTO gone = service.verifyVersion(VERSION_ID, 5);
        assertThat(gone.getMissingColumns()).containsExactlyInAnyOrder("id", "amount");

        // 已发布版本不可再验证
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(AiDatasetVersionDO.STATUS_PUBLISHED, AiDatasetVersionDO.VERIFICATION_VERIFIED));
        assertThatThrownBy(() -> service.verifyVersion(VERSION_ID, 6))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_IMMUTABLE));

        when(versionMapper.selectById(VERSION_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.verifyVersion(VERSION_ID, 1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_FOUND));
    }

    @Test
    void publishRequiresVerifiedAndUnchangedUpstream() {
        // 未验证：拒绝
        assertThatThrownBy(() -> service.publishVersion(VERSION_ID, 2))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_NOT_VERIFIED));

        // 已验证但上游自验证后发生变化：置 DRIFTED 并拒绝
        AiDatasetVersionDO verifiedVersion =
                version(AiDatasetVersionDO.STATUS_DRAFT, AiDatasetVersionDO.VERIFICATION_VERIFIED);
        verifiedVersion.setSourceSchemaHash("f".repeat(64));
        when(versionMapper.selectById(VERSION_ID)).thenReturn(verifiedVersion);
        when(versionMapper.updateWithVersion(any(), any())).thenReturn(1);
        assertThatThrownBy(() -> service.publishVersion(VERSION_ID, 2))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_DRIFTED));
        verify(driftWriter).markDrifted(VERSION_ID, 2, "missing=;typeChanged=;added=note");

        // 已验证且上游未变：发布成功并回写数据集的已发布版本号
        when(mysqlConnectorService.discoverObjects(CONNECTOR_ID))
                .thenReturn(List.of(upstream("id:bigint", "amount:decimal")));
        String hash = service.verifyVersion(VERSION_ID, 2).getSourceSchemaHash();
        AiDatasetVersionDO verifiedRow =
                version(AiDatasetVersionDO.STATUS_DRAFT, AiDatasetVersionDO.VERIFICATION_VERIFIED);
        verifiedRow.setSourceSchemaHash(hash);
        when(versionMapper.selectById(VERSION_ID)).thenReturn(verifiedRow);
        when(datasetMapper.updateWithVersion(any(), any())).thenReturn(1);
        AiDatasetVersionVerifyResultDTO published = service.publishVersion(VERSION_ID, 2);
        assertThat(published.getStatus()).isEqualTo(AiDatasetVersionDO.STATUS_PUBLISHED);

        ArgumentCaptor<AiDatasetVersionDO> versionCaptor = ArgumentCaptor.forClass(AiDatasetVersionDO.class);
        verify(versionMapper, org.mockito.Mockito.atLeastOnce()).updateWithVersion(versionCaptor.capture(), any());
        assertThat(versionCaptor.getAllValues())
                .anySatisfy(update -> assertThat(update.getStatus()).isEqualTo(AiDatasetVersionDO.STATUS_PUBLISHED));
        ArgumentCaptor<AiDatasetDO> datasetCaptor = ArgumentCaptor.forClass(AiDatasetDO.class);
        verify(datasetMapper).updateWithVersion(datasetCaptor.capture(), any());
        assertThat(datasetCaptor.getValue().getPublishedVersionNo()).isEqualTo(1);

        // 已发布版本再次发布：不可变
        when(versionMapper.selectById(VERSION_ID))
                .thenReturn(version(AiDatasetVersionDO.STATUS_PUBLISHED, AiDatasetVersionDO.VERIFICATION_VERIFIED));
        assertThatThrownBy(() -> service.publishVersion(VERSION_ID, 2))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_VERSION_IMMUTABLE));
    }

    @Test
    void pagesDelegateAndDatasetMustExistForVersionPage() {
        when(datasetMapper.selectPage(
                        any(com.basicframework.framework.common.pojo.PageParam.class),
                        any(Long.class),
                        any(String.class)))
                .thenReturn(new com.basicframework.framework.common.pojo.PageResult<>(List.of(dataset()), 1L));
        when(versionMapper.selectPage(
                        any(com.basicframework.framework.common.pojo.PageParam.class),
                        any(Long.class),
                        org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new com.basicframework.framework.common.pojo.PageResult<>(
                        List.of(version(AiDatasetVersionDO.STATUS_DRAFT, null)), 1L));

        assertThat(service.getDatasetPage(
                                new com.basicframework.framework.common.pojo.PageParam(),
                                CONNECTOR_ID,
                                AiDatasetDO.STATUS_ENABLED)
                        .getTotal())
                .isEqualTo(1L);
        assertThat(service.getVersionPage(DATASET_ID, new com.basicframework.framework.common.pojo.PageParam())
                        .getTotal())
                .isEqualTo(1L);

        when(datasetMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getVersionPage(404L, new com.basicframework.framework.common.pojo.PageParam()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_DATASET_NOT_FOUND));
    }

    /** 版本引用检查端口在无实现时不影响删除（D04 单独可用）。 */
    @Test
    void referenceCheckerIsOptional() {
        AiDatasetServiceImpl withoutCheckers = new AiDatasetServiceImpl(
                datasetMapper, versionMapper, connectorMapper, mysqlConnectorService, List.of(), driftWriter);
        when(versionMapper.selectByDataset(DATASET_ID)).thenReturn(List.of());
        when(datasetMapper.updateWithVersion(any(), any())).thenReturn(1);

        withoutCheckers.delete(DATASET_ID, 1);
        verify(datasetMapper).deleteById(DATASET_ID);
    }
}
