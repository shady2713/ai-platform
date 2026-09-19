package com.basicframework.module.ai.controller.app.v1.conversation;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationBindReleaseReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationBindReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationCreateReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationMessageReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationMessageRespVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationPageReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationRenameReqVO;
import com.basicframework.module.ai.controller.app.v1.conversation.vo.AiConversationRespVO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 会话与消息接口（O01）。
 *
 * <p>归属完全由**服务端会话身份**（A05 写入的票据信息）决定：请求体不能自报应用或主体，
 * 越权访问与不存在同语义（404）。删除先关闭访问（会话状态 + 全部消息），
 * 正文的物理清理由 O06 的保留策略处理。消息正文属于受控业务数据，不进入日志。
 */
@Tag(name = "应用端 - AI 会话")
@RestController
@RequestMapping("/ai/conversation")
@Validated
@RequiredArgsConstructor
public class AiConversationController {

    private final AiConversationService conversationService;

    @PostMapping("/create")
    @Operation(summary = "创建会话（业务键在同一应用+主体内唯一）")
    @AuthenticatedOnly
    public CommonResult<Long> create(@Valid @RequestBody AiConversationCreateReqVO reqVO) {
        return success(conversationService.create(new AiConversationCreateDTO()
                .setConversationKey(reqVO.getConversationKey())
                .setTitle(reqVO.getTitle())
                .setServiceId(reqVO.getServiceId())
                .setBusinessContext(reqVO.getBusinessContext())));
    }

    @GetMapping("/page")
    @Operation(summary = "当前主体的会话分页（按编号倒序，翻页稳定）")
    @AuthenticatedOnly
    public CommonResult<PageResult<AiConversationRespVO>> page(@Valid AiConversationPageReqVO reqVO) {
        PageResult<AiConversationDO> page = conversationService.getPage(reqVO);
        return success(new PageResult<>(
                page.getList().stream()
                        .map(AiConversationController::toConversationRespVO)
                        .collect(Collectors.toList()),
                page.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "读取会话（非本人或不存在的会话同语义拒绝）")
    @Parameter(name = "id", description = "会话编号", required = true)
    @AuthenticatedOnly
    public CommonResult<AiConversationRespVO> get(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toConversationRespVO(conversationService.getConversation(id)));
    }

    @PutMapping("/rename")
    @Operation(summary = "重命名会话（乐观锁）")
    @AuthenticatedOnly
    public CommonResult<Boolean> rename(@Valid @RequestBody AiConversationRenameReqVO reqVO) {
        conversationService.rename(reqVO.getId(), reqVO.getTitle(), reqVO.getVersion());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除会话（先关闭访问：会话与消息立即不可读，正文由保留策略清理）")
    @AuthenticatedOnly
    public CommonResult<Boolean> delete(
            @Parameter(description = "会话编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version) {
        conversationService.delete(id, version);
        return success(true);
    }

    @PutMapping("/bind-service")
    @Operation(summary = "绑定服务（已固定发布版本后需显式新建或迁移会话）")
    @AuthenticatedOnly
    public CommonResult<Boolean> bindService(@Valid @RequestBody AiConversationBindReqVO reqVO) {
        conversationService.bindService(reqVO.getId(), reqVO.getServiceId(), reqVO.getVersion());
        return success(true);
    }

    @PutMapping("/bind-release")
    @Operation(summary = "固定发布版本（首个运行解析后写入，后续消息沿用该版本）")
    @AuthenticatedOnly
    public CommonResult<Boolean> bindRelease(@Valid @RequestBody AiConversationBindReleaseReqVO reqVO) {
        conversationService.bindRelease(reqVO.getId(), reqVO.getReleaseId(), reqVO.getVersion());
        return success(true);
    }

    @PostMapping("/message")
    @Operation(summary = "追加消息（角色与长度校验；序号在会话内递增）")
    @AuthenticatedOnly
    public CommonResult<Long> appendMessage(@Valid @RequestBody AiConversationMessageReqVO reqVO) {
        return success(conversationService.appendMessage(new AiConversationMessageSaveDTO()
                .setConversationId(reqVO.getConversationId())
                .setRole(reqVO.getRole())
                .setContent(reqVO.getContent())
                .setSourceRunId(reqVO.getSourceRunId())));
    }

    @GetMapping("/messages")
    @Operation(summary = "会话消息（按序号升序，afterSequence 之后最多 limit 条）")
    @AuthenticatedOnly
    public CommonResult<List<AiConversationMessageRespVO>> messages(
            @Parameter(description = "会话编号", required = true) @RequestParam("conversationId") @NotNull @Positive
                    Long conversationId,
            @Parameter(description = "起始序号（不含）")
                    @RequestParam(value = "afterSequence", required = false)
                    @PositiveOrZero
                    Integer afterSequence,
            @Parameter(description = "条数上限（1-100，缺省 20）")
                    @RequestParam(value = "limit", required = false)
                    @Min(1)
                    @Max(100)
                    Integer limit) {
        return success(conversationService.listMessages(conversationId, afterSequence, limit).stream()
                .map(AiConversationController::toMessageRespVO)
                .collect(Collectors.toList()));
    }

    private static AiConversationRespVO toConversationRespVO(AiConversationDO conversation) {
        return new AiConversationRespVO()
                .setId(conversation.getId())
                .setConversationKey(conversation.getConversationKey())
                .setTitle(conversation.getTitle())
                .setServiceId(conversation.getServiceId())
                .setReleaseId(conversation.getReleaseId())
                .setBusinessContext(conversation.getBusinessContext())
                .setMessageCount(conversation.getMessageCount())
                .setLastMessageTime(conversation.getLastMessageTime())
                .setStatus(conversation.getStatus())
                .setVersion(conversation.getVersion())
                .setCreateTime(conversation.getCreateTime());
    }

    private static AiConversationMessageRespVO toMessageRespVO(AiConversationMessageDO message) {
        return new AiConversationMessageRespVO()
                .setId(message.getId())
                .setConversationId(message.getConversationId())
                .setSequenceNo(message.getSequenceNo())
                .setRole(message.getRole())
                .setContent(message.getContent())
                .setContentHash(message.getContentHash())
                .setSourceRunId(message.getSourceRunId())
                .setCreateTime(message.getCreateTime());
    }
}
