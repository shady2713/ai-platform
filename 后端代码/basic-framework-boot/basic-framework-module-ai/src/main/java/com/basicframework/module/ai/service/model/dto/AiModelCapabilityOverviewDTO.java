package com.basicframework.module.ai.service.model.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 端点能力总览（服务层 DTO）：声明与探测确认的能力分开给出，二者交集才是可发布范围。
 *
 * <p>只声明未确认的能力不会被发布：避免"配置里写了、实际用不了"的能力进入应用发布范围；
 * 探测失败（FAILED）与明确不支持（UNSUPPORTED）都不计入确认集合。
 */
@Data
@Accessors(chain = true)
public class AiModelCapabilityOverviewDTO {

    /** 端点编号 */
    private Long endpointId;

    /** 端点声明的能力（当前配置版本） */
    private List<String> declared;

    /** 探测确认为可用的能力（每种探测的最新结论） */
    private List<String> supported;

    /** 可发布范围：声明与确认的交集 */
    private List<String> publishable;
}
