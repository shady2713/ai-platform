package com.basicframework.module.ai.service.application;

import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;

/**
 * 多系统授权发现（Y01 的 "授权发现"）。
 *
 * <p>职责：对给定外部主体算出"在哪些系统里有可访问范围"的目录。三条不变量：
 * <ol>
 *   <li><b>只读事实</b>：每次调用都重新读取主体、范围解析结果、授权目录与已批准联邦映射，
 *       不使用缓存、不写入任何状态（撤销后下一次调用立即反映）；</li>
 *   <li><b>无权不出现</b>：主体在该系统不可用、范围被拒、没有 ACTIVE 授权、或联邦映射未批准，
 *       该系统都不出现在目录里；</li>
 *   <li><b>拒绝不可区分</b>：无法确定时返回 {@code denied=true} 的空目录（与"主体未登记"
 *       完全同形），既不枚举主体也不枚举系统。</li>
 * </ol>
 */
public interface AiSystemCatalogService {

    /** 发现当前主体的可访问系统目录。 */
    AiSystemCatalogDTO discover(AiSystemCatalogQueryDTO query);
}
