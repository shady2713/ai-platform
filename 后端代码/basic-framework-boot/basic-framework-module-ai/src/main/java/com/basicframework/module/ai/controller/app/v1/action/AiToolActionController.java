package com.basicframework.module.ai.controller.app.v1.action;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.action.vo.AiToolActionConfirmReqVO;
import com.basicframework.module.ai.controller.app.v1.action.vo.AiToolActionPageReqVO;
import com.basicframework.module.ai.controller.app.v1.action.vo.AiToolActionRespVO;
import com.basicframework.module.ai.dal.dataobject.action.AiToolActionDO;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.tool.action.AiToolActionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具动作确认接口（D09，应用端）。
 *
 * <p>归属由**服务端会话身份**决定（应用 + 主体类型 + 外部用户标识），请求体不能自报主体：
 * 越权与不存在同语义（404/403 同码）；确认必须携带一次性挑战与原参数，
 * 改参数、换用户、过期都会被状态机挡住。执行只发生一次（CAS），重放不产生第二次副作用。
 */
@Tag(name = "AI 应用端 - 工具动作确认")
@RestController
@RequestMapping("/ai/action")
@Validated
@RequiredArgsConstructor
public class AiToolActionController {

    private final AiToolActionService actionService;

    private final AiConversationSubjectResolver subjectResolver;

    @PostMapping("/confirm")
    @Operation(summary = "确认工具动作（同一主体 + 同一挑战 + 同一参数；确认后执行一次）")
    @AuthenticatedOnly
    public CommonResult<AiToolActionRespVO> confirm(@Valid @RequestBody AiToolActionConfirmReqVO reqVO) {
        AiConversationSubject subject = currentSubject();
        return success(toRespVO(actionService.confirm(
                reqVO.getActionId(),
                subject.applicationId(),
                subject.subjectType().name(),
                subject.externalUserId(),
                reqVO.getChallenge(),
                reqVO.getArguments())));
    }

    @PostMapping("/reject")
    @Operation(summary = "拒绝工具动作（终态，不执行）")
    @AuthenticatedOnly
    public CommonResult<AiToolActionRespVO> reject(@Valid @RequestBody AiToolActionConfirmReqVO reqVO) {
        AiConversationSubject subject = currentSubject();
        return success(toRespVO(actionService.reject(
                reqVO.getActionId(),
                subject.applicationId(),
                subject.subjectType().name(),
                subject.externalUserId(),
                reqVO.getChallenge())));
    }

    @PostMapping("/execute")
    @Operation(summary = "执行已确认的工具动作（只有 CONFIRMED 能执行一次）")
    @AuthenticatedOnly
    public CommonResult<AiToolActionRespVO> execute(
            @Parameter(description = "动作编号", required = true) @RequestParam("actionId") @NotNull @Positive
                    Long actionId) {
        AiConversationSubject subject = currentSubject();
        return success(toRespVO(actionService.execute(
                actionId, subject.applicationId(), subject.subjectType().name(), subject.externalUserId())));
    }

    @GetMapping("/get")
    @Operation(summary = "查询工具动作（越权与不存在同语义）")
    @AuthenticatedOnly
    public CommonResult<AiToolActionRespVO> get(
            @Parameter(description = "动作编号", required = true) @RequestParam("actionId") @NotNull @Positive
                    Long actionId) {
        AiConversationSubject subject = currentSubject();
        return success(toRespVO(actionService.getAction(
                actionId, subject.applicationId(), subject.subjectType().name(), subject.externalUserId())));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询工具动作（只返回当前主体的动作）")
    @AuthenticatedOnly
    public CommonResult<PageResult<AiToolActionRespVO>> page(@Valid AiToolActionPageReqVO pageReqVO) {
        AiConversationSubject subject = currentSubject();
        PageResult<AiToolActionDO> page = actionService.getActionPage(
                subject.applicationId(),
                subject.subjectType().name(),
                subject.externalUserId(),
                pageReqVO.getRunId(),
                pageReqVO);
        return success(new PageResult<>(
                page.getList().stream().map(AiToolActionController::toRespVO).toList(), page.getTotal()));
    }

    /** 当前主体：缺失即未认证（由 @AuthenticatedOnly 保证已认证，这里是防御性判定）。 */
    private AiConversationSubject currentSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_ACCESS_DENIED));
    }

    private static AiToolActionRespVO toRespVO(AiToolActionDO action) {
        return new AiToolActionRespVO()
                .setId(action.getId())
                .setRunId(action.getRunId())
                .setToolId(action.getToolId())
                .setToolVersionId(action.getToolVersionId())
                .setStatus(action.getStatus())
                .setExpiresAt(action.getExpiresAt())
                .setDecidedAt(action.getDecidedAt())
                .setExecutedAt(action.getExecutedAt())
                .setResultCode(action.getResultCode())
                .setVersion(action.getVersion());
    }
}
