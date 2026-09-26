package com.basicframework.module.ai.dal.dataobject.evaluation;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 评测套件（Q04）：**配置**，一行 = 一个应用下的一个套件。
 *
 * <p>{@link #status} 为 FROZEN 时表示内容被冻结并配套 {@link #contentDigest}；
 * 冻结后编辑会创建**新修订**（{@link #revision} +1），历史评测运行保留自己的套件摘要，
 * 因此"套件修改不改变已有 eval 运行"由数据而不是约定保证（见 {@code AiEvalRunDO#suiteDigest}）。
 */
@TableName("ai_eval_suite")
@KeySequence("ai_eval_suite_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiEvalSuiteDO extends SoftDeletableDO {

    /** 状态：草稿（可编辑，尚未冻结） */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已冻结（内容摘要固定；再编辑即新修订） */
    public static final String STATUS_FROZEN = "FROZEN";

    /** 数据分级：公开（合成夹具的最低档） */
    public static final String LEVEL_PUBLIC = "L1_PUBLIC";

    /** 数据分级：内部（合成夹具的默认档） */
    public static final String LEVEL_INTERNAL = "L2_INTERNAL";

    /** 套件编号 */
    @TableId
    private Long id;

    /** 所属应用编号 */
    private Long applicationId;

    /** 套件标识（应用内唯一） */
    private String code;

    /** 套件名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 被评测的服务编号（执行走同一运行服务与授权） */
    private Long serviceId;

    /** 执行主体类型（APP/USER） */
    private String subjectType;

    /** 执行主体标识（评测专用合成主体，不是真实用户） */
    private String externalUserId;

    /** 样例数据分级（只允许 L1_PUBLIC/L2_INTERNAL） */
    private String dataLevel;

    /** 状态（DRAFT/FROZEN） */
    private String status;

    /** 修订号（冻结一次 +1） */
    private Integer revision;

    /** 样例数（冻结时的事实值） */
    private Integer caseCount;

    /** 最近冻结时间 */
    private LocalDateTime frozenTime;

    /** 冻结内容摘要（套件 + 样例规范化后的 SHA-256） */
    private String contentDigest;

    /** 乐观锁版本 */
    private Integer version;
}
