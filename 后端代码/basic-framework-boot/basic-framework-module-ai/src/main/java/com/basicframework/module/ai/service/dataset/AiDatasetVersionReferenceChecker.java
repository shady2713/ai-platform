package com.basicframework.module.ai.service.dataset;

import java.util.Optional;

/**
 * 数据集版本引用检查（D04）：删除前询问"谁还在引用这个语义版本"。
 *
 * <p>报表（R04）等消费方在各自卡片里实现本接口并注册为 Bean；任一实现报告引用，
 * 数据集删除即被拒绝（409），从而保证"旧报表引用的版本可追溯"。
 * 默认没有任何实现，因此新建数据集可以自由删除。
 */
public interface AiDatasetVersionReferenceChecker {

    /**
     * 检查引用。
     *
     * @param datasetVersionId 数据集版本编号
     * @return 引用说明（例如"报表 X 的版本 3 正在使用"）；无引用返回空
     */
    Optional<String> findReference(Long datasetVersionId);
}
