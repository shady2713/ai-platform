package com.basicframework.module.ai.dal.dataobject.semantic;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 企业统一对象（Y02）：跨系统主数据映射的锚点。
 *
 * <p>三条不变量：
 * <ul>
 *   <li><b>标识稳定且不可修改</b>：{@code object_code} 是平台内唯一标识，登记后只能改名称/说明/状态
 *       （改标识会让历史报表指向另一个对象）；</li>
 *   <li><b>判定必须显式指定版本</b>：{@code current_revision} 只表示"当前已发布的最新版本"，
 *       判定路径不接受"取最新"的省略写法，旧报表/旧产物按受理时的版本编号解释；</li>
 *   <li><b>停用即阻断</b>：{@code status = DISABLED} 时任何映射判定都被拒绝，不回退到历史版本。</li>
 * </ul>
 */
@TableName("ai_master_object")
@KeySequence("ai_master_object_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiMasterObjectDO extends SoftDeletableDO {

    /** 状态：可用。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：停用（判定阻断）。 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 统一对象编号 */
    @TableId
    private Long id;

    /** 统一对象标识（全局唯一，稳定且不可修改） */
    private String objectCode;

    /** 对象名称（仅展示，不参与判定） */
    private String objectName;

    /** 对象类型（AiMasterObjectType 词表） */
    private String objectType;

    /** 说明 */
    private String description;

    /** 状态（ACTIVE/DISABLED） */
    private String status;

    /** 当前已发布的映射版本（0=尚无已发布版本） */
    private Long currentRevision;

    /** 乐观锁版本 */
    private Integer version;
}
