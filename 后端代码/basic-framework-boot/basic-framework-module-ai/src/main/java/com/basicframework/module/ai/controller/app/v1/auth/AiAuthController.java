package com.basicframework.module.ai.controller.app.v1.auth;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.module.ai.controller.app.v1.auth.vo.AiTicketReqVO;
import com.basicframework.module.ai.controller.app.v1.auth.vo.AiTicketRespVO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.auth.AiTicketAttemptThrottle;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.auth.dto.AiTicketIssueDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 应用端换票接口（A04）。
 *
 * <p>换票必须携带应用客户端凭据；错误一律收敛为同一 401 语义（不可枚举），并按客户端 IP 限流
 * （防暴力尝试）。响应不缓存（票据本身短期有效且服务端只存摘要）。
 */
@Tag(name = "应用端 - AI 换票")
@RestController
@RequestMapping("/ai/auth")
@Validated
@RequiredArgsConstructor
public class AiAuthController {

    private final AiTicketService ticketService;

    private final AiTicketAttemptThrottle attemptThrottle;

    @PostMapping("/ticket")
    @Operation(summary = "用应用客户端凭据换取访问票据（响应携带一次性票据明文）")
    // 换票入口本身以**客户端凭据**鉴权（appCode + appSecret），因此不属于管理端权限面；
    // 显式声明为匿名可见入口，配合失败节流与统一 401 语义防止枚举与暴力尝试。
    @PermitAll
    public CommonResult<AiTicketRespVO> issueTicket(@Valid @RequestBody AiTicketReqVO reqVO) {
        String clientKey = clientKey(reqVO.getAppCode());
        // 失败节流：先判定是否已被限制，再尝试换票
        attemptThrottle.checkAllowed(clientKey);
        AiTicketIssueDTO issue;
        try {
            issue = ticketService.issue(
                    reqVO.getAppCode(),
                    reqVO.getAppSecret(),
                    AiSubjectType.valueOf(reqVO.getSubjectType()),
                    reqVO.getExternalUserId(),
                    reqVO.getResourceKeys());
        } catch (RuntimeException exception) {
            // 只累计失败次数，不记录异常正文（凭据错误与业务拒绝共用同一处理）
            attemptThrottle.recordFailure(clientKey);
            throw exception;
        }
        attemptThrottle.recordSuccess(clientKey);
        return success(new AiTicketRespVO()
                .setToken(issue.getToken())
                .setExpiresTime(issue.getExpiresTime())
                .setOrganizationIds(issue.getOrganizationIds())
                .setResourceKeys(issue.getResourceKeys())
                .setScopeFingerprint(issue.getScopeFingerprint()));
    }

    /** 失败节流键：客户端 IP + 应用标识（无请求上下文时退化为应用标识）。 */
    private static String clientKey(String appCode) {
        String clientIp;
        try {
            clientIp = ServletUtils.getClientIP();
        } catch (RuntimeException exception) {
            clientIp = "unknown";
        }
        return clientIp + "|" + appCode;
    }
}
