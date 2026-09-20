package com.basicframework.module.ai.service.connector.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.connector.importer.dto.AiConnectorOperationDraftDTO;
import com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO;
import org.junit.jupiter.api.Test;

/** D02 导入器：限定范围（GET/POST、本地 $ref、无 header 参数）、跳过项可审计、上限与失败分支。 */
class AiOpenApiImporterImplTest {

    private final AiOpenApiImporterImpl importer = new AiOpenApiImporterImpl();

    private static final String VALID_DOCUMENT =
            """
            {
              "openapi": "3.0.3",
              "info": {"title": "CRM", "version": "1.0"},
              "paths": {
                "/orders/{id}": {
                  "get": {
                    "operationId": "getOrder",
                    "summary": "查询订单",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}},
                      {"name": "tenant", "in": "query", "schema": {"type": "string"}},
                      {"name": "X-Trace", "in": "header", "schema": {"type": "string"}}
                    ]
                  }
                },
                "/orders": {
                  "post": {
                    "operationId": "createOrder",
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": {
                            "type": "object",
                            "required": ["orderId"],
                            "properties": {"orderId": {"type": "string"}, "amount": {"type": "number"}}
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    private static void assertInvalid(Throwable throwable) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CONNECTOR_IMPORT_INVALID.getCode());
    }

    @Test
    void importsGetAndPostOperationsWithDeclaredParameters() {
        AiOpenApiImportResultDTO result = importer.importDocument(VALID_DOCUMENT);

        assertThat(result.getOperations()).hasSize(2);
        AiConnectorOperationDraftDTO get = result.getOperations().get(0);
        assertThat(get.getOperationKey()).isEqualTo("getOrder");
        assertThat(get.getHttpMethod()).isEqualTo("GET");
        assertThat(get.getPathTemplate()).isEqualTo("/orders/{id}");
        assertThat(get.getParameters()).containsKeys("id", "tenant");
        assertThat(get.getParameters().get("id")).containsEntry("in", "path").containsEntry("required", true);
        assertThat(get.getParameters()).as("header 参数不导入（请求头只能来自连接器配置）").doesNotContainKey("X-Trace");
        assertThat(result.getSkipped()).anySatisfy(reason -> assertThat(reason).contains("忽略 header 参数"));

        AiConnectorOperationDraftDTO post = result.getOperations().get(1);
        assertThat(post.getHttpMethod()).isEqualTo("POST");
        assertThat(post.getParameters().get("orderId"))
                .containsEntry("in", "body")
                .containsEntry("required", true);
        assertThat(post.getParameters().get("amount")).containsEntry("type", "number");

        // 默认分页是"不翻页"，提取规则是整包（分页/提取必须显式声明，不做猜测）
        assertThat(get.getPagination()).containsEntry("type", "NONE");
        assertThat(get.getResponse()).containsEntry("rootPath", "");
    }

    @Test
    void skipsUnsupportedOperationsAndRecordsReasons() {
        String document =
                """
                {
                  "openapi": "3.0.3",
                  "paths": {
                    "/orders/{id}": {
                      "get": {"operationId": "getOrder",
                        "parameters": [{"$ref": "https://evil.example.com/params.json#/id"}]}
                    },
                    "/orders": {"delete": {"operationId": "deleteOrder"}, "put": {"operationId": "putOrder"}},
                    "/reports": {"get": {"operationId": "getReport"}}
                  }
                }
                """;

        AiOpenApiImportResultDTO result = importer.importDocument(document);

        assertThat(result.getOperations())
                .as("只有 /reports 的 GET 可导入")
                .extracting(AiConnectorOperationDraftDTO::getOperationKey)
                .containsExactly("getReport");
        assertThat(result.getSkipped())
                .anySatisfy(reason -> assertThat(reason).contains("外部 $ref"))
                .anySatisfy(reason -> assertThat(reason).contains("只支持 GET/POST"));
    }

    @Test
    void rejectsDocumentsWithoutImportableOperationsOrWithInvalidShape() {
        assertThatThrownBy(
                        () -> importer.importDocument(
                                """
                        {"openapi": "3.0.3", "paths": {"/x": {"delete": {"operationId": "del"}}}}"""))
                .as("没有可导入操作时报错，而不是静默导入 0 条")
                .satisfies(AiOpenApiImporterImplTest::assertInvalid);

        assertThatThrownBy(() -> importer.importDocument("not-json"))
                .satisfies(AiOpenApiImporterImplTest::assertInvalid);
        assertThatThrownBy(() -> importer.importDocument("{\"openapi\":\"3.0.3\"}"))
                .satisfies(AiOpenApiImporterImplTest::assertInvalid);
        assertThatThrownBy(() -> importer.importDocument(null)).satisfies(AiOpenApiImporterImplTest::assertInvalid);
        assertThatThrownBy(() -> importer.importDocument("x".repeat(512 * 1024 + 1)))
                .as("超大文档直接拒绝")
                .satisfies(AiOpenApiImporterImplTest::assertInvalid);
    }

    @Test
    void derivesOperationKeyWhenMissingAndRejectsUnsafePaths() {
        String document =
                """
                {
                  "openapi": "3.0.3",
                  "paths": {
                    "/orders/list": {"get": {"summary": "列表"}},
                    "/orders/../../admin": {"get": {"operationId": "unsafe"}}
                  }
                }
                """;

        AiOpenApiImportResultDTO result = importer.importDocument(document);

        assertThat(result.getOperations())
                .extracting(AiConnectorOperationDraftDTO::getOperationKey)
                .containsExactly("get_orders_list");
        assertThat(result.getSkipped()).anySatisfy(reason -> assertThat(reason).contains("路径模板不合法"));
    }
}
