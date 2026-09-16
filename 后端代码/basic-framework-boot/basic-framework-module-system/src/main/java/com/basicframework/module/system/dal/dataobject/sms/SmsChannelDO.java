package com.basicframework.module.system.dal.dataobject.sms;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 短信渠道 DO
 *
 * @since 2021-01-25
 */
@TableName(value = "system_sms_channel", autoResultMap = true)
@KeySequence("system_sms_channel_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SmsChannelDO extends SoftDeletableDO {

    /**
     * 渠道编号
     */
    private Long id;
    /**
     * 短信签名
     */
    private String signature;
    /**
     * 渠道编码
     *
     * 枚举 {@link SmsChannelEnum}
     */
    private String code;
    /**
     * 启用状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;
    /**
     * 备注
     */
    private String remark;
    /** 短信 API 账号的版本化密文。 */
    @ToString.Exclude
    @TableField("api_key")
    private String apiKeyCiphertext;

    /** 仅用于命令入参和客户端初始化，不持久化。 */
    @ToString.Exclude
    @TableField(exist = false)
    private String apiKey;
    /** 短信 API 密钥的版本化密文。 */
    @ToString.Exclude
    @TableField("api_secret")
    private String apiSecretCiphertext;

    /** 仅用于命令入参和客户端初始化，不持久化。 */
    @ToString.Exclude
    @TableField(exist = false)
    private String apiSecret;
    /**
     * 短信发送回调 URL
     */
    private String callbackUrl;
}
