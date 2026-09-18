package com.basicframework.module.ai.service.auth;

import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.auth.dto.AiTicketContextDTO;
import com.basicframework.module.ai.service.auth.dto.AiTicketIssueDTO;
import java.util.List;

/**
 * 访问票据服务（A04）：客户端凭据换票与票据校验。
 *
 * <p>安全约束：
 * <ul>
 *   <li>换票必须出示应用客户端凭据与可信外部用户断言（浏览器单靠 Origin 换不到任意用户票据）；</li>
 *   <li>token 为 32 字节随机值，库中只存 SHA-256 摘要，明细只在响应出现一次；</li>
 *   <li>票据范围是**裁剪后**的白名单：请求越界的资源被裁掉，裁剪后为空则拒绝签发；</li>
 *   <li>短期有效；校验时重新读取应用与主体状态，因此撤销应用/主体后未过期票据同样失效；</li>
 *   <li>不缓存换票响应，也不缓存校验结果。</li>
 * </ul>
 */
public interface AiTicketService {

    /**
     * 换票。
     *
     * @param appCode        应用标识
     * @param appSecret      应用客户端秘密
     * @param subjectType    主体类型（APP/USER）
     * @param externalUserId 可信外部用户断言（USER 主体必填）
     * @param resourceHints  本次需要的对象标识（用于裁剪范围；为空表示只取组织范围）
     */
    AiTicketIssueDTO issue(
            String appCode,
            String appSecret,
            AiSubjectType subjectType,
            String externalUserId,
            List<String> resourceHints);

    /** 校验票据并返回服务端上下文；无效/过期/已撤销/应用或主体不可用都抛 401 语义错误。 */
    AiTicketContextDTO verify(String token);

    /** 撤销某主体当前的全部票据（主体撤销时调用）。 */
    void revokeTickets(Long applicationId, AiSubjectType subjectType, String externalUserId);

    /** 撤销某应用当前的全部票据（应用撤销时调用，不区分主体）。 */
    void revokeTicketsOfApplication(Long applicationId);

    /**
     * 清理已失效票据（A06）：撤销的或过期超过保留期的票据按批逻辑删除。
     *
     * <p>幂等：重复执行不会产生额外副作用；每批只处理 {@code batchSize} 条，最多 {@code maxBatches} 批。
     *
     * @return 实际清理条数
     */
    int cleanInvalidTickets(int batchSize, int maxBatches, java.time.Duration retention);
}
