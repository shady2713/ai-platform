package com.basicframework.module.ai.controller.app.v1.embed;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_APP_NOT_EXISTS;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.service.application.AiApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 嵌入入口的应用解析（C05）：`appCode` → **已启用**应用。
 *
 * <p>三条口径：
 * <ol>
 *   <li><b>精确匹配</b>：公开入口只认完全相等的 `appCode`（开放服务的应用标识唯一），
 *       否则 `crm` 的入口会命中 `crm-portal`；</li>
 *   <li><b>未启用与不存在同语义</b>：停用应用返回 404，不暴露"这个应用存在但被停用"；</li>
 *   <li><b>提前退出</b>：按页扫描的页数有上限，配置异常（例如标识被大量前缀相同的应用淹没）时宁可 404，
 *       也不做无界查询。</li>
 * </ol>
 *
 * <p>说明：模块内已有 `AiApplicationService.getApplicationPage(appCode)`（模糊匹配），
 * 本类在其上做精确过滤；卡片允许路径不含 `service/application`，故未新增"按标识精确查询"的服务方法，
 * 该依赖缺口记在 C05 证据文档的"上游差异"里。
 */
@Component
@RequiredArgsConstructor
public class AiEmbedApplicationResolver {

    private static final int SCAN_PAGE_SIZE = 100;

    private static final int MAX_SCAN_PAGES = 10;

    private final AiApplicationService applicationService;

    /** 解析已启用应用；未知、停用或标识非法都抛同一稳定错误码（404）。 */
    public AiApplicationDO resolveEnabled(String appCode) {
        if (!StringUtils.hasText(appCode) || appCode.length() > 64) {
            throw exception(AI_EMBED_APP_NOT_EXISTS);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageSize(SCAN_PAGE_SIZE);
        for (int pageNo = 1; pageNo <= MAX_SCAN_PAGES; pageNo++) {
            pageParam.setPageNo(pageNo);
            PageResult<AiApplicationDO> page = applicationService.getApplicationPage(pageParam, appCode, null);
            for (AiApplicationDO application : page.getList()) {
                if (appCode.equals(application.getAppCode()) && Boolean.TRUE.equals(application.getEnabled())) {
                    return application;
                }
            }
            if (page.getList().size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        throw exception(AI_EMBED_APP_NOT_EXISTS);
    }
}
