package com.basicframework.module.ai.service.semantic;

import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogQueryDTO;

/**
 * 主数据映射的**目录发现面**（Y02）：某个主体在指定对象/版本下能看到哪些跨系统映射事实。
 *
 * <p>与 Y01 的授权发现同构的四条不变量：
 * <ol>
 *   <li><b>只读事实</b>：每次调用重新读取对象、版本、条目与主体的可访问系统，不缓存、不写状态；</li>
 *   <li><b>无权不出现</b>：主体在某系统没有可访问范围时，该系统在该版本里的映射条目不出现在结果里
 *       （也不出现在送进模型的目录里）；</li>
 *   <li><b>拒绝不可区分</b>：主体不可用/无任何可访问系统时返回 {@code denied=true} 的空目录，
 *       与"主体从未登记"完全同形；</li>
 *   <li><b>预算有界</b>：可见条目超过单次发现预算即拒绝（稳定错误码），不返回部分目录。</li>
 * </ol>
 *
 * <p>发现负责**暴露问题**（冲突/过期条目照样列出并标注），判定（{@link AiMasterMappingResolver}）
 * 负责**阻断**：两者读同一份已核验事实，所以页面看到的与判定用的不会有两套结论。
 */
public interface AiMasterObjectCatalogService {

    /** 发现某主体在指定对象/版本下可见的映射事实。 */
    AiMasterObjectCatalogDTO discover(AiMasterObjectCatalogQueryDTO queryDTO);
}
