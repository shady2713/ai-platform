package com.basicframework.module.ai.service.theme.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 有效主题（服务层 DTO）：继承顺序的**结果**，供嵌入页/宿主直接使用。
 *
 * <p>继承顺序：平台默认 → 应用已发布修订 → 允许字段的宿主运行时覆盖（覆盖不经过本 DTO，也不写库）。
 * {@code source} 标明当前值来自哪一层，缓存与排查都靠它（与 C05 的"应用和主题版本参与缓存键"同一口径）。
 */
@Data
@Accessors(chain = true)
public class AiThemeEffectiveDTO {

    /** 来源：平台默认（应用尚无发布修订） */
    public static final String SOURCE_PLATFORM_DEFAULT = "PLATFORM_DEFAULT";

    /** 来源：应用已发布修订 */
    public static final String SOURCE_APPLICATION_PUBLISHED = "APPLICATION_PUBLISHED";

    /** 应用编号 */
    private Long applicationId;

    /** 主题对外标识（来自平台默认时为 null） */
    private String publicId;

    /** 修订号（来自平台默认时为 null） */
    private Integer revision;

    /** 内容摘要（缓存键；来自平台默认时为平台默认摘要） */
    private String fingerprint;

    /** 来源（PLATFORM_DEFAULT / APPLICATION_PUBLISHED） */
    private String source;

    /** 布局与排版选项 JSON（规范化） */
    private String layoutJson;

    /** ThemeTokens v1 JSON（规范化） */
    @ToString.Exclude
    private String tokensJson;
}
