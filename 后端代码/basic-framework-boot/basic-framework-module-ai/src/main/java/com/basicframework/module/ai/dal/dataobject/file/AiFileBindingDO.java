package com.basicframework.module.ai.dal.dataobject.file;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** AI 业务文件绑定（A07）：文件与业务对象的引用关系 + 上传主体（所有者）。 */
@TableName("ai_file_binding")
@KeySequence("ai_file_binding_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiFileBindingDO extends SoftDeletableDO {

    /** 状态：有效引用 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：引用已解除 */
    public static final String STATUS_RELEASED = "RELEASED";

    /** 绑定编号 */
    @TableId
    private Long id;

    /** infra 文件编号 */
    private Long fileId;

    /** 业务类型（ai_report/ai_knowledge_document/ai_chat_session） */
    private String businessType;

    /** 业务对象标识 */
    private String businessKey;

    /** 上传主体所属应用 */
    private Long applicationId;

    /** 上传主体类型（APP/USER） */
    private String subjectType;

    /** 上传主体外部用户标识（所有者） */
    private String externalUserId;

    /** 状态（ACTIVE/RELEASED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
