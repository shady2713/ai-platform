package com.basicframework.module.ai.service.query.compiler;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_COMPILE_FAILED;

import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryRequest;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 编译结果执行器（D06）：把 {@link CompiledQuery} 交给 D03 的只读执行链路。
 *
 * <p>为什么不让编译器自己执行：编译是纯函数（可快照、可离线比对），执行需要连接、超时与取消语义。
 * 这里只做"编译结果 → 只读查询请求"的翻译：SQL 文本与绑定值按序传递，
 * 行数/超时预算取编译结果（调用方不能放大），实际连接仍由 D03 的池与只读账号负责
 * （D03 的 SQL 守卫会再校验一次来源对象与只读性——两道防线互不替代）。
 */
@Service
@RequiredArgsConstructor
public class AiCompiledQueryExecutor {

    private final AiMysqlConnectorService mysqlConnectorService;

    /** 在连接器上执行编译结果（只读、参数化、有界）。 */
    public AiMysqlQueryResultDTO execute(Long connectorId, CompiledQuery query) {
        if (query == null) {
            throw exception(AI_QUERY_COMPILE_FAILED);
        }
        return mysqlConnectorService.execute(
                connectorId,
                new AiMysqlQueryRequest(query.sql(), query.parameterValues(), query.maxRows(), query.timeoutMillis()));
    }
}
