package com.basicframework.module.ai.service.application;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_CREDENTIAL_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_ORIGIN_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationCredentialDO;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.mysql.application.AiApplicationCredentialMapper;
import com.basicframework.module.ai.dal.mysql.application.AiApplicationMapper;
import com.basicframework.module.ai.domain.application.ApplicationOrigins;
import com.basicframework.module.ai.domain.application.ApplicationSecrets;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 应用与客户端凭据实现（A01）。
 *
 * <p>并发语义与模型端点一致：可变操作都带乐观锁 CAS；轮换/吊销在一个事务内完成
 * "吊销旧凭据 + 签发新凭据"，任何时刻至多一条 ACTIVE 凭据（默认无重叠）。
 */
@Service
@RequiredArgsConstructor
public class AiApplicationServiceImpl implements AiApplicationService {

    /** appCode 规则：小写字母开头，允许小写字母、数字、下划线与连字符。 */
    private static final Pattern APP_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{2,63}$");

    private final AiApplicationMapper applicationMapper;

    private final AiApplicationCredentialMapper credentialMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiApplicationCredentialIssueDTO createApplication(AiApplicationSaveDTO saveDTO) {
        validateSaveDTO(saveDTO);
        if (applicationMapper.selectByAppCode(saveDTO.getAppCode()) != null) {
            throw exception(AI_APPLICATION_CODE_DUPLICATE, saveDTO.getAppCode());
        }
        AiApplicationDO application = new AiApplicationDO()
                .setAppCode(saveDTO.getAppCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setOrigins(ApplicationOrigins.normalizeToJson(saveDTO.getOrigins()))
                .setEnabled(false)
                .setVersion(0);
        applicationMapper.insert(application);
        return issueCredential(application);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateApplication(AiApplicationSaveDTO saveDTO) {
        validateSaveDTO(saveDTO);
        requireVersion(saveDTO.getVersion());
        AiApplicationDO existing = validateApplicationExists(saveDTO.getId());
        if (saveDTO.getAppCode() != null && !saveDTO.getAppCode().equals(existing.getAppCode())) {
            // appCode 是对接方的稳定标识：改标识等于换一个应用，必须新建
            throw exception(AI_STATE_CONFLICT);
        }
        AiApplicationDO update = new AiApplicationDO()
                .setId(existing.getId())
                .setAppCode(existing.getAppCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setOrigins(ApplicationOrigins.normalizeToJson(saveDTO.getOrigins()))
                .setVersion(saveDTO.getVersion() + 1);
        if (applicationMapper.updateWithVersion(update, saveDTO.getVersion()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer version, Boolean enabled) {
        requireVersion(version);
        if (enabled == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiApplicationDO update =
                new AiApplicationDO().setId(id).setEnabled(enabled).setVersion(version + 1);
        if (applicationMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiApplicationCredentialIssueDTO rotateCredential(Long id, Integer version) {
        requireVersion(version);
        AiApplicationDO application = validateApplicationExists(id);
        AiApplicationDO update = new AiApplicationDO().setId(id).setVersion(version + 1);
        if (applicationMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        revokeActiveCredentials(id);
        return issueCredential(application);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeCredential(Long id, Integer version) {
        requireVersion(version);
        validateApplicationExists(id);
        AiApplicationDO update = new AiApplicationDO().setId(id).setVersion(version + 1);
        if (applicationMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        revokeActiveCredentials(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteApplication(Long id, Integer version) {
        requireVersion(version);
        validateApplicationExists(id);
        if (!credentialMapper.selectActiveByApplication(id).isEmpty()) {
            // 有可用凭据就不允许删除：避免把仍能换票的秘密留在库里
            throw exception(AI_STATE_CONFLICT);
        }
        if (applicationMapper.updateWithVersion(new AiApplicationDO().setId(id).setVersion(version + 1), version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        applicationMapper.deleteById(id);
    }

    @Override
    public AiApplicationDO getApplication(Long id) {
        return validateApplicationExists(id);
    }

    @Override
    public boolean hasActiveCredential(Long applicationId) {
        return !credentialMapper.selectActiveByApplication(applicationId).isEmpty();
    }

    @Override
    public PageResult<AiApplicationDO> getApplicationPage(PageParam pageParam, String appCode, Boolean enabled) {
        return applicationMapper.selectPage(pageParam, appCode, enabled);
    }

    @Override
    public AiApplicationDO authenticate(String appCode, String secret) {
        if (!StringUtils.hasText(appCode) || !StringUtils.hasText(secret)) {
            throw exception(AI_APPLICATION_CREDENTIAL_INVALID);
        }
        AiApplicationDO application = applicationMapper.selectByAppCode(appCode);
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            throw exception(AI_APPLICATION_CREDENTIAL_INVALID);
        }
        AiApplicationCredentialDO credential = credentialMapper.selectActiveByDigest(ApplicationSecrets.digest(secret));
        if (credential == null
                || !credential.getApplicationId().equals(application.getId())
                || !ApplicationSecrets.matches(credential.getSecretDigest(), secret)) {
            throw exception(AI_APPLICATION_CREDENTIAL_INVALID);
        }
        return application;
    }

    private AiApplicationCredentialIssueDTO issueCredential(AiApplicationDO application) {
        String secret = ApplicationSecrets.generate();
        AiApplicationCredentialDO credential = new AiApplicationCredentialDO()
                .setApplicationId(application.getId())
                .setSecretDigest(ApplicationSecrets.digest(secret))
                .setStatus(AiApplicationCredentialDO.STATUS_ACTIVE);
        credentialMapper.insert(credential);
        return new AiApplicationCredentialIssueDTO()
                .setApplication(application)
                .setSecret(secret)
                .setCredentialId(credential.getId());
    }

    private void revokeActiveCredentials(Long applicationId) {
        List<AiApplicationCredentialDO> active = credentialMapper.selectActiveByApplication(applicationId);
        LocalDateTime now = LocalDateTime.now();
        for (AiApplicationCredentialDO credential : active) {
            // 逻辑删除列不能被普通 update 写入（MyBatis-Plus 逻辑删除语义），只改状态列
            credentialMapper.updateById(new AiApplicationCredentialDO()
                    .setId(credential.getId())
                    .setStatus(AiApplicationCredentialDO.STATUS_REVOKED)
                    .setRevokedTime(now));
        }
    }

    private AiApplicationDO validateApplicationExists(Long id) {
        AiApplicationDO application = id == null ? null : applicationMapper.selectById(id);
        if (application == null) {
            throw exception(AI_APPLICATION_NOT_FOUND);
        }
        return application;
    }

    private static void validateSaveDTO(AiApplicationSaveDTO saveDTO) {
        if (saveDTO == null
                || !StringUtils.hasText(saveDTO.getAppCode())
                || !APP_CODE_PATTERN.matcher(saveDTO.getAppCode()).matches()
                || !StringUtils.hasText(saveDTO.getName())
                || saveDTO.getName().length() > 128) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getDescription() != null && saveDTO.getDescription().length() > 512) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getOrigins() == null || saveDTO.getOrigins().isEmpty()) {
            throw exception(AI_APPLICATION_ORIGIN_INVALID);
        }
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
