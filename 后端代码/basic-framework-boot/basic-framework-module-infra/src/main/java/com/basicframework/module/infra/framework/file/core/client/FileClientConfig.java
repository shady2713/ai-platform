package com.basicframework.module.infra.framework.file.core.client;

import com.basicframework.module.infra.framework.file.core.client.db.DBFileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 文件客户端的配置
 * 不同实现的客户端，需要不同的配置，通过子类来定义
 *
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DBFileClientConfig.class, name = "db"),
    @JsonSubTypes.Type(value = LocalFileClientConfig.class, name = "local"),
    @JsonSubTypes.Type(value = S3FileClientConfig.class, name = "s3")
})
public interface FileClientConfig {}
