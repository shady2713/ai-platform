package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 按"统一对象 + 显式版本"判定源键的请求（Y02）。
 *
 * <p>{@code revisionNo} 必填：判定路径**没有**"取最新版本"的省略写法——旧报表/旧产物按受理时的
 * 版本解释，而"当前版本"由调用方先读取对象事实再显式传入（换版本不改旧结果的结构性保证）。
 */
@Data
@Accessors(chain = true)
public class AiMasterMappingResolveDTO {

    /** 统一对象标识 */
    private String objectCode;

    /** 映射版本号（显式指定，不接受"最新"） */
    private Long revisionNo;

    /** 目标系统（接入应用）编号 */
    private Long applicationId;

    /** 该系统内的实体类型 */
    private String entityType;

    /** 判定时刻（有效期按此时刻计算；为空表示不按有效期过滤会拒绝，见服务层校验） */
    private LocalDateTime asOf;
}
