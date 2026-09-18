package com.basicframework.module.ai.adapter.file;

import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.infra.api.file.FileBusinessAccessProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 业务文件授权 Provider 装配（A07）：每种业务类型一个 Bean。
 *
 * <p>infra 的注册表要求"每个业务类型恰好一个实现"：重复注册或未注册都会在启动期/读取时失败（fail-closed）。
 * 三种类型共用同一实现，只是业务类型与资源类型不同。
 */
@Configuration(proxyBeanMethods = false)
public class AiFileAccessProviderConfiguration {

    @Bean
    public FileBusinessAccessProvider aiReportFileAccessProvider(
            AiFileBindingMapper bindingMapper,
            AiFileSubjectResolver subjectResolver,
            AiAuthorizationService authorizationService) {
        return new AiFileBusinessAccessProvider(
                AiFileBusinessType.REPORT.code(),
                bindingMapper,
                subjectResolver,
                authorizationService,
                AiResourceType.REPORT);
    }

    @Bean
    public FileBusinessAccessProvider aiKnowledgeDocumentFileAccessProvider(
            AiFileBindingMapper bindingMapper,
            AiFileSubjectResolver subjectResolver,
            AiAuthorizationService authorizationService) {
        return new AiFileBusinessAccessProvider(
                AiFileBusinessType.KNOWLEDGE_DOCUMENT.code(),
                bindingMapper,
                subjectResolver,
                authorizationService,
                AiResourceType.KNOWLEDGE_BASE);
    }

    @Bean
    public FileBusinessAccessProvider aiChatSessionFileAccessProvider(
            AiFileBindingMapper bindingMapper, AiFileSubjectResolver subjectResolver) {
        return new AiFileBusinessAccessProvider(
                AiFileBusinessType.CHAT_SESSION.code(), bindingMapper, subjectResolver, null, null);
    }
}
