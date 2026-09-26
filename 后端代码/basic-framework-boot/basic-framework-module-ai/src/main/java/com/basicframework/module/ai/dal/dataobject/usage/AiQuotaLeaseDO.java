package com.basicframework.module.ai.dal.dataobject.usage;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 并发配额占位（Q02）：带租约，**到期即可回收**。
 *
 * <p>为什么用租约而不是"计数 + 减一"：进程崩溃时计数会永久占位（AT-059 的失败分支）。
 * 这里每个占位都有 `lease_until`，到期即视为空闲（清理任务或下一次占位时顺手回收）；
 * 长调用可以续租，终态显式释放。
 */
@TableName("ai_quota_lease")
@KeySequence("ai_quota_lease_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiQuotaLeaseDO extends BaseDO {

    /** 状态：持有中 */
    public static final String STATE_ACTIVE = "ACTIVE";

    /** 状态：已释放 */
    public static final String STATE_RELEASED = "RELEASED";

    /** 占位编号 */
    @TableId
    private Long id;

    /** 占位键（应用:服务:调用标识，唯一） */
    private String leaseKey;

    /** 应用编号 */
    private Long applicationId;

    /** 服务编号 */
    private Long serviceId;

    /** 调用标识（与账本同口径） */
    private String invocationId;

    /** 持有者（进程/实例标识） */
    private String holderRef;

    /** 状态（ACTIVE/RELEASED） */
    private String state;

    /** 租约到期时间 */
    private LocalDateTime leaseUntil;

    /** 乐观锁版本 */
    private Integer version;
}
