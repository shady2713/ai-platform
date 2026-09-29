package com.basicframework.module.ai.service.authorization;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.authorization.dto.AiSubjectFederationSubmitDTO;
import java.util.List;

/**
 * 跨系统主体联邦映射服务（Y01）。
 *
 * <p>它回答的问题是"**谁**在另一个业务系统里就是**谁**"——这是跨系统分析的前置事实，因此：
 * <ul>
 *   <li><b>显式登记 + 独立审批</b>：映射以 {@code PENDING} 提交，由**另一位**操作员批准后才生效；
 *       提交人不能批准自己的申请（{@code AI_SUBJECT_FEDERATION_APPROVER_CONFLICT}）；</li>
 *   <li><b>不按同名推断</b>：两个应用里 externalUserId 相同的用户不会自动成为同一主体，
 *       调用方必须逐对登记（本服务只接受服务端已登记且 ACTIVE 的主体）；</li>
 *   <li><b>撤销立即生效</b>：{@link #revoke} 后，此后任何一次授权发现都不再包含目标系统
 *       （发现路径每次都读库，不缓存，也没有常驻扫描任务）；</li>
 *   <li><b>事实可核验</b>：每次批准/撤销递增映射版本，范围选择的指纹把它纳入计算。</li>
 * </ul>
 */
public interface AiSubjectFederationService {

    /**
     * 登记一对跨系统身份映射（状态 PENDING，等待独立审批）。
     *
     * <p>同一身份对已有 PENDING/APPROVED 映射时返回 409；已撤销的映射允许重新提交（复用该行并
     * 清空上一次审批痕迹）。
     *
     * @return 映射编号
     */
    Long submit(AiSubjectFederationSubmitDTO submitDTO);

    /** 独立审批通过（PENDING → APPROVED）；批准人必须不同于提交人。 */
    void approve(Long id, Integer version, String approvalNote);

    /** 撤销映射（立即不再参与发现）；对已撤销的映射幂等成功。 */
    void revoke(Long id, Integer version);

    /** 按编号读取映射；不存在返回 404 语义错误。 */
    AiSubjectFederationDO getFederation(Long id);

    /** 管理端分页（按来源应用与状态过滤）。 */
    PageResult<AiSubjectFederationDO> getFederationPage(PageParam pageParam, Long sourceApplicationId, String status);

    /**
     * 某来源主体**已批准**的映射列表（授权发现的唯一入口）。
     *
     * @param sourceExternalUserId 来源主体外部用户标识（APP 主体传空串或 null）
     */
    List<AiSubjectFederationDO> listApprovedTargets(
            Long sourceApplicationId, AiSubjectType sourceSubjectType, String sourceExternalUserId);
}
