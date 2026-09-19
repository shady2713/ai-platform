package com.basicframework.module.ai.service.serviceconfig;

import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseEvaluationDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import java.util.List;

/**
 * AI 服务发布（S02）：候选冻结、评测证据与别名切换。
 *
 * <p>约束：
 * <ul>
 *   <li><b>不可变</b>：发布版本的内容列（提示词、Schema、能力、端点、端点配置版本、评测门槛）
 *       在创建候选时冻结，此后只允许状态单向流转 CANDIDATE → ACTIVE → RETIRED；</li>
 *   <li><b>预检查</b>：发布前必须同时满足能力（探测确认覆盖所需）、资源（绑定生效且当前授权仍在）、
 *       Schema（可解析的 JSON 对象）与评测（最新结论匹配内容摘要与端点配置版本且得分达门槛）；</li>
 *   <li><b>别名切换</b>：切换在同一事务内以乐观锁完成（退役旧 ACTIVE → 激活候选），
 *       失败不改变当前 active；同一服务最多一条 ACTIVE；</li>
 *   <li><b>运行解析</b>：新运行解析到唯一 ACTIVE 版本，若依赖的绑定已解除或端点配置已变化则拒绝
 *       （旧版本不保留旧权限）。</li>
 * </ul>
 */
public interface AiServiceReleaseService {

    /**
     * 创建发布候选：冻结当前草稿内容与资源绑定，固定端点配置版本与评测门槛。
     *
     * @param serviceId    服务编号
     * @param draftVersion 草稿乐观锁版本（并发编辑时冲突）
     */
    Long createCandidate(Long serviceId, Integer draftVersion);

    /** 记录评测结论（绑定内容摘要 + 端点配置版本；通过与否由平台按冻结门槛判定）。 */
    Long recordEvaluation(AiServiceEvaluationSaveDTO saveDTO);

    /** 发布：预检查通过后在同一事务内切换别名（旧 ACTIVE 退役，候选转 ACTIVE）。 */
    void publish(Long releaseId, Integer releaseVersion);

    /** 停用：退役当前 ACTIVE 版本，服务不再对新运行开放（历史版本与评测记录保留）。 */
    void disable(Long serviceId, Integer serviceVersion);

    /** 发布预检查（只读）：返回未满足项，空列表表示可发布。 */
    List<String> checkPublishReadiness(Long releaseId);

    /** 某版本冻结的资源绑定快照（含已解除，倒序无保证，按编号升序）。 */
    List<AiServiceResourceDO> listReleaseBindings(Long releaseId);

    /** 某版本的评测记录（最新在前）。 */
    List<AiServiceReleaseEvaluationDO> listEvaluations(Long releaseId);

    /** 服务的发布版本（倒序）。 */
    List<AiServiceReleaseDO> listReleases(Long serviceId);

    /**
     * 新运行解析：返回唯一 ACTIVE 版本及其生效绑定。
     *
     * <p>任一条依赖绑定已解除、或端点当前配置版本与冻结值不一致时拒绝，绝不回退到草稿。
     */
    AiServiceRunSnapshotDTO resolveForNewRun(Long serviceId);
}
