package com.basicframework.module.ai.service.connector;

import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlObjectMetadata;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlPoolStateDTO;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryRequest;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import java.util.List;

/**
 * 独立 MySQL 只读连接器（D03）：数据集（D04）与 SQL 编译（D06）使用的唯一入口。
 *
 * <p>本接口是**服务层**：调用方只给连接器编号与查询，秘密解密、启用态校验、错误码映射都在实现里完成；
 * 调用方拿不到连接池、连接串与凭据。
 */
public interface AiMysqlConnectorService {

    /** 发现授权对象的元数据（白名单外一律不可见）。 */
    List<AiMysqlObjectMetadata> discoverObjects(Long connectorId);

    /** 在只读连接上执行单条查询（受行数/超时/白名单约束）。 */
    AiMysqlQueryResultDTO execute(Long connectorId, AiMysqlQueryRequest request);

    /** 取消在途查询（按查询句柄）；返回是否找到并请求了取消。 */
    boolean cancel(String handleId);

    /** 在途查询句柄（可观测：据此取消正在跑的查询）。 */
    List<String> inFlightHandles();

    /** 只读池资源状态（用于验证连接归还与池关闭）。 */
    AiMysqlPoolStateDTO poolState(Long connectorId);

    /** 关闭连接器的只读池（停用/删除时调用）。 */
    boolean closePool(Long connectorId);
}
