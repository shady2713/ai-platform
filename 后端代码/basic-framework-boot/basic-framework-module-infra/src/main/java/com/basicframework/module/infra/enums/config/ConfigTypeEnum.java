package com.basicframework.module.infra.enums.config;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 系统参数配置的类型枚举 */
@Getter
@AllArgsConstructor
public enum ConfigTypeEnum {

    /**
     * 系统配置
     */
    SYSTEM(1),
    /**
     * 自定义配置
     */
    CUSTOM(2);

    private final Integer type;
}
