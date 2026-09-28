package com.basicframework.module.ai.dal.dataobject.media;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 媒体任务（X03）：图片生成/编辑与（X04）非实时语音的持久任务。
 *
 * <p>为什么任务要落库而不是"请求里同步做完"：生成类调用耗时不可控，进程中断、重试、取消与用量
 * 都需要可核验的事实。任务行保存**受理时固定**的端点与配置版本（模型换了不会被旧任务悄悄换掉），
 * 以及输入的**引用与声明级元数据**（源文件编号/MIME/字节数/摘要），不保存正文与地址。
 *
 * <p>租约语义与 O03 的 `ai_run_task`、K03 的入库任务一致：领取是 CAS（QUEUED → RUNNING 且写
 * owner/epoch/到期时间），续租与落终态都必须带 (owner, epoch)，租约被接管后旧 worker 命中 0 行。
 */
@TableName("ai_media_task")
@KeySequence("ai_media_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiMediaTaskDO extends SoftDeletableDO {

    /** 状态：待领取。 */
    public static final String STATUS_QUEUED = "QUEUED";

    /** 状态：执行中（持有租约）。 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：成功。 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：失败（达到重试上限或不可重试错误）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：已取消（终态，不再执行）。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 媒体种类：图片。 */
    public static final String KIND_IMAGE = "IMAGE";

    /** 媒体种类：音频。 */
    public static final String KIND_AUDIO = "AUDIO";

    /** 操作：生成（图片：文生图）. */
    public static final String OPERATION_GENERATE = "GENERATE";

    /** 操作：编辑（图片：底图 + 指令）. */
    public static final String OPERATION_EDIT = "EDIT";

    /** 操作：转写（音频：语音转文字）. */
    public static final String OPERATION_TRANSCRIBE = "TRANSCRIBE";

    /** 操作：合成（音频：文字转语音）. */
    public static final String OPERATION_SYNTHESIZE = "SYNTHESIZE";

    /** 计量来源：上游真实计量。 */
    public static final String USAGE_SOURCE_REPORTED = "REPORTED";

    /** 计量来源：上游缺失。 */
    public static final String USAGE_SOURCE_UNKNOWN = "UNKNOWN";

    /** 任务编号 */
    @TableId
    private Long id;

    /** 幂等键（调用方提供，应用 + 主体内唯一） */
    private String requestKey;

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 可信外部用户标识 */
    private String externalUserId;

    /** 媒体种类（IMAGE/AUDIO） */
    private String mediaKind;

    /** 操作（GENERATE/EDIT/TRANSCRIBE/SYNTHESIZE） */
    private String operation;

    /** 能力（与 ModelCapability 同名，执行前据此走媒体准入） */
    private String capability;

    /** 受理时固定的模型端点编号 */
    private Long endpointId;

    /** 受理时固定的端点配置版本 */
    private Integer endpointConfigRevision;

    /** 受理时固定的模型标识 */
    private String modelRef;

    /** 文本输入（生成提示词/编辑指令/合成文本） */
    private String inputText;

    /** 源文件编号（编辑底图/待转写音频） */
    private Long sourceFileId;

    /** 源文件声明 MIME */
    private String sourceMime;

    /** 源文件声明字节数 */
    private Long sourceSizeBytes;

    /** 源文件声明摘要 */
    private String sourceSha256;

    /** 目标尺寸（宽x高） */
    private String targetSize;

    /** 请求产物数量 */
    private Integer outputCount;

    /** 请求输出格式（图片生成/编辑与 TTS；转写没有输出格式，为空） */
    private String outputFormat;

    /** TTS 音色标识（受理时固定；为空表示端点默认音色，X04） */
    private String voice;

    /** STT 语言提示（冻结语言的短标识；为空表示由端点自行识别，X04） */
    private String languageHint;

    /** 状态（QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED） */
    private String status;

    /** 已落库产物数量 */
    private Integer resultCount;

    /** 失败原因（稳定错误码） */
    private String failureCode;

    /** 已尝试次数 */
    private Integer attemptCount;

    /** 最大尝试次数 */
    private Integer maxAttempts;

    /** 下次可领取时间 */
    private LocalDateTime nextAttemptTime;

    /** 租约持有者 */
    private String leaseOwner;

    /** 租约到期时间 */
    private LocalDateTime leaseExpiresTime;

    /** 最近续租时间 */
    private LocalDateTime heartbeatTime;

    /** 领取代数（栅栏） */
    private Integer claimedEpoch;

    /** 计量单位（未知为空） */
    private String usageUnit;

    /** 计量数值（未知为空，不写 0） */
    private Long usageQuantity;

    /** 计量来源（REPORTED/UNKNOWN） */
    private String usageSource;

    /** 乐观锁版本 */
    private Integer version;
}
