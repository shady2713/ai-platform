package com.basicframework.module.ai.dal.dataobject.federation;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * AI 跨系统主体联邦映射（Y01）：把"某个系统的某个外部主体"显式映射到"另一个系统的某个外部主体"。
 *
 * <p>四条不变量：
 * <ul>
 *   <li><b>不按同名推断</b>：来源与目标主体都是服务端登记事实（{@code external_user_id} 只是字符串，
 *       在两个应用里相同**不等于**同一个人）；</li>
 *   <li><b>独立审批</b>：只有 {@link #STATUS_APPROVED} 行参与授权发现，且批准人必须不同于提交人
 *       （服务层校验，{@code requested_by <> approved_by}）；</li>
 *   <li><b>撤销立即生效</b>：状态转 {@link #STATUS_REVOKED} 后，下一次授权发现即不再包含该目标系统
 *       （读取路径不做缓存，也没有常驻扫描任务）；</li>
 *   <li><b>事实可核验</b>：{@code revision} 随批准/撤销递增，范围选择的指纹把映射版本纳入计算，
 *       历史选择在映射被撤销后校验失败而不是静默换目标。</li>
 * </ul>
 *
 * <p>唯一键是六段身份（来源应用/类型/外部标识 × 目标应用/类型/外部标识）：同一身份对只有一行，
 * 状态在行上迁移（PENDING → APPROVED → REVOKED，撤销后再次提交复用该行）。
 */
@TableName("ai_subject_federation")
@KeySequence("ai_subject_federation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiSubjectFederationDO extends SoftDeletableDO {

    /** 状态：已提交，等待独立审批（不参与发现） */
    public static final String STATUS_PENDING = "PENDING";

    /** 状态：已批准（唯一参与授权发现的状态） */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 状态：已撤销（立即不参与发现；重新提交复用该行） */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 映射编号 */
    @TableId
    private Long id;

    /** 来源系统（应用）编号：当前会话主体所属应用 */
    private Long sourceApplicationId;

    /** 来源主体类型（USER/APP） */
    private String sourceSubjectType;

    /** 来源主体外部用户标识（APP 主体为空串） */
    private String sourceExternalUserId;

    /** 目标系统（应用）编号：被联邦的另一个业务系统接入 */
    private Long targetApplicationId;

    /** 目标主体类型（USER/APP） */
    private String targetSubjectType;

    /** 目标主体外部用户标识 */
    private String targetExternalUserId;

    /** 状态（PENDING/APPROVED/REVOKED） */
    private String status;

    /** 提交人（后台操作员编号） */
    private Long requestedBy;

    /** 提交时间 */
    private LocalDateTime requestedTime;

    /** 批准人（必须与提交人不同） */
    private Long approvedBy;

    /** 批准时间 */
    private LocalDateTime approvedTime;

    /** 审批说明（≤200 字符） */
    private String approvalNote;

    /** 映射版本（提交=1，批准/撤销递增） */
    private Long revision;

    /** 乐观锁版本（批准/撤销 CAS） */
    private Integer version;
}
