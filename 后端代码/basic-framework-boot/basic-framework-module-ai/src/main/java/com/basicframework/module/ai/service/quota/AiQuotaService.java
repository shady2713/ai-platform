package com.basicframework.module.ai.service.quota;

import com.basicframework.module.ai.service.quota.dto.AiQuotaAcquireDTO;

/**
 * 并发配额占位（Q02）：带租约、可续租、到期可回收。
 *
 * <p>语义：
 * <ul>
 *   <li>`acquire` 在并发占用已达上限时返回"未获得"（调用方回 429 并说明原因），不排队不阻塞；</li>
 *   <li>占位带租约：进程崩溃后到期即被回收，**不会永久占位**；长调用可 `renew` 续租；</li>
 *   <li>同一调用标识重复申请不叠加（幂等），终态用 `release` 显式释放。</li>
 * </ul>
 */
public interface AiQuotaService {

    /** 申请占位：true=已获得（含幂等重入），false=并发已满。 */
    boolean acquire(AiQuotaAcquireDTO request);

    /** 续租（CAS；占位已被回收时返回 false，调用方应视为失败并停止工作）。 */
    boolean renew(String leaseKey, java.time.Duration lease);

    /** 释放（终态；幂等）。 */
    void release(String leaseKey);

    /** 当前有效占位数（未释放且未到期）。 */
    long activeCount(Long applicationId);
}
