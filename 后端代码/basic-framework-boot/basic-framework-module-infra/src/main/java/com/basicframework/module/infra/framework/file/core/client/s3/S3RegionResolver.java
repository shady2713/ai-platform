package com.basicframework.module.infra.framework.file.core.client.s3;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import java.net.URI;

/**
 * 从 S3 兼容端点推断区域：配置优先，其次按云厂商端点格式解析，最后回退 us-east-1。
 *
 * 支持的端点形态：
 * AWS S3（s3.{region}.amazonaws.com）、阿里云 OSS（oss-{region}.aliyuncs.com）、
 * 腾讯云 COS（cos.{region}.myqcloud.com）；MinIO、七牛云等自建端点统一回退默认值。
 */
final class S3RegionResolver {

    /**
     * AWS S3 / S3 兼容实现的通用默认区域
     */
    private static final String DEFAULT_REGION = "us-east-1";

    private S3RegionResolver() {}

    /**
     * 解析 AWS 区域
     * 优先级：配置的 region > 从 endpoint 解析的 region > 默认值 us-east-1
     *
     * @param config 客户端配置
     * @return 区域字符串
     */
    static String resolveRegion(S3FileClientConfig config) {
        // 1. 如果配置了 region，直接使用
        if (StrUtil.isNotEmpty(config.getRegion())) {
            return config.getRegion();
        }
        String host = extractHost(config.getEndpoint());
        if (StrUtil.isEmpty(host)) {
            return DEFAULT_REGION;
        }
        if (host.contains("amazonaws.com")) {
            return resolveAwsRegion(host);
        }
        if (host.contains(S3FileClientConfig.ENDPOINT_ALIYUN)) {
            return resolveVendorRegion(host, "oss-", S3FileClientConfig.ENDPOINT_ALIYUN);
        }
        if (host.contains(S3FileClientConfig.ENDPOINT_TENCENT)) {
            return resolveVendorRegion(host, "cos.", S3FileClientConfig.ENDPOINT_TENCENT);
        }
        // MinIO、七牛云等无法从端点推断区域，使用默认值
        return DEFAULT_REGION;
    }

    /**
     * 移除协议头（http:// 或 https://）；非法 URI 无法推断区域，返回 null
     */
    private static String extractHost(String endpoint) {
        if (StrUtil.isEmpty(endpoint)) {
            return null;
        }
        if (HttpUtil.isHttp(endpoint) || HttpUtil.isHttps(endpoint)) {
            try {
                return URI.create(endpoint).getHost();
            } catch (IllegalArgumentException e) {
                // endpoint 非法 URI 时无法推断 region，回退默认值
                return null;
            }
        }
        return endpoint;
    }

    /**
     * AWS S3 格式：s3.us-west-2.amazonaws.com；s3.amazonaws.com 与
     * s3-accelerate.amazonaws.com 不携带区域信息，使用默认值
     */
    private static String resolveAwsRegion(String host) {
        int regionEnd = host.indexOf(".amazonaws.com");
        if (host.startsWith("s3.") && regionEnd > 3) {
            String regionPart = host.substring(3, regionEnd);
            if (!regionPart.equals("accelerate")) {
                return regionPart;
            }
        }
        return DEFAULT_REGION;
    }

    /**
     * 阿里云 OSS（oss-{region}.aliyuncs.com）与腾讯云 COS（cos.{region}.myqcloud.com）
     * 共享的前缀推断逻辑
     *
     * @param host      已剥离协议头的主机名
     * @param prefix    服务标识前缀（含分隔符）
     * @param domainTag 该厂商的端点域名
     * @return 区域字符串；无法识别时回退默认值
     */
    private static String resolveVendorRegion(String host, String prefix, String domainTag) {
        if (host.startsWith(prefix) && host.contains("." + domainTag)) {
            String regionPart = host.substring(prefix.length(), host.indexOf("." + domainTag));
            if (StrUtil.isNotEmpty(regionPart)) {
                return regionPart;
            }
        }
        return DEFAULT_REGION;
    }
}
