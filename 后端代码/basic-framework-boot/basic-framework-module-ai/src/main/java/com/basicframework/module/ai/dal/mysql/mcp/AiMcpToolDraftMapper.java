package com.basicframework.module.ai.dal.mysql.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpToolDraftDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** MCP 工具草稿 Mapper（X07）。 */
@Mapper
public interface AiMcpToolDraftMapper extends BaseMapperX<AiMcpToolDraftDO> {

    /**
     * 按 (连接器, 上游工具名) 取草稿。
     *
     * <p>这是"上游工具身份"的唯一查法：唯一键 {@code (connector_id, upstream_tool_name, deleted)}
     * 在数据库层保证"一个上游工具只有一条草稿"，因此并发发现不会产生重复登记。
     */
    default AiMcpToolDraftDO selectByUpstream(Long connectorId, String upstreamToolName) {
        return selectOne(new LambdaQueryWrapper<AiMcpToolDraftDO>()
                .eq(AiMcpToolDraftDO::getConnectorId, connectorId)
                .eq(AiMcpToolDraftDO::getUpstreamToolName, upstreamToolName));
    }

    /** 按连接器列出全部草稿（按编号升序，便于人工逐条审阅）。 */
    default List<AiMcpToolDraftDO> selectByConnector(Long connectorId) {
        return selectList(new LambdaQueryWrapper<AiMcpToolDraftDO>()
                .eq(AiMcpToolDraftDO::getConnectorId, connectorId)
                .orderByAsc(AiMcpToolDraftDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiMcpToolDraftDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiMcpToolDraftDO>()
                        .eq(AiMcpToolDraftDO::getId, update.getId())
                        .eq(AiMcpToolDraftDO::getVersion, expectedVersion));
    }

    /**
     * 乐观锁 CAS，并**显式**把已审批指纹写成 NULL。
     *
     * <p>为什么必须单独一个方法：MyBatis-Plus 的 {@code update(entity, wrapper)} 默认
     * <b>忽略实体的 null 字段</b>，因此"置空已审批指纹"写在实体上不会生效——
     * 漂移阻断后 {@code approved_fingerprint} 会残留旧值，数据上看起来"仍然审批过"。
     * {@code LambdaUpdateWrapper.set(column, null)} 才会真正生成 {@code SET col = NULL}。
     */
    default int updateWithVersionClearingApproval(AiMcpToolDraftDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiMcpToolDraftDO>()
                        .eq(AiMcpToolDraftDO::getId, update.getId())
                        .eq(AiMcpToolDraftDO::getVersion, expectedVersion)
                        .set(AiMcpToolDraftDO::getApprovedFingerprint, null));
    }
}
