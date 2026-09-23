package com.basicframework.module.ai.dal.dataobject.report;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 报表版本（R04）：不可变快照。
 *
 * <p>不可变是**结构性的**：没有"更新版本内容"的服务方法，保存/对话修改只会新增版本号；
 * 读取时按 {@code scopeFingerprint} 复核当前范围（第 4 步）。
 */
@TableName("ai_report_version")
@KeySequence("ai_report_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiReportVersionDO extends SoftDeletableDO {

    /** 版本编号 */
    @TableId
    private Long id;

    /** 报表编号 */
    private Long reportId;

    /** 版本号（报表内递增） */
    private Integer versionNo;

    /** 模式（SNAPSHOT/REFRESHABLE） */
    private String mode;

    /** ReportSpec（已校验） */
    private String specJson;

    /** 快照数据（SNAPSHOT 模式） */
    private String dataJson;

    /** 来源与资源依赖 */
    private String sourcesJson;

    /** 逐项资源依赖的 A03 范围指纹（读取时逐项复核；空数组表示该版本未声明资源依赖） */
    private String scopeRefsJson;

    /** 保存时的授权范围指纹（读取时比对） */
    private String scopeFingerprint;

    /** 数据截至时间（快照模式） */
    private java.time.LocalDateTime asOf;

    /** 数据完整性（COMPLETE/PARTIAL/FAILED） */
    private String completeness;

    /** 来源运行标识 */
    private String createdByRun;

    /** 乐观锁版本 */
    private Integer version;
}
