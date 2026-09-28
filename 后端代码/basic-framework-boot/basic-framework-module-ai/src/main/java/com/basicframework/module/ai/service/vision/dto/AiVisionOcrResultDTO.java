package com.basicframework.module.ai.service.vision.dto;

import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import com.basicframework.module.ai.service.vision.AiVisionRegionSource;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 单页 OCR 结果（X02）：文本 + 页码 + 识别范围 + **置信度来源**。
 *
 * <p>三条不伪装的语义：
 * <ol>
 *   <li><b>页码</b>：`page` 是该识别文本在来源文档中的页序（单张图片固定为 1）；
 *       重索引时页码作为页标题写入识别稿正文（"第 N 页"），引用片段因此能定位到第几页；
 *       切片位置本身仍由既有解析器给出（`.txt` 识别稿走段落编号），不另造一套位置体系；</li>
 *   <li><b>识别范围</b>：`region` + `regionSource` 说明这段文本覆盖图片的哪一块。
 *       X01 冻结的端口契约（`MediaTextResponse`）只返回文本、不返回逐块区域，因此平台当前只给出
 *       **整页**范围（{@link AiVisionRegionSource#WHOLE_PAGE}）并显式标注；适配层能返回逐块结果时
 *       改为 {@link AiVisionRegionSource#PROVIDER}，不改变本结果的字段形状；</li>
 *   <li><b>置信度来源</b>：{@link AiVisionConfidenceSource} 说明置信度是上游给的还是**没有**。
 *       没有就记 {@link AiVisionConfidenceSource#UNKNOWN} 且不给任何数值——不编造 0.9 之类的数字；
 *       `reviewRequired=true` 表示这是机器识别结果、未经人工核验，展示方必须提示。</li>
 * </ol>
 */
@Data
@Accessors(chain = true)
public class AiVisionOcrResultDTO {

    /** 结果引用的私有图片编号（**只有标识，没有地址**） */
    private Long fileId;

    /** 页码（从 1 开始；单张图片固定为 1） */
    private Integer page;

    /** 识别文本（非空；空文本按上游协议异常拒绝） */
    private String text;

    /** 识别范围（当前为整页） */
    private AiVisionRegionDTO region;

    /** 识别范围来源 */
    private AiVisionRegionSource regionSource;

    /** 置信度来源；UNKNOWN 时不给任何置信度数值 */
    private AiVisionConfidenceSource confidenceSource;

    /** 置信度（0..1）；仅在 {@link AiVisionConfidenceSource#PROVIDER} 时有值 */
    private Double confidence;

    /** 是否需要人工复核（机器识别未核验时为 true，展示方不得伪装成人工核验结果） */
    private boolean reviewRequired;

    /** 用量（缺失记 UNKNOWN） */
    private AiVisionUsageDTO usage;
}
