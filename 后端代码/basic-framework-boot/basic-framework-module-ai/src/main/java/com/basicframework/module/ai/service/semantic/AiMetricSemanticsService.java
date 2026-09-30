package com.basicframework.module.ai.service.semantic;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsRevisionDO;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsRevisionDraftDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsSaveDTO;
import java.time.LocalDateTime;

/**
 * 跨源指标口径的**管理面与判定面**（Y03）。
 *
 * <p>四条不变量（与 Y02 同构，但对象是"口径"而不是"标识对应"）：
 * <ol>
 *   <li><b>口径标识不可修改</b>：登记后只能改名称/说明/状态；历史报表按标识引用口径；</li>
 *   <li><b>版本不可变</b>：草稿（DRAFT）可改定义，发布（PUBLISHED）后定义与指纹冻结，
 *       改口径只能新建版本——"换口径版本不改旧结果"由结构保证；</li>
 *   <li><b>发布是独立审核</b>：发布人必须不同于草稿创建人；</li>
 *   <li><b>判定必须显式钉住版本与时刻</b>：{@link #resolveVerified} 要求口径标识 + 版本号 + 时刻，
 *       没有"取最新版""取服务器当前时间"的省略写法。</li>
 * </ol>
 */
public interface AiMetricSemanticsService {

    /** 新建跨源指标口径（标识全局唯一且不可修改）。 */
    Long createSemantics(AiMetricSemanticsSaveDTO saveDTO);

    /** 启停口径（停用后一切跨源聚合阻断，不回退到历史版本）。 */
    void updateSemanticsStatus(Long id, Integer version, boolean enabled);

    /** 按标识查询口径（不存在抛 404 语义）。 */
    AiMetricSemanticsDO getSemanticsByCode(String metricCode);

    /** 口径分页（按状态过滤，关键字匹配标识或名称）。 */
    PageResult<AiMetricSemanticsDO> getSemanticsPage(PageParam pageParam, String status, String keyword);

    /** 新建草稿版本（定义会先经 {@link AiMetricSemantics} 解析校验：一个口径同时只允许一个草稿）。 */
    Long createRevision(AiMetricSemanticsRevisionDraftDTO draftDTO);

    /** 发布草稿版本（口径一致性自检 + 独立审核 + 冻结指纹，并推进口径的当前版本）。 */
    AiMetricSemanticsRevisionDO publishRevision(Long metricSemanticsId, Long revisionNo, Integer version);

    /**
     * 读取并核验已发布版本，返回可用的口径定义。
     *
     * <p>四条检查任一不通过都阻断（而不是降级读别的版本）：版本存在 → 已发布 →
     * 有效期覆盖判定时刻 → 重算指纹等于冻结指纹。
     */
    AiMetricSemantics resolveVerified(String metricCode, Long revisionNo, LocalDateTime asOf);
}
