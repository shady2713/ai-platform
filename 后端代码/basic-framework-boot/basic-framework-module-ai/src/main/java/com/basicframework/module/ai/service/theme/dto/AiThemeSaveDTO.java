package com.basicframework.module.ai.service.theme.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 主题修订新增（服务层 DTO）：只接受已校验 token 与受控布局选项。 */
@Data
@Accessors(chain = true)
public class AiThemeSaveDTO {

    /** 所属应用编号（必填；应用必须存在） */
    private Long applicationId;

    /** 布局与排版受控选项 JSON（可选；缺省项按平台默认补齐） */
    private String layoutJson;

    /** ThemeTokens v1 JSON（必填；色值/半径/字体白名单在此校验） */
    @ToString.Exclude
    private String tokensJson;
}
