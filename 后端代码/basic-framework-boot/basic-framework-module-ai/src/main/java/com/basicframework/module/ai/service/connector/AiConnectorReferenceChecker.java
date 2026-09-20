package com.basicframework.module.ai.service.connector;

import java.util.Optional;

/**
 * 连接器引用检查（D01）：删除前询问"谁还在用这个连接器"。
 *
 * <p>数据集（D04）、工具（D08）等消费方在各自卡片里实现本接口并注册为 Bean；
 * 任一实现报告引用，删除即被拒绝（409）。默认没有任何实现，因此新建连接器可以自由删除，
 * 一旦有消费方注册，引用保护自动生效——引用方不需要修改连接器服务。
 */
public interface AiConnectorReferenceChecker {

    /**
     * 检查引用。
     *
     * @param connectorId 连接器编号
     * @return 引用说明（例如"数据集 X 正在使用"）；无引用返回空
     */
    Optional<String> findReference(Long connectorId);
}
