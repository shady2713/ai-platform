package com.basicframework.module.ai.service.serviceconfig;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceCapabilityDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import java.util.List;

/**
 * AI 服务草稿与资源绑定（S01）。
 *
 * <p>约束：
 * <ul>
 *   <li>草稿编辑带乐观锁：并发编辑返回 409（{@code AI_STATE_CONFLICT}）；</li>
 *   <li>资源绑定必须**同时**满足：资源类型/动作在目录词汇内、所属应用对该资源有 ACTIVE 授权；
 *       任一不满足即拒绝（越权绑定拒绝），且不写入任何绑定；</li>
 *   <li>标记为可发布（READY）前必须满足能力条件：端点探测确认的能力覆盖服务所需能力，
 *       否则不得保存为可发布（草稿仍可保存）；</li>
 *   <li>服务层不依赖协议层 VO（Controller 负责 VO↔DTO）。</li>
 * </ul>
 */
public interface AiServiceService {

    /** 创建服务草稿（初始状态 DRAFT）。 */
    Long createDraft(AiServiceSaveDTO saveDTO);

    /** 修改服务草稿（乐观锁；配置变更递增 draftRevision）。 */
    void updateDraft(AiServiceSaveDTO saveDTO);

    /** 删除服务草稿（已归档或不存在时拒绝）。 */
    void deleteDraft(Long id, Integer version);

    /** 标记为可发布（READY）：能力与资源都必须满足，否则拒绝。 */
    void markReady(Long id, Integer version);

    /** 校验当前草稿的能力与资源是否满足发布条件（只读，供管理端提示）。 */
    AiServiceCapabilityDTO checkCapabilities(Long id);

    /** 绑定资源（越权或类型非法都拒绝）。 */
    Long bindResource(AiServiceResourceSaveDTO saveDTO);

    /** 解绑资源（乐观锁）。 */
    void unbindResource(Long bindingId, Integer version);

    /** 服务的草稿绑定列表。 */
    List<AiServiceResourceDO> listDraftBindings(Long serviceId);

    /** 服务的发布版本（倒序）。 */
    List<AiServiceReleaseDO> listReleases(Long serviceId);

    AiServiceDO getService(Long id);

    PageResult<AiServiceDO> getServicePage(PageParam pageParam, Long appId, String code, String status);
}
