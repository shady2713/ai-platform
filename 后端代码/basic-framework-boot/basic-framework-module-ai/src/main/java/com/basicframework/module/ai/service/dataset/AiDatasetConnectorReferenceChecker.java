package com.basicframework.module.ai.service.dataset;

import com.basicframework.module.ai.dal.dataobject.dataset.AiDatasetDO;
import com.basicframework.module.ai.dal.mysql.dataset.AiDatasetMapper;
import com.basicframework.module.ai.service.connector.AiConnectorReferenceChecker;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 数据集对连接器的引用保护（D04）：把"数据集在用这个连接器"注册进 D01 的引用检查端口。
 *
 * <p>这是 D01 预留的扩展点：连接器服务不需要认识数据集，只要本 Bean 存在，
 * 删除被数据集引用的连接器就会被拒绝（409），避免把还在使用的数据源删掉。
 */
@Component
@RequiredArgsConstructor
public class AiDatasetConnectorReferenceChecker implements AiConnectorReferenceChecker {

    private final AiDatasetMapper datasetMapper;

    @Override
    public Optional<String> findReference(Long connectorId) {
        if (connectorId == null) {
            return Optional.empty();
        }
        List<AiDatasetDO> datasets = datasetMapper.selectByConnector(connectorId);
        if (datasets.isEmpty()) {
            return Optional.empty();
        }
        AiDatasetDO first = datasets.get(0);
        return Optional.of("数据集 " + first.getCode() + " 正在使用该连接器");
    }
}
