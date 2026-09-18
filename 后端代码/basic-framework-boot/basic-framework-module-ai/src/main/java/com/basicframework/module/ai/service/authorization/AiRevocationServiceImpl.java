package com.basicframework.module.ai.service.authorization;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 撤销命令实现（A06）。
 *
 * <p>授权目录通过 {@link AiResourceGrantService} 的公开方法分页遍历并逐条撤销
 * （每条都递增 authz revision，使基于旧版本的判定与范围指纹立即失效）；
 * 票据撤销由 {@link AiTicketService} 完成（应用维度或主体维度）。
 * 全过程在一个事务内完成：要么全部失效，要么都不变。
 */
@Service
@RequiredArgsConstructor
public class AiRevocationServiceImpl implements AiRevocationService {

    /** 撤销遍历的分页大小：撤销是低频管理动作，按常规分页上限遍历。 */
    private static final int REVOKE_PAGE_SIZE = 100;

    private final AiApplicationService applicationService;

    private final AiSubjectService subjectService;

    private final AiResourceGrantService grantService;

    private final AiTicketService ticketService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeApplication(Long applicationId, Integer version) {
        if (applicationId == null || version == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        // 1) 停用应用：后续 authenticate / 范围解析立即拒绝
        applicationService.updateStatus(applicationId, version, false);
        // 2) 撤销该应用名下的全部授权（所有主体）
        revokeGrants(applicationId, null, null);
        // 3) 撤销该应用的全部票据（不区分主体）
        ticketService.revokeTicketsOfApplication(applicationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeSubject(
            Long applicationId, AiSubjectType subjectType, String externalUserId, Integer subjectVersion) {
        if (applicationId == null || subjectType == null || subjectVersion == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        String normalizedExternalUserId = subjectType == AiSubjectType.APP ? "" : externalUserId;
        // 1) 停用主体
        subjectService.disableSubject(applicationId, subjectType, normalizedExternalUserId);
        // 2) 撤销该主体的全部授权
        revokeGrants(applicationId, subjectType.name(), normalizedExternalUserId);
        // 3) 撤销该主体的全部票据
        ticketService.revokeTickets(applicationId, subjectType, normalizedExternalUserId);
    }

    /**
     * 分页遍历并逐条撤销授权。
     *
     * <p>已撤销的记录仍会出现在分页结果中（状态置 REVOKED 而非删除），因此用 total 判断是否还有下一页。
     */
    private void revokeGrants(Long applicationId, String subjectType, String externalUserId) {
        int pageNo = 1;
        while (true) {
            PageResult<AiResourceGrantDO> page =
                    grantService.getGrantPage(page(pageNo), applicationId, subjectType, externalUserId, null);
            if (page.getList().isEmpty()) {
                return;
            }
            for (AiResourceGrantDO grant : page.getList()) {
                if (AiResourceGrantDO.STATUS_ACTIVE.equals(grant.getStatus())) {
                    grantService.revokeGrant(grant.getId(), grant.getVersion());
                }
            }
            if ((long) pageNo * REVOKE_PAGE_SIZE >= page.getTotal()) {
                return;
            }
            pageNo++;
        }
    }

    private static PageParam page(int pageNo) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo);
        pageParam.setPageSize(REVOKE_PAGE_SIZE);
        return pageParam;
    }
}
