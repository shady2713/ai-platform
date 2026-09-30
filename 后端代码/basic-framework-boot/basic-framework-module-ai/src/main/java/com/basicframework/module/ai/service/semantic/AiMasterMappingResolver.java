package com.basicframework.module.ai.service.semantic;

import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolutionDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseResultDTO;

/**
 * 主数据映射的**判定面**（Y02）：回答"这条标识在某个版本下是谁"，不做任何写操作。
 *
 * <p>两条路径都按同一份事实（已发布版本 + 冻结指纹 + 有效期）判定，并且都**显式要求判定时刻**：
 * 报表按受理时刻解释、查询按数据时间窗解释，平台不隐式取"服务器现在"。
 *
 * <p>失败分支全部是稳定错误码，绝不"挑一个"：
 * <ul>
 *   <li>版本未发布 → 409（草稿不是事实）；版本有效期不覆盖判定时刻 → 409；</li>
 *   <li>版本内容指纹与冻结值不符 → 409（内容被版本外改动）；</li>
 *   <li>该（对象, 系统, 实体类型）下无登记 → 404；有登记但有效期不覆盖判定时刻 → 409；</li>
 *   <li>一对多/多对一冲突 → 409（阻断，不排序取第一个）；对象已停用 → 409。</li>
 * </ul>
 *
 * <p>唯一"不报错"的否定结论是**未登记**（{@code mapped=false}）：跨系统统计因此可以明确地说
 * "这两条记录没有映射，不能合并"，而不是拿冲突/过期当"没映射"静默降级。
 */
public interface AiMasterMappingResolver {

    /** 按统一对象 + 显式版本判定某系统里的源键（结果携带版本指纹，供调用方固定）。 */
    AiMasterMappingResolutionDTO resolveObjectKey(AiMasterMappingResolveDTO resolveDTO);

    /** 按源键反查统一对象（未登记返回 mapped=false；冲突/过期/停用阻断）。 */
    AiMasterMappingReverseResultDTO resolveSourceKey(AiMasterMappingReverseDTO reverseDTO);
}
