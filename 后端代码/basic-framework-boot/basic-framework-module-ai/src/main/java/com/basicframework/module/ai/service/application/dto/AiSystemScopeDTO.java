package com.basicframework.module.ai.service.application.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 某系统内的一条可访问范围（Y01）：资源类型 + 资源标识 + 动作白名单。 */
@Data
@Accessors(chain = true)
public class AiSystemScopeDTO {

    /** 资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET） */
    private String resourceType;

    /** 资源标识 */
    private String resourceKey;

    /** 动作白名单（已排序，只含 A03 词表内的动作） */
    private List<String> actions;
}
