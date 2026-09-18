package com.basicframework.module.ai.adapter.file;

import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.infra.api.file.FileBusinessAccessProvider;
import com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext;
import lombok.RequiredArgsConstructor;

/**
 * AI 业务文件授权 Provider（A07）：实现 infra 的 {@link FileBusinessAccessProvider}，为 AI 业务对象做归属判定。
 *
 * <p>安全语义：
 * <ul>
 *   <li>判定输入只有"文件 + 业务标识 + 主体（用户类型/编号）"——**管理权限不会传入**，
 *       因此不存在 {@code canManageFiles} 伪装成业务授权的路径；</li>
 *   <li>主体（MEMBER 票据编号）先回查为可信 AI 身份（票据必须有效），解析失败即拒绝；</li>
 *   <li>报表/知识库：按 A03 当前授权判定；会话附件：仅所有者；未知业务类型一律拒绝；</li>
 *   <li>{@code canDelete} 只允许所有者（并且管理权限同样不参与），默认拒绝保持不变。</li>
 * </ul>
 */
@RequiredArgsConstructor
public class AiFileBusinessAccessProvider implements FileBusinessAccessProvider {

    private final String businessType;

    private final AiFileBindingMapper bindingMapper;

    private final AiFileSubjectResolver subjectResolver;

    private final AiAuthorizationService authorizationService;

    /** 该 Provider 关注的资源类型（仅所有者类型为 null）。 */
    private final AiResourceType governedResourceType;

    @Override
    public String getBusinessType() {
        return businessType;
    }

    @Override
    public boolean canRead(FileBusinessAccessContext context) {
        Resolved resolved = resolve(context);
        if (resolved == null) {
            return false;
        }
        if (governedResourceType == null) {
            // 仅所有者类型（会话附件）：必须所有者本人
            return resolved.subject.sameAs(resolved.owner);
        }
        return authorizationService
                .authorize(
                        resolved.subject.applicationId(),
                        resolved.subject.subjectType().name(),
                        resolved.subject.externalUserId(),
                        governedResourceType,
                        resolved.binding.getBusinessKey(),
                        AiAction.READ,
                        java.util.List.of(resolved.binding.getBusinessKey()))
                .isAllowed();
    }

    @Override
    public boolean canDelete(FileBusinessAccessContext context) {
        Resolved resolved = resolve(context);
        // 删除只允许所有者：管理权限与"能读"都不构成删除依据
        return resolved != null && resolved.subject.sameAs(resolved.owner);
    }

    private Resolved resolve(FileBusinessAccessContext context) {
        if (context == null || context.getFileId() == null || context.getSubject() == null) {
            return null;
        }
        // 只处理本 Provider 的业务类型；业务类型不符或未绑定一律拒绝
        if (!businessType.equals(context.getBusinessType())) {
            return null;
        }
        AiFileSubject subject = subjectResolver
                .resolveByTicket(context.getSubject().getUserId())
                .orElse(null);
        if (subject == null) {
            return null;
        }
        AiFileBindingDO binding = bindingMapper.selectActiveByFile(context.getFileId()).stream()
                .filter(active -> businessType.equals(active.getBusinessType()))
                .findFirst()
                .orElse(null);
        if (binding == null) {
            return null;
        }
        AiFileSubject.AiFileBindingOwner owner = new AiFileSubject.AiFileBindingOwner(
                binding.getApplicationId(), binding.getSubjectType(), binding.getExternalUserId());
        return new Resolved(binding, subject, owner);
    }

    private record Resolved(AiFileBindingDO binding, AiFileSubject subject, AiFileSubject.AiFileBindingOwner owner) {}
}
