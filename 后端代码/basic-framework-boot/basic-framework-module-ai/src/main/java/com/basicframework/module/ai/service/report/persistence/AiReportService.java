package com.basicframework.module.ai.service.report.persistence;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import java.util.List;

/**
 * 报表保存与版本存储（R04）。
 *
 * <p>五条不变量（对应卡片逐步实施）：
 * <ol>
 *   <li><b>归属来自会话身份</b>：报表属于"应用 + 主体类型 + 外部用户标识"，
 *       保存他人运行的结果会被拒绝（跨用户保存他人 run 拒绝）；</li>
 *   <li><b>版本不可修改</b>：保存与对话修改都只**新增版本**，历史版本只读；</li>
 *   <li><b>乐观锁防并发覆盖</b>：保存新版本必须带当前 version，冲突返回 409（AT-046）；</li>
 *   <li><b>范围指纹</b>：每个版本记录保存时的授权范围指纹；读取时若当前范围无法证明覆盖原范围
 *       （指纹不一致）则**拒绝显示**（AT-048）；</li>
 *   <li><b>快照与可刷新分开</b>：快照版本带数据截至时间；可刷新版本不带数据，刷新时按当前权限重新执行。</li>
 * </ol>
 */
public interface AiReportService {

    /** 新建报表并保存首个版本（快照模式必须带数据）。 */
    Long create(AiReportSaveDTO saveDTO);

    /** 保存新版本（乐观锁；历史版本不可修改）。 */
    Long saveVersion(AiReportSaveDTO saveDTO);

    /** 查询报表（归属不符与不存在同语义）。 */
    AiReportDO getReport(Long id);

    /** 分页查询当前主体的报表。 */
    PageResult<AiReportDO> getReportPage(PageParam pageParam, String mode);

    /** 查询版本（归属不符与不存在同语义）。 */
    AiReportVersionDO getVersion(Long reportId, Integer versionNo);

    /** 某报表的版本列表（版本号倒序）。 */
    List<AiReportVersionDO> listVersions(Long reportId);

    /**
     * 读取当前生效版本：先做归属判定，再做范围指纹复核；
     * 指纹不一致（当前范围无法证明覆盖保存时范围）时抛 {@code AI_REPORT_SCOPE_CHANGED}。
     */
    AiReportVersionDO readCurrent(Long reportId);
}
