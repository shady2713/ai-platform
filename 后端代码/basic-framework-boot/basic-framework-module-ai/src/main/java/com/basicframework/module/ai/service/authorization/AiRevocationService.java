package com.basicframework.module.ai.service.authorization;

import com.basicframework.module.ai.domain.identity.AiSubjectType;

/**
 * 撤销命令（A06）：把"应用撤销 / 主体撤销"做成一次原子操作。
 *
 * <p>撤销必须同时覆盖四条链路，缺一不可：
 * <ol>
 *   <li>应用/主体状态置为不可用（后续解析与判定直接拒绝）；</li>
 *   <li>撤销该应用/主体名下的**全部资源授权**（递增每条授权的 authz revision，旧指纹失效）；</li>
 *   <li>撤销其**全部票据**（旧票据立即不可认证，A05 的会话随之失效）；</li>
 *   <li>已发出的内容无法回收（见 {@code docs/security/ai-revocation-semantics.md}）——
 *       撤销只保证"后续步骤与产物读取被拒绝"。</li>
 * </ol>
 */
public interface AiRevocationService {

    /** 撤销应用：停用 + 撤销全部授权 + 撤销全部票据。 */
    void revokeApplication(Long applicationId, Integer version);

    /** 撤销主体：停用 + 撤销其全部授权 + 撤销其全部票据。 */
    void revokeSubject(Long applicationId, AiSubjectType subjectType, String externalUserId, Integer subjectVersion);
}
