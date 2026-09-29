package com.basicframework.module.ai.service.report.share.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 创建报表分享请求（服务层 DTO）：归属来自服务端会话身份，请求体只给"分享给谁、哪个版本、到什么时候"。
 */
@Data
@Accessors(chain = true)
public class AiReportShareCreateDTO {

    /** 报表编号（必须是当前主体的报表，他人报表与不存在同语义 404） */
    private Long reportId;

    /** 版本号（空取报表当前最新版本；必须真实存在） */
    private Integer versionNo;

    /** 接收者外部用户标识（必须是同应用内的可用 USER 主体，且不能是自己） */
    private String granteeExternalUserId;

    /** 过期时间（空为长期有效；给了就必须在未来） */
    private LocalDateTime expiresTime;
}
