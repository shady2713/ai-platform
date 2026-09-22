package com.basicframework.module.ai.adapter.knowledge;

import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeSourceReader;
import com.basicframework.module.infra.api.file.FileCommonApi;
import com.basicframework.module.infra.api.file.dto.FileReadReqDTO;
import com.basicframework.module.infra.api.file.dto.FileSubjectDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 原文读取适配器（K05）：经 infra 的受控文件接口读取，主体取自**当前安全上下文**。
 *
 * <p>为什么坚持走这条路径而不是直接读存储：文件读取的授权由 A07 的业务 Provider 判定
 * （知识文档按 A03 的 KNOWLEDGE_BASE 授权 + 绑定归属），绕过它就等于绕过业务 ACL。
 *
 * <p>后台线程的限制：入库 Job 默认没有主体上下文，此时读取**失败**（`source-subject_missing`），
 * 而不是"以系统身份读所有文件"。谁在什么授权下读取原文是 K07/K08 要落地的策略；
 * 在这之前，无主体即失败（fail-closed）。
 */
@Component
@RequiredArgsConstructor
public class FileApiKnowledgeSourceReader implements AiKnowledgeSourceReader {

    private final FileCommonApi fileCommonApi;

    @Override
    public KnowledgeSource read(Long fileId) {
        if (fileId == null) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Reason.NOT_ACCESSIBLE, null);
        }
        LoginUser loginUser = currentLoginUser();
        if (loginUser == null) {
            throw new KnowledgeSourceException(KnowledgeSourceException.Reason.SUBJECT_MISSING, null);
        }
        try {
            FileReadReqDTO request = new FileReadReqDTO()
                    .setFileId(fileId)
                    .setSubject(new FileSubjectDTO()
                            .setUserType(loginUser.getUserType())
                            .setUserId(loginUser.getId()));
            byte[] content = fileCommonApi.getFileContent(request);
            if (content == null || content.length == 0) {
                throw new KnowledgeSourceException(KnowledgeSourceException.Reason.NOT_ACCESSIBLE, "empty");
            }
            // 文件名只用于格式识别：解析器按扩展名选择解析路径
            String fileName = fileCommonApi.getFileMeta(request).getName();
            return new KnowledgeSource(content, fileName);
        } catch (KnowledgeSourceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // 无权限与不存在同语义（防枚举）；只记录异常类型
            throw KnowledgeSourceException.of(KnowledgeSourceException.Reason.NOT_ACCESSIBLE, failure);
        }
    }

    private static LoginUser currentLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            return null;
        }
        return loginUser;
    }
}
