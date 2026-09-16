package com.basicframework.module.infra.job.file;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.infra.service.file.FileDeletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 重试清理已逻辑隐藏、但外部存储对象尚未确认删除的文件。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FileDeletionRetryJob implements JobHandler {

    private final FileDeletionService fileDeletionService;

    @Override
    public String execute(String param) {
        int deleted = fileDeletionService.retryPendingFiles();
        String summary = "文件待删除记录清理完成: deleted=" + deleted;
        log.info("[execute][{}]", summary);
        return summary;
    }
}
