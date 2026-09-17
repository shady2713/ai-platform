package com.basicframework.module.ai.service.authorization;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import java.util.Set;

/** 资源授权目录管理（A03）：新增/更新授权、撤销授权（递增 authz revision）与查询。 */
public interface AiResourceGrantService {

    Long createGrant(
            Long applicationId,
            String subjectType,
            String externalUserId,
            String resourceType,
            String resourceKey,
            Set<String> actions);

    void updateGrant(Long id, Integer version, Set<String> actions);

    /** 撤销授权：状态置 REVOKED 且 authz revision 递增，后续判定立即拒绝。 */
    void revokeGrant(Long id, Integer version);

    AiResourceGrantDO getGrant(Long id);

    PageResult<AiResourceGrantDO> getGrantPage(
            PageParam pageParam, Long applicationId, String subjectType, String externalUserId, String resourceType);
}
