package com.basicframework.framework.common.util.servlet;

import cn.hutool.core.net.Ipv4Util;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 客户端 IP 解析器：仅在请求直接对端（remoteAddr）命中可信代理列表时，才采信
 * {@code X-Forwarded-For} / {@code X-Real-IP}；否则直接返回 remoteAddr。
 *
 * 默认（未配置 basic-framework.web.trusted-proxies）不信任任何代理头，避免攻击者
 * 伪造代理头绕过按 IP 的限流与访问审计；部署在反向代理之后时，必须显式配置代理的
 * 出口 IP 或网段，否则按 IP 的限流与审计会取到代理 IP。
 */
public final class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String X_REAL_IP = "X-Real-IP";

    private static final String HEADER_IP_PATTERN =
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$|^[0-9a-fA-F:]{3,45}$";

    private static final Pattern VALID_IP_PATTERN = Pattern.compile(HEADER_IP_PATTERN);

    private static volatile List<String> trustedProxies = List.of();

    private ClientIpResolver() {}

    /**
     * 由 starter 自动装配在启动期注入可信代理列表；空列表表示不信任任何代理头（fail-closed）。
     *
     * @param trustedProxies 代理出口 IP 或 IPv4 CIDR，如 127.0.0.1、10.0.0.0/8
     */
    public ClientIpResolver(List<String> trustedProxies) {
        List<String> entries = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        for (String entry : entries) {
            if (!isValidIpOrCidr(entry)) {
                throw new IllegalArgumentException(
                        "basic-framework.web.trusted-proxies 配置项无效: " + entry + "，只支持 IPv4/IPv6 地址或 IPv4 CIDR");
            }
        }
        ClientIpResolver.trustedProxies = entries;
    }

    /**
     * @param request 请求
     * @return 客户端 IP；直接对端非可信代理时不采信任何代理头
     */
    public static String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String headerValue = firstHeaderValue(request, X_FORWARDED_FOR);
        if (StrUtil.isBlank(headerValue)) {
            headerValue = firstHeaderValue(request, X_REAL_IP);
        }
        return isValidIp(headerValue) ? headerValue : remoteAddr;
    }

    private static boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        for (String entry : trustedProxies) {
            int slashIndex = entry.indexOf('/');
            if (slashIndex > 0) {
                if (isIpInCidr(remoteAddr, entry, slashIndex)) {
                    return true;
                }
            } else if (entry.equals(remoteAddr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpInCidr(String remoteAddr, String cidr, int slashIndex) {
        try {
            int mask = Integer.parseInt(cidr.substring(slashIndex + 1));
            Long begin = Ipv4Util.getBeginIpLong(cidr.substring(0, slashIndex), mask);
            Long end = Ipv4Util.getEndIpLong(cidr.substring(0, slashIndex), mask);
            if (begin == null || end == null) {
                return false;
            }
            long ipValue = Ipv4Util.ipv4ToLong(remoteAddr);
            return ipValue >= begin && ipValue <= end;
        } catch (IllegalArgumentException ignored) {
            // IPv6 字形或其他非法输入，按不匹配处理
            return false;
        }
    }

    private static String firstHeaderValue(HttpServletRequest request, String headerName) {
        String header = request.getHeader(headerName);
        if (StrUtil.isBlank(header)) {
            return null;
        }
        int commaIndex = header.indexOf(',');
        return commaIndex > 0 ? header.substring(0, commaIndex).trim() : header.trim();
    }

    private static boolean isValidIpOrCidr(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        int slashIndex = value.indexOf('/');
        if (slashIndex > 0) {
            String cidrPart = value.substring(slashIndex + 1);
            if (cidrPart.isEmpty() || !cidrPart.chars().allMatch(Character::isDigit)) {
                return false;
            }
            // 前缀位数字符校验保证 parseInt 不会失败，此处不需要防御性 catch
            return Integer.parseInt(cidrPart) <= 32 && isValidIp(value.substring(0, slashIndex));
        }
        return isValidIp(value);
    }

    private static boolean isValidIp(String value) {
        return value != null && VALID_IP_PATTERN.matcher(value).matches();
    }
}
