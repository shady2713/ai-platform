package com.basicframework.module.ai.service.tool;

import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.service.connector.AiConnectorReferenceChecker;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具对连接器的引用保护（D08）：把"工具在用这个连接器"注册进 D01 的引用检查端口。
 *
 * <p>与数据集（D04）同一做法：连接器服务不需要认识工具，只要本 Bean 存在，
 * 删除被工具引用的连接器就会被拒绝（409）。
 */
@Component
@RequiredArgsConstructor
public class AiToolConnectorReferenceChecker implements AiConnectorReferenceChecker {

    private final AiToolMapper toolMapper;

    @Override
    public Optional<String> findReference(Long connectorId) {
        if (connectorId == null) {
            return Optional.empty();
        }
        List<AiToolDO> tools = toolMapper.selectByConnector(connectorId);
        if (tools.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("工具 " + tools.get(0).getCode() + " 正在使用该连接器");
    }
}
