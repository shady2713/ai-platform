package com.basicframework.module.ai.adapter.connector.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** D02 执行器：固定 Origin 与请求头、有限分页（重复游标停止 / 超页数 PARTIAL）、参数与失败分支。 */
class AiHttpConnectorExecutorTest {

    private static final Long CONNECTOR_ID = 71L;

    private final AiConnectorMapper connectorMapper = mock(AiConnectorMapper.class);

    private final AiConnectorOperationMapper operationMapper = mock(AiConnectorOperationMapper.class);

    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExternalHttpClient> httpClientProvider = mock(ObjectProvider.class);

    private final ExternalHttpClient httpClient = mock(ExternalHttpClient.class);

    private final AiConnectorAuthHeaders authHeaders = new AiConnectorAuthHeaders(credentialCipher);

    private final AiHttpConnectorExecutor executor =
            new AiHttpConnectorExecutor(connectorMapper, operationMapper, authHeaders, httpClientProvider);

    @BeforeEach
    void setUp() {
        when(httpClientProvider.getIfAvailable()).thenReturn(httpClient);
        when(credentialCipher.decrypt(any(), any())).thenReturn("it-connector-secret");
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector());
    }

    private static AiConnectorDO connector() {
        return new AiConnectorDO()
                .setId(CONNECTOR_ID)
                .setCode("crm-http")
                .setName("CRM 接口")
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setStatus(AiConnectorDO.STATUS_ENABLED)
                .setConfigJson("{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\",\"authType\":\"BEARER\"}")
                .setCredentialCiphertext("v1:encrypted")
                .setCredentialRevision(1)
                .setVersion(0);
    }

    private void stubOperation(String status, String paginationJson) {
        when(operationMapper.selectByKey(CONNECTOR_ID, "getOrder"))
                .thenReturn(new AiConnectorOperationDO()
                        .setId(81L)
                        .setConnectorId(CONNECTOR_ID)
                        .setOperationKey("getOrder")
                        .setHttpMethod("GET")
                        .setPathTemplate("/orders/{id}")
                        .setParameterJson("{\"id\":{\"in\":\"path\",\"required\":true,\"type\":\"string\"},"
                                + "\"tenant\":{\"in\":\"query\",\"required\":false,\"type\":\"string\"}}")
                        .setResponseJson("{\"rootPath\":\"\",\"listPath\":\"data.items\"}")
                        .setPaginationJson(paginationJson)
                        .setStatus(status)
                        .setVersion(0));
    }

    private static AiConnectorExecutionRequestDTO request(Map<String, Object> arguments) {
        return new AiConnectorExecutionRequestDTO()
                .setConnectorId(CONNECTOR_ID)
                .setOperationKey("getOrder")
                .setArguments(arguments);
    }

    private static ExternalHttpResponse json(String body) {
        return new ExternalHttpResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8), body.length());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void usesFixedOriginAndConnectorAuthHeadersOnly() {
        stubOperation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"type\":\"NONE\",\"maxPages\":1}");
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenReturn(json("{\"data\":{\"items\":[{\"orderId\":\"A-1\"}]}}"));

        AiConnectorExecutionResultDTO result = executor.execute(request(Map.of("id", "A-1", "tenant", "tenant-a")));

        assertThat(result.getStatus()).isEqualTo("COMPLETE");
        assertThat(result.getItemCount()).isEqualTo(1);
        assertThat(result.getItems()).singleElement().satisfies(item -> assertThat(item)
                .contains("A-1"));

        ArgumentCaptor<ExternalHttpRequest> captor = ArgumentCaptor.forClass(ExternalHttpRequest.class);
        org.mockito.Mockito.verify(httpClient).execute(captor.capture());
        assertThat(captor.getValue().url())
                .as("URL 只由连接器 baseUrl + 路径模板 + 声明参数拼出")
                .isEqualTo("https://crm.example.com/orders/A-1?tenant=tenant-a");
        assertThat(captor.getValue().headers())
                .as("请求头只来自连接器认证配置（调用方无法替换）")
                .containsEntry("Authorization", "Bearer it-connector-secret")
                .doesNotContainKey("X-Trace");
    }

    @Test
    void rejectsForeignOriginUnsafeArgumentsAndUndeclaredParameters() {
        stubOperation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"type\":\"NONE\",\"maxPages\":1}");

        // 参数取值含查询语法：拒绝（不做转义，避免注入查询片段）
        assertThatThrownBy(() -> executor.execute(request(Map.of("id", "A-1&admin=1"))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID));
        assertThatThrownBy(() -> executor.execute(request(Map.of("id", "A-1", "tenant", "a/b"))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID));
        // 必填 path 参数缺失
        assertThatThrownBy(() -> executor.execute(request(Map.of())))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID));
        // 未声明的参数被忽略（不会进入 URL）
        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(json("{}"));
        executor.execute(request(Map.of("id", "A-1", "unknown", "x")));
        ArgumentCaptor<ExternalHttpRequest> captor = ArgumentCaptor.forClass(ExternalHttpRequest.class);
        org.mockito.Mockito.verify(httpClient).execute(captor.capture());
        assertThat(captor.getValue().url()).doesNotContain("unknown");

        // 操作路径模板被改成绝对 URL（例如直接改库篡改）：拒绝，请求不得离开连接器 Origin
        when(operationMapper.selectByKey(CONNECTOR_ID, "getOrder"))
                .thenReturn(new AiConnectorOperationDO()
                        .setId(81L)
                        .setConnectorId(CONNECTOR_ID)
                        .setOperationKey("getOrder")
                        .setHttpMethod("GET")
                        .setPathTemplate("https://evil.example.com/orders/{id}")
                        .setParameterJson("{\"id\":{\"in\":\"path\",\"required\":true,\"type\":\"string\"}}")
                        .setResponseJson("{}")
                        .setPaginationJson("{\"type\":\"NONE\",\"maxPages\":1}")
                        .setStatus(AiConnectorOperationDO.STATUS_PUBLISHED)
                        .setVersion(0));
        assertThatThrownBy(() -> executor.execute(request(Map.of("id", "A-1"))))
                .as("SSRF 防线：目标必须与连接器 Origin 一致")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_ORIGIN_MISMATCH));
    }

    @Test
    void paginationStopsOnRepeatedCursorAndMarksPartialAtPageLimit() {
        // 重复游标：第二页返回同一个游标 → 立即停止（不再翻页）
        stubOperation(
                AiConnectorOperationDO.STATUS_PUBLISHED,
                "{\"type\":\"CURSOR\",\"maxPages\":5,\"cursorParam\":\"cursor\",\"cursorPath\":\"next\"}");
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenReturn(json("{\"next\":\"c1\",\"data\":{\"items\":[1]}}"))
                .thenReturn(json("{\"next\":\"c1\",\"data\":{\"items\":[2]}}"));

        AiConnectorExecutionResultDTO repeated = executor.execute(request(Map.of("id", "A-1")));
        assertThat(repeated.getStatus()).isEqualTo("COMPLETE");
        assertThat(repeated.getStoppedReason()).isEqualTo("repeated-cursor");
        assertThat(repeated.getPages()).isEqualTo(2);
        assertThat(repeated.getItemCount()).isEqualTo(2);

        // 页数上限：每页都有新游标 → 达到上限时结论为 PARTIAL（不是"取完了"）
        stubOperation(
                AiConnectorOperationDO.STATUS_PUBLISHED,
                "{\"type\":\"CURSOR\",\"maxPages\":2,\"cursorParam\":\"cursor\",\"cursorPath\":\"next\"}");
        List<ExternalHttpResponse> pages = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            pages.add(json("{\"next\":\"c" + index + "\",\"data\":{\"items\":[" + index + "]}}"));
        }
        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(pages.get(0), pages.get(1), pages.get(2));

        AiConnectorExecutionResultDTO partial = executor.execute(request(Map.of("id", "A-1")));
        assertThat(partial.getStatus()).isEqualTo("PARTIAL");
        assertThat(partial.getStoppedReason()).isEqualTo("page-limit");
        assertThat(partial.getPages()).as("不超过声明的页数上限").isEqualTo(2);
    }

    @Test
    void rejectsDisabledConnectorDraftOperationAndUpstreamFailures() {
        stubOperation(AiConnectorOperationDO.STATUS_DRAFT, "{\"type\":\"NONE\",\"maxPages\":1}");
        assertThatThrownBy(() -> executor.execute(request(Map.of("id", "A-1"))))
                .as("草稿不可执行")
                .satisfies(
                        exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED));

        when(operationMapper.selectByKey(CONNECTOR_ID, "getOrder")).thenReturn(null);
        assertThatThrownBy(() -> executor.execute(request(Map.of("id", "A-1"))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND));

        stubOperation(AiConnectorOperationDO.STATUS_PUBLISHED, "{\"type\":\"NONE\",\"maxPages\":1}");
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector().setStatus(AiConnectorDO.STATUS_DISABLED));
        assertThatThrownBy(() -> executor.execute(request(Map.of("id", "A-1"))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_CONNECTOR_DISABLED));

        // 上游/策略失败：FAILED + 稳定原因码（不含上游正文）
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector());
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.TARGET_NOT_ALLOWED, "目标未允许"));
        AiConnectorExecutionResultDTO failed = executor.execute(request(Map.of("id", "A-1")));
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getDetailCode()).isEqualTo("TARGET_NOT_ALLOWED");

        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenReturn(new ExternalHttpResponse(503, Map.of(), new byte[0], 0));
        assertThat(executor.execute(request(Map.of("id", "A-1"))).getDetailCode())
                .isEqualTo("HTTP_503");

        // 重定向不跟随：3xx 必须报失败，不能当成"空页 → 已取完"
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenReturn(
                        new ExternalHttpResponse(302, Map.of("Location", "https://evil.example.com"), new byte[0], 0));
        AiConnectorExecutionResultDTO redirected = executor.execute(request(Map.of("id", "A-1")));
        assertThat(redirected.getStatus()).isEqualTo("FAILED");
        assertThat(redirected.getDetailCode()).isEqualTo("HTTP_302");
        assertThat(redirected.getItems()).isEmpty();
    }
}
