package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 主数据映射目录发现请求（Y02）。
 *
 * <p>与 Y01 的授权发现同构：只读事实、不接受"我想看哪些系统"的参数——主体在其不可访问的系统里
 * 的映射**根本不出现**（而不是出现后标注无权），因此没有可枚举的参数面。
 */
@Data
@Accessors(chain = true)
public class AiMasterObjectCatalogQueryDTO {

    /** 统一对象标识 */
    private String objectCode;

    /** 映射版本号（显式指定；判定与目录都以同一版本为准） */
    private Long revisionNo;

    /** 当前应用（当前系统）编号 */
    private Long applicationId;

    /** 当前主体类型（USER/APP） */
    private String subjectType;

    /** 当前主体外部用户标识（APP 主体忽略） */
    private String externalUserId;

    /** 判定时刻（版本与其条目的有效期都按此时刻计算） */
    private LocalDateTime asOf;
}
