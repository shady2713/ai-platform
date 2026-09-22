package com.basicframework.module.ai.dal.dataobject.knowledge;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 知识文档（K02）：sourceKey 幂等 + active 版本指针。
 *
 * <p>文档是"逻辑对象"，内容永远挂在版本上：文档只记录 {@code sourceKey}（外部同步键或上传标识）、
 * 标题与状态，正文/文件/指纹都在 {@link AiKnowledgeDocumentVersionDO}。
 * {@code activeVersionNo} 是**可用版本指针**，只在索引成功后切换；索引失败保留旧版本（AT-024）。
 *
 * <p>状态是入库流水线的可见面（FR-17）：PENDING 排队 → PARSING 解析 → INDEXING 切分/向量化 →
 * READY 可用 / FAILED 失败 / DELETING 删除中。失败原因只存**脱敏稳定原因码**，不含正文。
 */
@TableName("ai_knowledge_document")
@KeySequence("ai_knowledge_document_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiKnowledgeDocumentDO extends SoftDeletableDO {

    /** 状态：排队。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 状态：解析中。 */
    public static final String STATUS_PARSING = "PARSING";

    /** 状态：切分与向量化中。 */
    public static final String STATUS_INDEXING = "INDEXING";

    /** 状态：可用。 */
    public static final String STATUS_READY = "READY";

    /** 状态：失败（可重试）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：删除中（K07 回收索引与切片）。 */
    public static final String STATUS_DELETING = "DELETING";

    /** 来源类型：上传。 */
    public static final String SOURCE_UPLOAD = "UPLOAD";

    /** 来源类型：受授权 API 同步。 */
    public static final String SOURCE_API_SYNC = "API_SYNC";

    /** 文档编号 */
    @TableId
    private Long id;

    /** 知识库编号 */
    private Long knowledgeBaseId;

    /** 来源幂等键（库内唯一；重复入库按指纹决定复用还是生成新版本） */
    private String sourceKey;

    /** 文档标题 */
    private String title;

    /** 来源类型（UPLOAD/API_SYNC） */
    private String sourceType;

    /** 来源位置（受控标识，不抓取第三方站点） */
    private String sourceRef;

    /** 状态（PENDING/PARSING/INDEXING/READY/FAILED/DELETING） */
    private String status;

    /** 当前可用版本号（0 表示尚无可用版本） */
    private Integer activeVersionNo;

    /** 最新版本号（0 表示尚无版本） */
    private Integer latestVersionNo;

    /** 最近失败原因（脱敏稳定原因码） */
    private String failureReason;

    /** 解析提示（例如扫描件需要 OCR） */
    private String parseNote;

    /** 乐观锁版本 */
    private Integer version;
}
