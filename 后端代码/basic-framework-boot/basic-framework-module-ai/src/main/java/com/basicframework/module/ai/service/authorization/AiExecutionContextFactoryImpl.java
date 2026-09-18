package com.basicframework.module.ai.service.authorization;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_AUTHORIZATION_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.domain.identity.AiExecutionContext;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 执行上下文重建实现（A06）：每次调用都重新解析主体范围。
 *
 * <p>实现里没有任何静态/线程级缓存：范围只来自当次解析结果，
 * 因此同一线程先后执行两个任务时，后一个任务不会继承前一个的身份或范围。
 */
@Service
@RequiredArgsConstructor
public class AiExecutionContextFactoryImpl implements AiExecutionContextFactory {

    private final AiApplicationService applicationService;

    private final AiSubjectService subjectService;

    @Override
    public AiExecutionContext rebuild(
            Long applicationId, AiSubjectType subjectType, String externalUserId, List<String> resourceHints) {
        if (applicationId == null || subjectType == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        // 应用被撤销/停用后，任何后续步骤都不得继续（撤销只停用主体是不够的）
        AiApplicationDO application = applicationService.getApplication(applicationId);
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            throw exception(AI_AUTHORIZATION_DENIED);
        }
        AiSubjectScopeDTO scope = subjectService.resolveScope(
                applicationId, subjectType, externalUserId, resourceHints == null ? List.of() : resourceHints);
        if (scope.isDenied()) {
            // 主体不可用、范围解析失败、空范围或超预算：任务不得继续执行
            throw exception(AI_AUTHORIZATION_DENIED);
        }
        return new AiExecutionContext(
                applicationId,
                subjectType.name(),
                scope.getExternalUserId(),
                scope.getOrganizationIds(),
                scope.getResourceKeys(),
                scope.getScopeSource(),
                scope.getScopeVersion());
    }
}
