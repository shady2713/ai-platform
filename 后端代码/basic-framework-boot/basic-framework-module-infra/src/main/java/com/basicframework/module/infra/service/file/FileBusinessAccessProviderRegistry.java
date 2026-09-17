package com.basicframework.module.infra.service.file;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_BUSINESS_TYPE_UNREGISTERED;

import com.basicframework.module.infra.api.file.FileBusinessAccessProvider;
import com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext;
import com.basicframework.module.infra.api.file.dto.FileSubjectDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 业务文件授权 Provider 注册表。
 *
 * <p>启动期建立"业务类型 → Provider"索引：空白业务类型、重复注册都直接抛
 * {@link IllegalStateException} 让应用启动失败；查询时未注册的业务类型一律拒绝（fail-closed），
 * 不回退到管理权限。零 Provider 是合法状态（仓库内 AI 实现落地前），此时任何业务绑定文件都不可读。
 */
@Component
public class FileBusinessAccessProviderRegistry {

    private final Map<String, FileBusinessAccessProvider> providers;

    public FileBusinessAccessProviderRegistry(List<FileBusinessAccessProvider> providerList) {
        Map<String, FileBusinessAccessProvider> indexed = new HashMap<>();
        for (FileBusinessAccessProvider provider : providerList) {
            String businessType = provider.getBusinessType();
            if (businessType == null || businessType.isBlank()) {
                throw new IllegalStateException("业务文件授权 Provider 必须声明业务类型");
            }
            if (indexed.putIfAbsent(businessType, provider) != null) {
                throw new IllegalStateException("业务类型 " + businessType + " 存在重复的文件授权 Provider");
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    /** 是否已注册该业务类型。 */
    public boolean isRegistered(String businessType) {
        return providers.containsKey(businessType);
    }

    /**
     * 要求业务类型已注册；未注册时抛业务异常，避免创建出永远不可读的文件。
     */
    public void requireRegistered(String businessType) {
        if (!isRegistered(businessType)) {
            throw exception(FILE_BUSINESS_TYPE_UNREGISTERED, businessType);
        }
    }

    /** 读取授权：未注册业务类型一律拒绝。 */
    public boolean canRead(FileBusinessAccessContext context) {
        FileBusinessAccessProvider provider = context == null ? null : providers.get(context.getBusinessType());
        return provider != null && provider.canRead(context);
    }

    /** 删除授权：未注册业务类型或未实现 canDelete 一律拒绝。 */
    public boolean canDelete(FileBusinessAccessContext context) {
        FileBusinessAccessProvider provider = context == null ? null : providers.get(context.getBusinessType());
        return provider != null && provider.canDelete(context);
    }

    /** 构造判定上下文。 */
    public static FileBusinessAccessContext buildContext(
            Long fileId, String businessType, Long businessId, FileAccessPrincipal principal) {
        FileSubjectDTO subject = new FileSubjectDTO();
        subject.setUserId(principal.userId());
        subject.setUserType(principal.userType());
        FileBusinessAccessContext context = new FileBusinessAccessContext();
        context.setFileId(fileId);
        context.setBusinessType(businessType);
        context.setBusinessId(businessId);
        context.setSubject(subject);
        return context;
    }
}
