package com.basicframework.module.ai.service.file;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_AUTHORIZATION_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;

import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import com.basicframework.module.infra.api.file.FileCommonApi;
import com.basicframework.module.infra.api.file.dto.FileCreateReqDTO;
import com.basicframework.module.infra.api.file.dto.FileDeleteReqDTO;
import com.basicframework.module.infra.api.file.dto.FileReadReqDTO;
import com.basicframework.module.infra.api.file.dto.FileSubjectDTO;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 业务文件实现（A07）。
 *
 * <p>归属判定统一收敛到 {@link #canAccess}：
 * <ul>
 *   <li>REPORT / KNOWLEDGE_DOCUMENT：走 A03 授权目录（当前状态，无缓存）；</li>
 *   <li>CHAT_SESSION：仅所有者（上传主体）本人；</li>
 *   <li>未知业务类型：拒绝（上传时 400，读取时按不存在处理）。</li>
 * </ul>
 * 删除是"解除引用"：所有者才可解除；文件在没有其他有效引用时才真正删除。
 */
@Service
@RequiredArgsConstructor
public class AiFileServiceImpl implements AiFileService {

    private final AiFileBindingMapper bindingMapper;

    private final AiFileSubjectResolver subjectResolver;

    private final AiAuthorizationService authorizationService;

    private final FileCommonApi fileCommonApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiFileUploadResultDTO upload(
            String businessType, String businessKey, String name, String contentType, byte[] content) {
        AiFileBusinessType type =
                AiFileBusinessType.parse(businessType).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        if (!StringUtils.hasText(businessKey) || businessKey.length() > 128) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (content == null || content.length == 0 || !StringUtils.hasText(name)) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiFileSubject subject = currentSubject();
        // 上传即访问：报表/知识库需要当前授权；会话附件归属上传者本人
        if (!canAccess(type, businessKey, subject)) {
            throw exception(AI_AUTHORIZATION_DENIED);
        }
        Long fileId = fileCommonApi.createFile(new FileCreateReqDTO()
                .setContent(content)
                .setName(name)
                .setType(contentType)
                .setBusinessType(type.code())
                // infra 的业务编号列是数值列：AI 业务键是字符串，存储侧统一记为 0，
                // 真正的归属关系保存在 ai_file_binding
                .setBusinessId(0L)
                .setSubject(toFileSubject(subject)));
        AiFileBindingDO binding = new AiFileBindingDO()
                .setFileId(fileId)
                .setBusinessType(type.code())
                .setBusinessKey(businessKey)
                .setApplicationId(subject.applicationId())
                .setSubjectType(subject.subjectType().name())
                .setExternalUserId(subject.externalUserId())
                .setStatus(AiFileBindingDO.STATUS_ACTIVE)
                .setVersion(0);
        bindingMapper.insert(binding);
        return new AiFileUploadResultDTO()
                .setFileId(fileId)
                .setBusinessType(type.code())
                .setBusinessKey(businessKey)
                .setName(name)
                .setSize((long) content.length);
    }

    @Override
    public byte[] read(Long fileId) {
        AiFileSubject subject = currentSubject();
        AiFileBindingDO binding = requireActiveBinding(fileId);
        AiFileBusinessType type =
                AiFileBusinessType.parse(binding.getBusinessType()).orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
        // 授权目录类型按当前授权判定；会话附件这类"仅所有者"类型必须所有者本人
        if (!canAccess(type, binding.getBusinessKey(), subject) || !ownsWhenRequired(type, binding, subject)) {
            // 无权限与不存在同语义
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return fileCommonApi.getFileContent(
                new FileReadReqDTO().setFileId(fileId).setSubject(toFileSubject(subject)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long fileId) {
        AiFileSubject subject = currentSubject();
        AiFileBindingDO binding = requireActiveBinding(fileId);
        AiFileSubject.AiFileBindingOwner owner = new AiFileSubject.AiFileBindingOwner(
                binding.getApplicationId(), binding.getSubjectType(), binding.getExternalUserId());
        if (!subject.sameAs(owner)) {
            // 只有所有者可以解除引用；管理权限不参与业务判定
            throw exception(AI_AUTHORIZATION_DENIED);
        }
        // 共享引用未释放不得误删：除本次引用外还有其他 ACTIVE 引用时只解除引用
        boolean lastReference = bindingMapper.selectActiveByFile(fileId).stream()
                .allMatch(active -> active.getId().equals(binding.getId()));
        if (lastReference) {
            // 必须在引用仍为 ACTIVE 时调用删除：infra 的删除授权由业务 Provider 判定，
            // 若先释放引用，Provider 将因"没有有效绑定"而拒绝授权，文件永远删不掉。
            fileCommonApi.deleteFile(new FileDeleteReqDTO().setFileId(fileId).setSubject(toFileSubject(subject)));
        }
        bindingMapper.updateWithVersion(
                new AiFileBindingDO()
                        .setId(binding.getId())
                        .setStatus(AiFileBindingDO.STATUS_RELEASED)
                        .setVersion(binding.getVersion() + 1),
                binding.getVersion());
    }

    @Override
    public List<AiFileUploadResultDTO> listByBusiness(String businessType, String businessKey) {
        AiFileBusinessType type =
                AiFileBusinessType.parse(businessType).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        return bindingMapper.selectActiveByBusiness(type.code(), businessKey).stream()
                .map(binding -> new AiFileUploadResultDTO()
                        .setFileId(binding.getFileId())
                        .setBusinessType(binding.getBusinessType())
                        .setBusinessKey(binding.getBusinessKey()))
                .toList();
    }

    @Override
    public AiFileSubjectResolver subjectResolver() {
        return subjectResolver;
    }

    /** 归属判定：REPORT/KNOWLEDGE_DOCUMENT 走授权目录，CHAT_SESSION 仅所有者。 */
    boolean canAccess(AiFileBusinessType type, String businessKey, AiFileSubject subject) {
        if (subject == null) {
            return false;
        }
        Optional<AiResourceType> governed = type.governedResourceType();
        if (governed.isEmpty()) {
            // 会话附件：只有上传主体本人（读取时还要与绑定所有者比对，见 read/release）
            return true;
        }
        AiResourceType resourceType = governed.get();
        return authorizationService
                .authorize(
                        subject.applicationId(),
                        subject.subjectType().name(),
                        subject.externalUserId(),
                        resourceType,
                        businessKey,
                        AiAction.READ,
                        List.of(businessKey))
                .isAllowed();
    }

    /** CHAT_SESSION 类型没有授权目录语义：读取/解除引用必须由所有者本人执行。 */
    boolean requiresOwnership(AiFileBusinessType type) {
        return type.governedResourceType().isEmpty();
    }

    /** 仅所有者类型的所有者校验：非所有者一律拒绝。 */
    private static boolean ownsWhenRequired(AiFileBusinessType type, AiFileBindingDO binding, AiFileSubject subject) {
        if (type.governedResourceType().isPresent()) {
            return true;
        }
        return subject.sameAs(new AiFileSubject.AiFileBindingOwner(
                binding.getApplicationId(), binding.getSubjectType(), binding.getExternalUserId()));
    }

    private AiFileSubject currentSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
    }

    private AiFileBindingDO requireActiveBinding(Long fileId) {
        if (fileId == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return bindingMapper.selectActiveByFile(fileId).stream()
                .findFirst()
                .orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
    }

    private static FileSubjectDTO toFileSubject(AiFileSubject subject) {
        FileSubjectDTO fileSubject = new FileSubjectDTO();
        fileSubject.setUserType(com.basicframework.framework.common.enums.UserTypeEnum.MEMBER.getValue());
        fileSubject.setUserId(subject.ticketId());
        return fileSubject;
    }
}
