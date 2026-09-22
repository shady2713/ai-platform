package com.basicframework.module.ai.service.knowledge;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;

/**
 * 知识库管理（K02）：共享/应用专用、嵌入模型与维度、启停与删除保护。
 *
 * <p>服务层只返回 DO / 服务 DTO（协议层负责转 VO）。
 *
 * <p>删除保护有两条独立规则：被服务绑定引用（`ai_service_resource`，资源类型 KNOWLEDGE_BASE）
 * 不能删除；仍有存活文档不能删除。二者都**拒绝**而不是级联——级联删除会让历史会话的引用与
 * 审计线索指向不存在的库。
 */
public interface AiKnowledgeBaseService {

    /** 创建知识库（标识唯一；嵌入模型与维度创建后不可修改）。 */
    Long create(AiKnowledgeBaseSaveDTO saveDTO);

    /** 修改知识库（只允许名称/说明/管理者/保留策略）。 */
    void update(AiKnowledgeBaseSaveDTO saveDTO);

    /** 启用/停用（停用后不接受新入库与索引换代）。 */
    void updateStatus(Long id, Integer version, Boolean enabled);

    /** 删除（被服务引用或仍有文档时拒绝）。 */
    void delete(Long id, Integer version);

    /** 查询（不存在抛 404）。 */
    AiKnowledgeBaseDO getKnowledgeBase(Long id);

    /** 按标识查询（消费者按标识解析；不存在抛 404）。 */
    AiKnowledgeBaseDO getByCode(String code);

    /** 要求启用态（入库/索引换代的前置校验）。 */
    AiKnowledgeBaseDO requireEnabled(Long id);

    /** 分页查询。 */
    PageResult<AiKnowledgeBaseDO> getKnowledgeBasePage(
            PageParam pageParam, String visibility, Long ownerApplicationId, String status);
}
