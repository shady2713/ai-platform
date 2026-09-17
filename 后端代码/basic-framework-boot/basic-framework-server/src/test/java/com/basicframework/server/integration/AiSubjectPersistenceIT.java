package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * A02 外部主体的真实 MySQL 集成验证：
 * 唯一键（同用户名跨应用不冲突）、业务侧撤销同步、范围版本单调递增，
 * 以及"可信解析器给出范围才放行、否则 DENY"的端到端语义。
 */
@Import(AiSubjectPersistenceIT.ResolverConfiguration.class)
class AiSubjectPersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-subject-app";

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    /** 测试用可信解析器：只放行组织 10 与对象 report-1。 */
    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver testSubjectScopeResolver() {
            // 声明式解析器：回显主体自己的范围来源与版本；无对象提示时返回空范围（用于验证 DENY）
            return request -> request.resourceHints().isEmpty()
                    ? Optional.of(new SubjectScope(Set.of(), Set.of(), request.scopeSource(), request.scopeVersion()))
                    : Optional.of(new SubjectScope(
                            Set.of(10L), Set.of("report-1"), request.scopeSource(), request.scopeVersion()));
        }
    }

    @AfterEach
    void cleanUp() {
        List<Long> appIds = jdbcTemplate.queryForList(
                "SELECT id FROM ai_application WHERE app_code IN (?, ?)", Long.class, APP_CODE, APP_CODE + "-2");
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
    }

    private Long createApplication(String appCode) {
        AiApplicationSaveDTO saveDTO = new AiApplicationSaveDTO()
                .setAppCode(appCode)
                .setName("IT 主体应用")
                .setOrigins(List.of("https://crm.example.com"));
        return applicationService.createApplication(saveDTO).getApplication().getId();
    }

    @Test
    void sameExternalUserInDifferentApplicationsCoexistAndScopesStayIndependent() {
        Long first = createApplication(APP_CODE);
        Long second = createApplication(APP_CODE + "-2");

        AiSubjectDO aliceInFirst =
                subjectService.syncSubject(first, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        AiSubjectDO aliceInSecond =
                subjectService.syncSubject(second, AiSubjectType.USER, "alice", "Alice(2)", "portal-auth", 5L);

        assertThat(aliceInFirst.getId()).isNotEqualTo(aliceInSecond.getId());
        assertThat(aliceInFirst.getScopeSource()).isEqualTo("crm-auth");
        assertThat(aliceInSecond.getScopeSource()).isEqualTo("portal-auth");

        AiSubjectScopeDTO firstScope =
                subjectService.resolveScope(first, AiSubjectType.USER, "alice", List.of("report-1"));
        AiSubjectScopeDTO secondScope =
                subjectService.resolveScope(second, AiSubjectType.USER, "alice", List.of("report-1"));

        assertThat(firstScope.isDenied()).isFalse();
        assertThat(secondScope.isDenied()).isFalse();
        // 两个应用里的 alice 是不同主体，范围来源各自独立
        assertThat(firstScope.getScopeSource()).isEqualTo("crm-auth");
        assertThat(secondScope.getScopeSource()).isEqualTo("portal-auth");
    }

    @Test
    void disabledSubjectImmediatelyDeniesScopeAndScopeVersionOnlyMovesForward() {
        Long applicationId = createApplication(APP_CODE);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "bob", "Bob", "crm-auth", 7L);
        sqlSessionTemplate.clearCache();

        // 旧版本同步不得回退范围版本
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "bob", "Bob", "crm-auth", 3L);
        sqlSessionTemplate.clearCache();
        assertThat(subjectService
                        .findActiveSubject(applicationId, AiSubjectType.USER, "bob")
                        .orElseThrow()
                        .getScopeVersion())
                .isEqualTo(7L);

        subjectService.disableSubject(applicationId, AiSubjectType.USER, "bob");
        sqlSessionTemplate.clearCache();

        assertThat(subjectService.findActiveSubject(applicationId, AiSubjectType.USER, "bob"))
                .isEmpty();
        AiSubjectScopeDTO denied =
                subjectService.resolveScope(applicationId, AiSubjectType.USER, "bob", List.of("report-1"));
        assertThat(denied.isDenied()).isTrue();
        assertThat(denied.getDenyReason()).isEqualTo("SUBJECT_NOT_FOUND");
    }

    @Test
    void emptyResolverScopeIsDeniedInsteadOfWideningToAllData() {
        Long applicationId = createApplication(APP_CODE);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "carol", "Carol", "crm-auth", 1L);

        // 测试解析器在"无对象提示"时返回空范围：必须 DENY
        AiSubjectScopeDTO denied = subjectService.resolveScope(applicationId, AiSubjectType.USER, "carol", List.of());

        assertThat(denied.isDenied()).isTrue();
        assertThat(denied.getDenyReason()).isEqualTo("EMPTY_SCOPE");
        assertThat(denied.getOrganizationIds()).isEmpty();
        assertThat(denied.getResourceKeys()).isEmpty();
    }

    @Test
    void subjectRequiresExistingApplicationByForeignKey() {
        // 外键 RESTRICT：指向不存在应用的插入必须被数据库拒绝
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO ai_subject (application_id, subject_type, external_user_id, status, scope_source,"
                                + " scope_version) VALUES (999999, 'USER', 'ghost', 'ACTIVE', 'crm-auth', 1)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
