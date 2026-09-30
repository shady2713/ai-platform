package com.basicframework.module.ai.service.semantic.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 企业统一对象的新建/修改请求（Y02）。
 *
 * <p>{@code objectCode} 只在新建时生效：标识稳定且不可修改（历史报表/映射版本都按标识引用对象）。
 */
@Data
@Accessors(chain = true)
public class AiMasterObjectSaveDTO {

    /** 统一对象编号（修改时必填，新建时忽略） */
    private Long id;

    /** 统一对象标识（新建必填；字母开头，字母数字与 _ -，长度 3..64） */
    private String objectCode;

    /** 对象名称（仅展示） */
    private String objectName;

    /** 对象类型（CUSTOMER/SUPPLIER/PRODUCT/EMPLOYEE/ORGANIZATION/OTHER） */
    private String objectType;

    /** 说明 */
    private String description;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
