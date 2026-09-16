package com.basicframework.module.system.service.auth.dto;

import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 已通过密码认证的会话结果。 */
@Data
@ToString(exclude = {"accessToken", "refreshToken"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginResultDTO {

    private Long userId;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime expiresTime;

    public static AuthLoginResultDTO token(UserSessionDO token) {
        return AuthLoginResultDTO.builder()
                .userId(token.getUserId())
                .accessToken(token.getAccessToken())
                .refreshToken(token.getRefreshToken())
                .expiresTime(token.getAccessExpiresTime())
                .build();
    }
}
