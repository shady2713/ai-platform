package com.basicframework.module.infra.dal.dataobject.file;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import lombok.*;

/**
 * 文件表
 * 每次文件上传，都会记录一条记录到该表中
 *
 */
@TableName("infra_file")
@KeySequence("infra_file_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDO extends BaseDO {

    /**
     * 编号，数据库自增
     */
    private Long id;
    /**
     * 配置编号
     *
     * 关联 {@link FileConfigDO#getId()}
     */
    private Long configId;
    /**
     * 原文件名
     */
    private String name;
    /**
     * 路径，即文件名
     */
    private String path;
    /**
     * 访问地址
     */
    private String url;
    /**
     * 文件的 MIME 类型，例如 "application/octet-stream"
     */
    private String type;
    /**
     * 文件大小
     */
    private Long size;
    /** 读取策略：1 公开读取，2 私有读取。 */
    private Integer accessType;
    /** 预签名上传状态：0 等待上传，1 已完成，2 校验中。 */
    private Integer uploadStatus;
    /** 校验前的私有临时对象路径，发布后清空。 */
    private String uploadStagingPath;
    /** 一次性上传令牌的 SHA-256 摘要，原始令牌不落库。 */
    @ToString.Exclude
    private String uploadTokenHash;
    /** 预签名上传过期时间。 */
    private LocalDateTime uploadExpiresAt;
    /** 签发令牌的用户编号，用于完成请求幂等重试与所有权校验。 */
    private Long uploadUserId;
    /** 签发令牌的用户类型，用于完成请求幂等重试与所有权校验。 */
    private Integer uploadUserType;
    /** 文件所有者用户编号；私有文件只允许该主体或文件管理员读取。 */
    private Long ownerUserId;
    /** 文件所有者用户类型；与 ownerUserId 共同组成主体标识。 */
    private Integer ownerUserType;

    /**
     * 业务类型：非空表示该文件受业务授权 SPI 管控（读取/删除由业务模块判定）
     */
    private String businessType;
    /**
     * 业务对象编号
     */
    private Long businessId;
    /** 删除状态：0 正常，1 等待清理外部存储。 */
    private Integer deleteStatus;
    /** 外部存储清理失败次数。 */
    private Integer deleteAttempts;
    /** 下次允许重试清理的时间。 */
    private LocalDateTime deleteNextRetryTime;
    /** 最近一次失败的异常类型，不保存异常消息与文件路径。 */
    private String deleteLastError;
}
