package com.basicframework.module.ai.service.document.dto;

import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * OCR 识别稿重索引结果（X02）：文档/版本/任务编号 + 识别稿元信息。
 *
 * <p>`reused=true` 表示同指纹复用既有版本（重复提交同一份识别稿不会产生第二个版本、第二个任务）；
 * `createdVersion=true` 表示这次真的产生了新版本（旧 active 版本在索引成功前仍然可用）。
 */
@Data
@Accessors(chain = true)
public class AiOcrDocumentReindexResultDTO {

    /** 文档编号 */
    private Long documentId;

    /** 版本编号 */
    private Long versionId;

    /** 版本号 */
    private Integer versionNo;

    /** 入库任务编号（由既有入库流水线领取处理） */
    private Long taskId;

    /** 是否产生了新版本 */
    private boolean createdVersion;

    /** 是否复用了既有版本 */
    private boolean reused;

    /** 识别稿页数（只统计有正文的页） */
    private Integer pageCount;

    /** 识别稿字符数 */
    private Integer characterCount;

    /** 识别稿的置信度来源（有一页未提供即记 UNKNOWN，不夸大） */
    private AiVisionConfidenceSource confidenceSource;

    /** 是否需要人工复核（识别稿恒为 true：机器识别、未经人工核验） */
    private boolean reviewRequired;

    /** 识别依据的原始私有文件编号 */
    private Long sourceFileId;

    /** 派生识别稿的私有文件编号（**只有标识，没有地址**） */
    private Long derivedFileId;

    /** 版本来源位置（追溯用受控标识） */
    private String sourceRef;
}
