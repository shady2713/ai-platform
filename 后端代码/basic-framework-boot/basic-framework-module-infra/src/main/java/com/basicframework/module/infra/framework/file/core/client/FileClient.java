package com.basicframework.module.infra.framework.file.core.client;

import java.time.Duration;

/**
 * 文件客户端
 *
 */
public interface FileClient extends AutoCloseable {

    /**
     * 获得客户端编号
     *
     * @return 客户端编号
     */
    Long getId();

    /**
     * 上传文件
     *
     * @param content 文件流
     * @param path    相对路径
     * @return 完整路径，即 HTTP 访问地址
     * @throws Exception 上传文件时，抛出 Exception 异常
     */
    String upload(byte[] content, String path, String type) throws Exception;

    /**
     * 删除文件
     *
     * @param path 相对路径
     * @throws Exception 删除文件时，抛出 Exception 异常
     */
    void delete(String path) throws Exception;

    /**
     * 获得文件的内容
     *
     * @param path 相对路径
     * @return 文件的内容
     */
    byte[] getContent(String path) throws Exception;

    // ========== 文件签名，目前仅 S3 支持 ==========

    /**
     * 获得文件预签名地址，用于上传
     *
     * @param path 相对路径
     * @param size 声明的对象字节数
     * @param type 声明的 MIME 类型
     * @param expiration 签名有效期
     * @return 文件预签名地址
     */
    default String presignPutUrl(String path, long size, String type, Duration expiration) {
        throw new UnsupportedOperationException("不支持的操作");
    }

    /**
     * 读取存储端对象元数据，不存在时返回 null。
     *
     * @param path 相对路径
     * @return 对象元数据
     */
    default FileObjectMetadata getMetadata(String path) {
        throw new UnsupportedOperationException("不支持的操作");
    }

    /**
     * 是否支持在对象公开前完成服务端校验的私有预签名上传。
     *
     * @return 是否支持
     */
    default boolean supportsPrivatePresignedUpload() {
        return false;
    }

    /**
     * 是否能阻止未授权主体直接读取私有对象。
     *
     * <p>本地和数据库客户端经应用下载接口读取，默认满足；公开 S3 Bucket 必须覆写为 {@code false}。
     *
     * @return 是否支持私有读取
     */
    default boolean supportsPrivateRead() {
        return true;
    }

    /**
     * 生成文件预签名地址，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    default String presignGetUrl(String url, Integer expirationSeconds) {
        throw new UnsupportedOperationException("不支持的操作");
    }

    /** 释放客户端持有的连接池、线程和网络资源。无资源客户端无需覆写。 */
    @Override
    default void close() {}
}
