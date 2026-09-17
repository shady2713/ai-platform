package com.basicframework.module.ai.service.application;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;

/**
 * AI 应用与客户端凭据服务（A01）。
 *
 * <p>约束：
 * <ul>
 *   <li>{@code appCode} 全局唯一且创建后不可修改；重复直接 409；</li>
 *   <li>Origin 只接受精确来源（无路径、无通配），写入前归一化；</li>
 *   <li>秘密只保存 SHA-256 摘要：明文仅在创建/轮换结果里出现一次，接口与日志永不回显；</li>
 *   <li>轮换与吊销默认**无重叠**：旧凭据立即失效；吊销后旧秘密立刻无法换票。</li>
 * </ul>
 */
public interface AiApplicationService {

    /** 创建应用并签发首个凭据；返回结果里携带一次性明文秘密。 */
    AiApplicationCredentialIssueDTO createApplication(AiApplicationSaveDTO saveDTO);

    /** 修改应用（名称、说明、Origin、启用状态之外的可变字段）；appCode 不可修改。 */
    void updateApplication(AiApplicationSaveDTO saveDTO);

    /** 启用/停用应用；停用后所有已签发凭据都不能换票。 */
    void updateStatus(Long id, Integer version, Boolean enabled);

    /** 轮换客户端凭据：吊销全部旧凭据并签发新凭据（默认无重叠）。 */
    AiApplicationCredentialIssueDTO rotateCredential(Long id, Integer version);

    /** 吊销当前凭据但不签发新凭据（对接方主动下线）。 */
    void revokeCredential(Long id, Integer version);

    /** 删除应用：必须先吊销全部凭据，避免留下无法归属的有效秘密。 */
    void deleteApplication(Long id, Integer version);

    AiApplicationDO getApplication(Long id);

    /** 是否存在可用（ACTIVE）凭据：管理端只展示这个布尔值，不返回摘要或明文。 */
    boolean hasActiveCredential(Long applicationId);

    PageResult<AiApplicationDO> getApplicationPage(PageParam pageParam, String appCode, Boolean enabled);

    /**
     * 校验客户端凭据（换票入口使用）：应用存在且启用、凭据为 ACTIVE、摘要匹配三者同时满足才通过；
     * 任一不满足都返回同一个 401 语义错误，不区分"应用不存在/未启用/凭据错误/已吊销"以防枚举。
     *
     * @return 通过校验的应用
     */
    AiApplicationDO authenticate(String appCode, String secret);
}
