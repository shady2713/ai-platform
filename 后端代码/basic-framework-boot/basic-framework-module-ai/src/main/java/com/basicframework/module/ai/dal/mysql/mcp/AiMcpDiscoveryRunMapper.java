package com.basicframework.module.ai.dal.mysql.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpDiscoveryRunDO;
import org.apache.ibatis.annotations.Mapper;

/** MCP 发现运行留痕 Mapper（X07）。 */
@Mapper
public interface AiMcpDiscoveryRunMapper extends BaseMapperX<AiMcpDiscoveryRunDO> {

    /** 按连接器列出最近若干次发现（按编号倒序；用于运维回看"上次试了几次、为什么停"）。 */
    default java.util.List<AiMcpDiscoveryRunDO> selectRecent(Long connectorId, int limit) {
        return selectList(new LambdaQueryWrapper<AiMcpDiscoveryRunDO>()
                .eq(AiMcpDiscoveryRunDO::getConnectorId, connectorId)
                .orderByDesc(AiMcpDiscoveryRunDO::getId)
                .last("LIMIT " + limit));
    }
}
