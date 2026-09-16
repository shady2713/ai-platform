package com.basicframework.module.system.api.session.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;

/** 用户会话校验结果。 */
@Data
public class UserSessionCheckRespDTO implements Serializable {

    private Long userId;

    private Integer userType;

    private Map<String, String> userInfo;

    private LocalDateTime accessExpiresTime;
}
