package com.basicframework.module.ai.dal.dataobject.theme;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 主题修订（C04）：控制面配置，一行 = 一个不可变的主题修订。
 *
 * <p>为什么按"修订"而不是"可改的主题"存储：主题直接影响嵌入页的观感与报表外观，
 * 发布后必须可追溯、可回退（`PUBLISHED`/`SUPERSEDED` 互切即回退），
 * 因此内容一旦发布就不再修改，任何调整都新建修订。
 *
 * <p>为什么不存"有效主题"：有效主题由**继承顺序**在运行时决定
 * （平台默认 → 应用已发布修订 → 允许字段的宿主运行时覆盖），
 * 覆盖不写库、也不影响其他应用，故本表只保存应用自己的发布修订。
 */
@TableName("ai_theme")
@KeySequence("ai_theme_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiThemeDO extends SoftDeletableDO {

    /** 发布状态：草稿（可继续调整的是**新修订**，不是它本身） */
    public static final String STATE_DRAFT = "DRAFT";

    /** 发布状态：当前生效 */
    public static final String STATE_PUBLISHED = "PUBLISHED";

    /** 发布状态：已被更新的修订取代（保留用于回退与审计） */
    public static final String STATE_SUPERSEDED = "SUPERSEDED";

    /** 主题修订编号 */
    @TableId
    private Long id;

    /** 主题对外标识（thm_ 前缀的不透明字符串，跨应用唯一） */
    private String publicId;

    /** 所属应用编号 */
    private Long applicationId;

    /** 修订号（应用内递增，发布后不可修改） */
    private Integer revision;

    /** ThemeTokens v1（已校验 JSON：色值/半径/字体白名单/深浅色） */
    @ToString.Exclude
    private String tokensJson;

    /** 布局与排版受控选项（已校验 JSON） */
    private String layoutJson;

    /** tokens+layout 的内容摘要（缓存键与审计用；不含凭据） */
    @ToString.Exclude
    private String tokensFingerprint;

    /** 发布状态（DRAFT/PUBLISHED/SUPERSEDED） */
    private String publicationState;

    /** 发布时间（首次发布时写入；回退会重新指向历史修订） */
    private LocalDateTime publishedTime;

    /** 乐观锁版本 */
    private Integer version;
}
