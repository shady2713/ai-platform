package com.basicframework.module.system.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.system.service.integrity.SystemDataIntegrityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 审计 system 模块逻辑引用完整性，异常由 Quartz 任务日志持久化。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemDataIntegrityAuditJob implements JobHandler {

    private final SystemDataIntegrityService integrityService;

    @Override
    public String execute(String param) {
        String summary = integrityService.verifyLogicalReferences();
        log.info("[execute][{}]", summary);
        return summary;
    }
}
