package com.basicframework.module.ai.service.tool;

import java.util.Optional;

/**
 * 工具引用检查（D08）：删除前询问"谁还在用这个工具"。
 *
 * <p>服务发布版本、分析步骤等消费方在各自卡片里实现并注册为 Bean；任一实现报告引用，
 * 删除即被拒绝（409）。默认没有实现，因此新建工具可以自由删除。
 */
public interface AiToolReferenceChecker {

    /**
     * 检查引用。
     *
     * @param toolId 工具编号
     * @return 引用说明；无引用返回空
     */
    Optional<String> findReference(Long toolId);
}
