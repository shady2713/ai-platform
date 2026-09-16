package com.basicframework.module.system.dal.dataobject.session;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** 用户会话。原始访问令牌和刷新令牌只存在于签发结果，不进入数据库、缓存或 JSON。 */
@TableName(value = "system_user_session", autoResultMap = true)
@KeySequence("system_user_session_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserSessionDO extends BaseDO {

    @TableId
    private Long id;

    @TableField(exist = false)
    @JsonIgnore
    @ToString.Exclude
    private String accessToken;

    @ToString.Exclude
    private String accessTokenHash;

    @TableField(exist = false)
    @JsonIgnore
    @ToString.Exclude
    private String refreshToken;

    @ToString.Exclude
    private String refreshTokenHash;

    /** 稳定撤销凭据的摘要；兼容升级前的会话时允许为空。 */
    @ToString.Exclude
    private String refreshFamilyHash;

    private Long userId;

    /** 枚举 {@link UserTypeEnum}。 */
    private Integer userType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> userInfo;

    private LocalDateTime accessExpiresTime;

    /** 固定绝对过期时间，刷新会话时不延长。 */
    private LocalDateTime refreshExpiresTime;
}
