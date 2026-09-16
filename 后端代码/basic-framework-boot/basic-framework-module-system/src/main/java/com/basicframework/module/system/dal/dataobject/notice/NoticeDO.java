package com.basicframework.module.system.dal.dataobject.notice;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@TableName("system_notice")
@KeySequence("system_notice_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NoticeDO extends SoftDeletableDO {

    @TableId
    private Long id;

    private String title;

    private String content;

    private Integer type;

    private Integer status;
}
