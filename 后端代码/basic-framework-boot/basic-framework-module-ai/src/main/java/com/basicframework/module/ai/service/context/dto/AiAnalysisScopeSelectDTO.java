package com.basicframework.module.ai.service.context.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 范围选择请求（Y01）。
 *
 * <p>"显式"体现在三个字段缺一不可：模式、目标系统清单、以及**看到过的目录指纹**。
 * 没有"默认全部系统"这一档：不传目录指纹的调用会被拒绝，因为平台无法判断调用方看到的是哪一份
 * 授权事实（跨系统授权是易变事实，凭猜测扩大范围是越权）。
 */
@Data
@Accessors(chain = true)
public class AiAnalysisScopeSelectDTO {

    /** 当前应用（当前系统）编号 */
    private Long applicationId;

    /** 当前主体类型（USER/APP） */
    private String subjectType;

    /** 当前主体外部用户标识 */
    private String externalUserId;

    /** 选择模式（CURRENT_SYSTEM/CROSS_SYSTEM） */
    private String mode;

    /** 目标系统标识（CROSS_SYSTEM 必填且必须包含当前系统；CURRENT_SYSTEM 必须为空） */
    private List<String> targetSystemCodes;

    /** 调用方在发现接口观察到的目录指纹（必须与当前事实一致） */
    private String catalogFingerprint;
}
