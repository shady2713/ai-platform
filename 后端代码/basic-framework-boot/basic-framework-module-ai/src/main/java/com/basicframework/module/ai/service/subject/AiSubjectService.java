package com.basicframework.module.ai.service.subject;

import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.List;

/**
 * 外部主体与范围服务（A02）。
 *
 * <p>职责：
 * <ul>
 *   <li>登记/同步外部主体（APP/USER），维护唯一键与状态；业务侧撤销通过 {@link #disableSubject} 同步；</li>
 *   <li>范围版本随业务侧变化递增，保证中台能判断"缓存的范围是否过期"；</li>
 *   <li>{@link #resolveScope} 调用可信解析器并把失败/空集合/超预算统一收敛为 DENY。</li>
 * </ul>
 */
public interface AiSubjectService {

    /** 登记或更新主体身份（幂等）：已存在则更新显示名、状态与范围来源/版本。 */
    AiSubjectDO syncSubject(
            Long applicationId,
            AiSubjectType subjectType,
            String externalUserId,
            String displayName,
            String scopeSource,
            Long scopeVersion);

    /** 业务侧撤销：主体立即不可用（后续解析一律 DENY）。 */
    void disableSubject(Long applicationId, AiSubjectType subjectType, String externalUserId);

    /** 按唯一键取可用主体；不存在或已停用返回空。 */
    java.util.Optional<AiSubjectDO> findActiveSubject(
            Long applicationId, AiSubjectType subjectType, String externalUserId);

    /**
     * 解析主体范围：主体不可用、解析器缺失/失败、结果为空或超预算都返回 {@code denied=true}，
     * 绝不返回"全部数据"。
     *
     * @param applicationId  应用编号
     * @param subjectType    主体类型
     * @param externalUserId 可信外部用户标识
     * @param resourceHints  业务侧对象提示（服务端拼装）
     */
    AiSubjectScopeDTO resolveScope(
            Long applicationId, AiSubjectType subjectType, String externalUserId, List<String> resourceHints);
}
