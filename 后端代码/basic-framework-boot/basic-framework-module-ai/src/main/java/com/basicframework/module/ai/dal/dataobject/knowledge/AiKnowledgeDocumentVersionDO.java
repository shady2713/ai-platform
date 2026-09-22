package com.basicframework.module.ai.dal.dataobject.knowledge;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 知识文档版本（K02）：一次入库的私有文件 + 指纹 + 索引代。
 *
 * <p>版本是不可变快照：进入 {@link #STATUS_READY} 或 {@link #STATUS_SUPERSEDED} 之后，
 * 文件、指纹与切片都不允许再改（只能新建版本）——否则历史引用会指向被改写的内容。
 *
 * <p>{@code fileId} 是 A07 业务文件编号（业务类型 {@code ai_knowledge_document}，业务键 = 知识库标识）：
 * 文件是私有的，读取仍按当前归属判定（授权被回收后立即读不到）。
 */
@TableName("ai_knowledge_document_version")
@KeySequence("ai_knowledge_document_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiKnowledgeDocumentVersionDO extends SoftDeletableDO {

    /** 状态：索引中（可重试、可置失败）。 */
    public static final String STATUS_INDEXING = "INDEXING";

    /** 状态：可用（不可再修改）。 */
    public static final String STATUS_READY = "READY";

    /** 状态：失败（可重试，不影响旧版本）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：已被新版本取代（不可再修改，仍可追溯）。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** 版本编号 */
    @TableId
    private Long id;

    /** 文档编号 */
    private Long documentId;

    /** 知识库编号（冗余，供检索过滤与清理直接命中） */
    private Long knowledgeBaseId;

    /** 版本号（文档内递增，READY 后不可变） */
    private Integer versionNo;

    /** 私有文件编号（A07 业务文件） */
    private Long fileId;

    /** 文件指纹（sha256 hex；同指纹重复入库不产生新版本） */
    private String contentHash;

    /** 来源位置 */
    private String sourceRef;

    /** 状态（INDEXING/READY/FAILED/SUPERSEDED） */
    private String status;

    /** 切片所属索引代 */
    private Integer indexGeneration;

    /** 切片数 */
    private Integer chunkCount;

    /** 失败原因（脱敏稳定原因码） */
    private String failureReason;

    /** 可用时间 */
    private java.time.LocalDateTime readyAt;

    /** 乐观锁版本 */
    private Integer version;

    /** 是否已进入不可变状态（READY/SUPERSEDED）。 */
    public boolean immutable() {
        return STATUS_READY.equals(status) || STATUS_SUPERSEDED.equals(status);
    }
}
