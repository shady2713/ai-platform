package com.basicframework.module.ai.service.model;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import java.util.List;

/** AI 模型端点服务：非秘密配置走不可变版本，凭据单独轮换，引用后地址冻结。 */
public interface AiModelEndpointService {

    Long createEndpoint(AiModelEndpointSaveDTO saveDTO);

    void updateEndpoint(AiModelEndpointSaveDTO saveDTO);

    void updateEndpointStatus(Long id, Integer version, Boolean enabled);

    void rotateCredential(Long id, Integer version, String credential);

    void deleteEndpoint(Long id, Integer version);

    /** 标记端点已被发布服务引用：此后 provider/baseUrl 不可原地修改。 */
    void markReferenced(Long id, Integer version);

    AiModelEndpointDO getEndpoint(Long id);

    PageResult<AiModelEndpointDO> getEndpointPage(PageParam pageParam, String name, String provider);

    /** 不可变配置版本列表（倒序）。 */
    List<AiModelEndpointRevisionDO> getRevisions(Long endpointId);

    /** 取启用中的端点；停用或不存在都拒绝（供模型调用链路使用）。 */
    AiModelEndpointDO getEnabledEndpoint(Long id);

    /**
     * 记录并校验嵌入维度：首次调用记录观测值，之后**改变即拒绝**（409），
     * 保证已有向量索引不会被静默写入不同维度的向量。
     *
     * @param endpointId        端点编号
     * @param observedDimension 本次实际观测到的向量维度
     */
    void assertEmbeddingDimensionUnchanged(Long endpointId, Integer observedDimension);
}
