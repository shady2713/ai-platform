package com.basicframework.module.ai.service.model.dto;

import java.util.List;
import lombok.Data;
import lombok.ToString;

/**
 * 模型端点保存命令（服务层输入）。
 *
 * <p>服务层不依赖 Controller VO（模块边界规则 D/I）：由控制器把请求 VO 转成本命令，
 * 服务只处理领域字段与乐观锁版本。
 */
@Data
public class AiModelEndpointSaveDTO {

    /** 端点编号；创建时为空 */
    private Long id;

    /** 端点名称 */
    private String name;

    /** 提供方标识 */
    private String provider;

    /** 基础地址 */
    private String baseUrl;

    /** 模型标识（非秘密配置，写入不可变版本） */
    private String modelId;

    /** 能力集合 */
    private List<String> capabilities;

    /** 凭据（仅创建/轮换时提供；为空表示不变更）；不得进入日志 */
    @ToString.Exclude
    private String credential;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
