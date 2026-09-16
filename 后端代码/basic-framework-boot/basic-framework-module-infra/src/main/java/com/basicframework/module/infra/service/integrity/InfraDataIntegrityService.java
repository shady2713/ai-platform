package com.basicframework.module.infra.service.integrity;

import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 检查 infra 模块无法由物理外键表达的逻辑引用完整性。 */
@Service
@RequiredArgsConstructor
public class InfraDataIntegrityService {

    private final FileMapper fileMapper;
    private final FileContentMapper fileContentMapper;

    /**
     * 检查已纳管的逻辑引用；发现孤儿时失败，由 Quartz 记录异常并触发任务告警。
     *
     * @return 稳定的审计摘要
     */
    public String verifyLogicalReferences() {
        int orphanFiles = fileMapper.selectOrphanFileConfigCount();
        if (orphanFiles > 0) {
            throw new IllegalStateException("infra_file.config_id 孤儿引用 " + orphanFiles + " 条");
        }
        int orphanFileContents = fileContentMapper.selectOrphanFileConfigCount();
        if (orphanFileContents > 0) {
            throw new IllegalStateException("infra_file_content.config_id 孤儿引用 " + orphanFileContents + " 条");
        }
        return "infra 逻辑引用完整性检查通过";
    }
}
