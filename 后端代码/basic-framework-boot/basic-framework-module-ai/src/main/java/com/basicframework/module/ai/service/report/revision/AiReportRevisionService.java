package com.basicframework.module.ai.service.report.revision;

import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionRequestDTO;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionResultDTO;

/**
 * 报表对话修改（R05）。
 *
 * <p>三步与卡片逐步实施一致：
 * <ol>
 *   <li><b>区分展示类与数据类</b>：操作清单由模型给出，分类由服务端按操作码判定——
 *       展示类（换图类型/字段、改标题/布局/格式）复用基础版本数据，**不查库**；
 *       数据类（新增指标、改粒度、加过滤、换数据集）必须走受控查询；</li>
 *   <li><b>以 baseVersion 生成候选规格并验证来源与权限</b>：基础版本必须属于当前主体且
 *       保存时的授权范围仍被覆盖（R04 口径）；新数据集在查询前做 A03 READ 判定；
 *       候选规格过 R01 结构 + 语义校验并与真实结果绑定（声明与结果不符即拒绝）；</li>
 *   <li><b>成功才创建新版本</b>：保存走 R04 的乐观锁路径（并发修改 409，AT-046），
 *       历史版本只读——原版本永远不会被改写。</li>
 * </ol>
 */
public interface AiReportRevisionService {

    /** 执行一次对话修改（APPLIED 产生新版本；CLARIFICATION 只返回追问）。 */
    AiReportRevisionResultDTO revise(AiReportRevisionRequestDTO request);
}
