package com.basicframework.module.ai.service.serviceconfig.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 服务所需能力与端点可发布能力的对比结果（S01：能力不足不能保存为可发布）。 */
@Data
@Accessors(chain = true)
public class AiServiceCapabilityDTO {

    /** 服务所需能力 */
    private List<String> required;

    /** 端点探测确认可用的能力（M04 的可发布范围） */
    private List<String> publishable;

    /** 缺失能力（required - publishable） */
    private List<String> missing;

    /** 是否满足发布条件 */
    private boolean satisfied;
}
