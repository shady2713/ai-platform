package com.basicframework.module.ai.service.report.refresh;

import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshRequestDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshResultDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshStateDTO;

/**
 * 报表刷新（R06）：可刷新报表按**当前权限**重新执行固定查询版本，结果原子切换。
 *
 * <p>五条不变量（与卡片逐步实施一致）：
 * <ol>
 *   <li><b>按当前权限执行固定查询版本</b>：来源（数据集/语义版本）先按当前状态复核可执行性，
 *       计划来自版本里保存的已校验计划（不重新规划、不调用模型）；</li>
 *   <li><b>原子切换</b>：全部查询与绑定成功后才新增版本并用乐观锁 CAS 推进生效版本；
 *       任一步失败都不新增版本，旧结果保持可读（AT-047）；</li>
 *   <li><b>漂移/停用/失权即停</b>：数据集停用、版本未发布/未验证/已漂移、范围指纹不再覆盖，
 *       都以稳定原因停止并留痕（不继续执行、不退回全库）；</li>
 *   <li><b>重复刷新幂等</b>：上游数据与上次成功刷新一致时不产生新版本（UNCHANGED）；</li>
 *   <li><b>旧结果同样按当前 ACL</b>：读取上一次结果前逐项复核范围指纹，失权即拒绝（AT-048）。</li>
 * </ol>
 */
public interface AiReportRefreshService {

    /** 刷新一张可刷新报表（OK/UNCHANGED/FAILED 三种正常结果）。 */
    AiReportRefreshResultDTO refresh(AiReportRefreshRequestDTO request);

    /** 查询最近一次刷新状态与上一次结果（读取结果前按当前 ACL 复核）。 */
    AiReportRefreshStateDTO lastState(Long reportId);
}
