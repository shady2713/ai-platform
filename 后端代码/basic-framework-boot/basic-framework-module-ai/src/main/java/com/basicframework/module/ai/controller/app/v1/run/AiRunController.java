package com.basicframework.module.ai.controller.app.v1.run;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunAcceptReqVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunAcceptRespVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunCancelReqVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunPageReqVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunRespVO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.service.event.AiRunEventService;
import com.basicframework.module.ai.service.event.dto.AiRunEventDTO;
import com.basicframework.module.ai.service.event.dto.AiRunEventSnapshotDTO;
import com.basicframework.module.ai.service.run.AiRunService;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 运行受理接口（O02）。
 *
 * <p>受理是幂等的：同键同请求复用原运行（返回 {@code reused=true}，不重新发起模型调用），
 * 同键不同请求返回 409。归属由服务端会话身份决定；响应只有运行引用、状态与固定版本，
 * 不含凭据、token 或请求正文。事件流与取消属 O05，任务查询与重试属 O06。
 */
@Tag(name = "应用端 - AI 运行")
@RestController
@RequestMapping("/ai/run")
@Validated
@RequiredArgsConstructor
public class AiRunController {

    /** SSE 连接最长存活时间（到期关闭订阅，客户端可换票续读）。 */
    private static final long STREAM_TIMEOUT_MILLIS = 300_000L;

    /** 单次重放的条数上限（有界订阅）。 */
    private static final int MAX_STREAM_BATCH = 200;

    /** 心跳用 SSE 注释：不写入事件表、不推进序号。 */
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    private final AiRunService runService;

    private final AiRunEventService eventService;

    @PostMapping("/accept")
    @Operation(summary = "受理运行（幂等：同键同请求复用原运行，异请求 409）")
    @AuthenticatedOnly
    public CommonResult<AiRunAcceptRespVO> accept(@Valid @RequestBody AiRunAcceptReqVO reqVO) {
        AiRunAcceptResultDTO result = runService.accept(new AiRunAcceptDTO()
                .setServiceId(reqVO.getServiceId())
                .setConversationId(reqVO.getConversationId())
                .setIdempotencyKey(reqVO.getIdempotencyKey())
                .setMessage(reqVO.getMessage())
                .setAttachmentKeys(reqVO.getAttachmentKeys())
                .setBusinessContext(reqVO.getBusinessContext())
                .setDataLevel(reqVO.getDataLevel()));
        return success(new AiRunAcceptRespVO()
                .setRunId(result.getRunId())
                .setRunKey(result.getRunKey())
                .setStatus(result.getStatus())
                .setReleaseId(result.getReleaseId())
                .setReleaseVersion(result.getReleaseVersion())
                .setReused(result.isReused()));
    }

    @GetMapping("/get")
    @Operation(summary = "读取运行（非本人或不存在的运行同语义拒绝）")
    @Parameter(name = "id", description = "运行编号", required = true)
    @AuthenticatedOnly
    public CommonResult<AiRunRespVO> get(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRunRespVO(runService.getRun(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "当前主体的运行分页（按编号倒序）")
    @AuthenticatedOnly
    public CommonResult<PageResult<AiRunRespVO>> page(@Valid AiRunPageReqVO reqVO) {
        PageResult<AiRunDO> page = runService.getPage(reqVO);
        return success(new PageResult<>(
                page.getList().stream().map(AiRunController::toRunRespVO).collect(Collectors.toList()),
                page.getTotal()));
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅运行事件（SSE：先鉴权再开流，afterSeq 重放，心跳是注释不推进序号）")
    @AuthenticatedOnly
    public SseEmitter events(
            @Parameter(description = "运行编号", required = true) @RequestParam("runId") @NotNull @Positive Long runId,
            @Parameter(description = "起始序号（不含）") @RequestParam(value = "afterSeq", required = false) @PositiveOrZero
                    Integer afterSeq) {
        // 认证与归属判定在**开流之前**完成：失败按普通 HTTP 错误返回，不会开出一条匿名流
        AiRunEventSnapshotDTO snapshot = eventService.snapshot(runId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicInteger lastSeq = new AtomicInteger(afterSeq == null ? 0 : afterSeq);
        // 开流之后的错误不再改变 HTTP 状态，而是以终态事件（或心跳注释）表达
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        try {
            // 先重放：afterSeq 之后已落库的事件按序补发（窗口过期时抛稳定错误）
            for (AiRunEventDTO event : eventService.replay(runId, lastSeq.get(), MAX_STREAM_BATCH)) {
                send(emitter, event);
                lastSeq.set(event.getSeq());
            }
            emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
        } catch (IOException | RuntimeException failure) {
            // 连接已断开或重放窗口过期：关闭订阅，由客户端换票后按快照续读
            emitter.completeWithError(failure);
            return emitter;
        }
        if (isTerminal(snapshot.getStatus())) {
            emitter.complete();
        }
        return emitter;
    }

    @PostMapping("/cancel")
    @Operation(summary = "取消运行（显式动作：写入终态事件并终止任务；已终态返回 409）")
    @AuthenticatedOnly
    public CommonResult<Boolean> cancel(@Valid @RequestBody AiRunCancelReqVO reqVO) {
        eventService.cancel(reqVO.getRunId(), reqVO.getVersion());
        return success(true);
    }

    private static void send(SseEmitter emitter, AiRunEventDTO event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getSeq()))
                .name("run")
                .data(event, MediaType.APPLICATION_JSON));
    }

    private static boolean isTerminal(String status) {
        return AiRunDO.STATUS_SUCCEEDED.equals(status)
                || AiRunDO.STATUS_FAILED.equals(status)
                || AiRunDO.STATUS_CANCELLED.equals(status);
    }

    private static AiRunRespVO toRunRespVO(AiRunDO run) {
        return new AiRunRespVO()
                .setId(run.getId())
                .setRunKey(run.getRunKey())
                .setConversationId(run.getConversationId())
                .setServiceId(run.getServiceId())
                .setReleaseId(run.getReleaseId())
                .setContentHash(run.getContentHash())
                .setEndpointConfigRevision(run.getEndpointConfigRevision())
                .setStatus(run.getStatus())
                .setStepCount(run.getStepCount())
                .setVersion(run.getVersion())
                .setCreateTime(run.getCreateTime());
    }
}
