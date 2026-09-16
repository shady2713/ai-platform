package com.basicframework.module.infra.service.file.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文件预签名地址信息
 */
@Getter
@AllArgsConstructor
public class FilePresignedUrlDTO {

    /**
     * 配置编号
     */
    private final Long configId;
    /**
     * 文件上传 URL
     */
    private final String uploadUrl;
    /**
     * 文件访问 URL
     *
     * 前端上传完文件后，需要使用该 URL 进行访问
     */
    private final String url;
    /**
     * 文件路径
     *
     * 前端上传完文件后，需要调用 createFile 记录下 path 路径
     */
    private final String path;
    /** 一次性上传完成凭据；服务端只保存摘要。 */
    private final String uploadToken;
}
