package com.basicframework.module.infra.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.infra.service.integrity.InfraDataIntegrityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 审计 infra 模块逻辑引用完整性，异常由 Quartz 任务日志持久化。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InfraDataIntegrityAuditJob implements JobHandler {

    private final InfraDataIntegrityService integrityService;

    @Override
    public String execute(String param) {
        String summary = integrityService.verifyLogicalReferences();
        log.info("[execute][{}]", summary);
        return summary;
    }
}
