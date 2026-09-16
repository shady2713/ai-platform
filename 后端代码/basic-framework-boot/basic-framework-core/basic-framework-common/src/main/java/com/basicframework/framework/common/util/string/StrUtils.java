package com.basicframework.framework.common.util.string;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aspectj.lang.JoinPoint;
import org.springframework.web.multipart.MultipartFile;

/**
 * 字符串工具类
 *
 */
public final class StrUtils {

    private StrUtils() {}

    public static String maxLength(CharSequence str, int maxLength) {
        if (maxLength <= 3) {
            throw new IllegalArgumentException("maxLength must be greater than 3");
        }
        if (str == null || str.length() <= maxLength) {
            return str == null ? null : str.toString();
        }
        return StrUtil.maxLength(str, maxLength - 3); // -3 的原因，是该方法会补充 ... 恰好
    }

    /**
     * 拼接方法的参数
     *
     * 特殊：排除一些无法序列化的参数，如 ServletRequest、ServletResponse、MultipartFile
     *
     * @param joinPoint 连接点
     * @return 拼接后的参数
     */
    public static String joinMethodArgs(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (ArrayUtil.isEmpty(args)) {
            return "";
        }
        return ArrayUtil.join(args, ",", item -> {
            if (item == null) {
                return "";
            }
            if (item instanceof ServletRequest || item instanceof ServletResponse || item instanceof MultipartFile) {
                return "";
            }
            return item;
        });
    }
}
