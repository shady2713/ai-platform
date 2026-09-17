package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.mysql.model.AiModelProbeMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M04 能力探测与嵌入维度的真实 MySQL 集成验证：
 * 探测结论按类型追加落库并只读回最新一条、失败结论不被启用状态掩盖、
 * 嵌入维度首写记录且改变即拒绝（对既有索引的保护语义）。
 *
 * <p>探测本身在上游不可达时也必须收敛为 FAILED 结论而不是抛出异常：
 * 本用例用"目标不在出站允许清单"的端点验证这条路径，因此不需要真实模型服务。
 */
class AiModelProbePersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String NAME = "it-probe-endpoint";

    @Autowired
    private AiModelCapabilityProbeService probeService;

    @Autowired
    private AiModelEndpointService endpointService;

    @Autowired
    private AiModelProbeMapper probeMapper;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @AfterEach
    void cleanUp() {
        Long endpointId = queryEndpointId();
        if (endpointId != null) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    private Long queryEndpointId() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, NAME);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long createEndpoint(boolean enabled) {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName(NAME);
        saveDTO.setProvider("openai_compatible");
        // 未列入 ai.http.allowed-hosts 的目标：解析阶段即被拒绝，探测必须收敛为 FAILED 结论
        saveDTO.setBaseUrl("https://it-probe.example.com/v1");
        saveDTO.setModelId("gpt-4o-mini");
        saveDTO.setCapabilities(List.of("TEXT", "EMBEDDING"));
        saveDTO.setCredential("sk-it-probe");
        Long id = endpointService.createEndpoint(saveDTO);
        if (enabled) {
            AiModelEndpointDO created = endpointService.getEndpoint(id);
            endpointService.updateEndpointStatus(id, created.getVersion(), true);
        }
        return id;
    }

    @Test
    void probeResultsAreAppendedAndLatestWinsPerKind() {
        Long endpointId = createEndpoint(true);

        List<AiModelProbeResultDTO> results = probeService.probeAll(endpointId);

        assertThat(results).hasSize(6);
        assertThat(results)
                .extracting(AiModelProbeResultDTO::getProbeKind)
                .containsExactly(
                        "CONNECTIVITY", "TEXT", "TEXT_STREAM", "STRUCTURED_OUTPUT", "TOOL_CALLING", "EMBEDDING");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getDetailCode()).as("失败只落稳定原因码").isNotBlank();
            assertThat(result.getLatencyMs()).isNotNull();
        });

        // 原生 SQL 写入的后续历史：最新一条必须在查询中胜出
        insertProbe(endpointId, "TEXT", "SUPPORTED", null, null);
        sqlSessionTemplate.clearCache();
        List<AiModelProbeResultDTO> latest = probeService.getLatestResults(endpointId);

        assertThat(latest).hasSize(6);
        assertThat(latest.stream()
                        .filter(result -> "TEXT".equals(result.getProbeKind()))
                        .findFirst()
                        .orElseThrow())
                .satisfies(result -> {
                    assertThat(result.getStatus()).isEqualTo("SUPPORTED");
                    assertThat(result.getDetailCode()).isNull();
                });
    }

    @Test
    void probeFailureIsRecordedEvenWhenEndpointIsDisabled() {
        Long endpointId = createEndpoint(false);

        List<AiModelProbeResultDTO> results = probeService.probeAll(endpointId);

        assertThat(results).allSatisfy(result -> assertThat(result.getStatus()).isEqualTo("FAILED"));
        assertThat(probeService.getLatestResults(endpointId)).hasSize(6);
        sqlSessionTemplate.clearCache();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_model_probe WHERE endpoint_id = ? AND deleted = 0", Integer.class, endpointId);
        assertThat(count).as("停用端点的探测结论同样落库，不被启用状态掩盖").isEqualTo(6);
    }

    @Test
    void embeddingDimensionIsRecordedOnceAndChangeIsRejected() {
        Long endpointId = createEndpoint(true);

        endpointService.assertEmbeddingDimensionUnchanged(endpointId, 1536);
        sqlSessionTemplate.clearCache();
        assertThat(endpointService.getEndpoint(endpointId).getEmbeddingDimension())
                .isEqualTo(1536);

        // 相同维度可以继续写入既有索引
        endpointService.assertEmbeddingDimensionUnchanged(endpointId, 1536);

        // 维度变化必须拒绝
        assertThatThrownBy(() -> endpointService.assertEmbeddingDimensionUnchanged(endpointId, 3072))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_EMBEDDING_DIMENSION_CHANGED.getCode());
        sqlSessionTemplate.clearCache();
        assertThat(endpointService.getEndpoint(endpointId).getEmbeddingDimension())
                .as("拒绝后不得被改写")
                .isEqualTo(1536);
    }

    private void insertProbe(
            Long endpointId, String kind, String status, String detailCode, Integer embeddingDimension) {
        jdbcTemplate.update(
                "INSERT INTO ai_model_probe (endpoint_id, config_revision, credential_revision, probe_kind, status,"
                        + " detail_code, embedding_dimension, latency_ms, creator, updater)"
                        + " VALUES (?, 1, 1, ?, ?, ?, ?, 5, 'it', 'it')",
                endpointId,
                kind,
                status,
                detailCode,
                embeddingDimension);
    }
}
