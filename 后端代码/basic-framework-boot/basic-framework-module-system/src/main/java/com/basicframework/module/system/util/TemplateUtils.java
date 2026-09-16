package com.basicframework.module.system.util;

import cn.hutool.core.util.ReUtil;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 模板内容工具类
 *
 * 短信模板、站内信模板均使用 {key} 作为内容变量占位符，解析规则在此统一定义
 */
public final class TemplateUtils {

    /**
     * 正则表达式，匹配 {} 中的变量
     */
    public static final Pattern PATTERN_PARAMS = Pattern.compile("\\{(.*?)}");

    private TemplateUtils() {}

    /**
     * 解析模板内容中的变量名
     *
     * @param content 模板内容
     * @return 变量名列表
     */
    public static List<String> parseContentParams(String content) {
        return ReUtil.findAllGroup1(PATTERN_PARAMS, content);
    }
}
