package com.basicframework.server.integration;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectRevisionMapper;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.semantic.AiMasterObjectService;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Y02 主数据映射的**管理维护路径**验收（真实 MySQL + Redis）。
 *
 * <p>与 {@link AiMasterMappingAcceptanceIT} 分工明确：那张覆盖 AT-070 的判定语义（同名不同实体不合并、
 * 冲突/过期阻断、换映射版本不改旧报表），这张覆盖**管理页面真正会调用的读写路径**——对象分页、
 * 版本分页、草稿条目删除（含乐观锁单赢家），以及三个 Mapper 的入参防御分支。
 *
 * <p>为什么必须单独一张而不是并进验收 IT：`*Mapper` 里只有手写 default 方法计入行覆盖，
 * 没人真实调用的方法就是 0%。这些路径只能由**真实 MySQL 的 IT** 覆盖（单测用 mock 打不到），
 * 而验收 IT 已有 640 行，并进去会逼近 800 行的全仓硬上限。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiMasterMappingAdminMaintenanceIT extends AbstractPersistenceIntegrationTest {

    private static final String SOURCE_APP = "it-md-admin-src";

    private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static final PageParam PAGE = new PageParam().setPageNo(1).setPageSize(20);

    @Autowired
    private AiMasterObjectService masterObjectService;

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiMasterObjectMapper objectMapper;

    @Autowired
    private AiMasterObjectRevisionMapper revisionMapper;

    @Autowired
    private AiMasterObjectMappingMapper mappingMapper;

    private Long sourceApplicationId;

    @BeforeEach
    void prepare() {
        cleanUpData();
        loginAs(1001L);
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(SOURCE_APP)
                .setName("管理维护来源系统")
                .setOrigins(List.of("https://admin-src.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        sourceApplicationId = issue.getApplication().getId();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        cleanUpData();
        sourceApplicationId = null;
    }

    /**
     * 对象分页：类型/状态过滤 + 关键字（标识或名称）模糊匹配。
     *
     * <p>关键字分支是 {@code AiMasterObjectMapper.selectPage} 里唯一带 OR 的条件，必须真的带上关键字
     * 跑一次才能覆盖；不带关键字再跑一次覆盖另一侧，两次合起来才是完整语义。
     */
    @Test
    void objectPagingFiltersByTypeStatusAndKeyword() {
        Long activeCustomer = createObject("md_pg_cust", "云启科技", "CUSTOMER");
        Long activeSupplier = createObject("md_pg_supplier", "远航物流", "SUPPLIER");
        Long disabledCustomer = createObject("md_pg_stopped", "停用客户", "CUSTOMER");
        masterObjectService.updateObjectStatus(disabledCustomer, 0, false);

        // 不带任何过滤：三个对象都在
        PageResult<AiMasterObjectDO> all = masterObjectService.getObjectPage(PAGE, null, null, null);
        assertThat(all.getTotal()).isEqualTo(3L);
        assertThat(all.getList())
                .extracting(AiMasterObjectDO::getObjectCode)
                .containsExactlyInAnyOrder("md_pg_cust", "md_pg_supplier", "md_pg_stopped");

        // 类型过滤：供应商只剩一个
        PageResult<AiMasterObjectDO> suppliers = masterObjectService.getObjectPage(PAGE, "SUPPLIER", null, null);
        assertThat(suppliers.getTotal()).isEqualTo(1L);
        assertThat(suppliers.getList().get(0).getObjectCode()).isEqualTo("md_pg_supplier");

        // 状态过滤：ACTIVE 排除掉已停用的客户
        PageResult<AiMasterObjectDO> activeOnly = masterObjectService.getObjectPage(PAGE, null, "ACTIVE", null);
        assertThat(activeOnly.getTotal()).isEqualTo(2L);
        assertThat(activeOnly.getList())
                .extracting(AiMasterObjectDO::getObjectCode)
                .containsExactlyInAnyOrder("md_pg_cust", "md_pg_supplier");

        // 关键字命中标识
        assertThat(masterObjectService
                        .getObjectPage(PAGE, null, null, "md_pg_sup")
                        .getTotal())
                .isEqualTo(1L);
        // 关键字命中名称（OR 分支的另一侧）
        PageResult<AiMasterObjectDO> byName = masterObjectService.getObjectPage(PAGE, null, null, "云启");
        assertThat(byName.getTotal()).isEqualTo(1L);
        assertThat(byName.getList().get(0).getObjectCode()).isEqualTo("md_pg_cust");
        // 关键字命中已停用对象：分页是控制面配置读取，停用只影响判定，不影响可见性
        assertThat(masterObjectService
                        .getObjectPage(PAGE, null, null, "md_pg_stopped")
                        .getTotal())
                .isEqualTo(1L);
        // 空白关键字按"未提供"处理，不当关键字用
        assertThat(masterObjectService.getObjectPage(PAGE, null, null, "   ").getTotal())
                .isEqualTo(3L);

        // 非法过滤值显式拒绝，不静默忽略
        assertCode(() -> masterObjectService.getObjectPage(PAGE, null, "UNKNOWN_STATUS", null), AI_REQUEST_INVALID);
        assertCode(() -> masterObjectService.getObjectPage(PAGE, "UNKNOWN_TYPE", null, null), AI_REQUEST_INVALID);
        assertCode(() -> masterObjectService.getObjectPage(null, null, null, null), AI_REQUEST_INVALID);
    }

    /** 版本分页：按对象 + 状态过滤，版本号倒序（新版本在前）。 */
    @Test
    void revisionPagingListsNewestVersionFirstPerObject() {
        Long objectId = createObject("md_pg_rev", "版本分页对象", "CUSTOMER");
        Long firstRevision = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(objectId).setValidFrom(WINDOW_FROM));
        masterObjectService.addMappingEntry(entry(objectId, firstRevision, "K-1"));
        // 一个对象同时只允许一个未发布草稿：先发布 v1 才能建 v2
        loginAs(1002L);
        masterObjectService.publishRevision(objectId, firstRevision, 0);

        loginAs(1001L);
        Long secondRevision = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(objectId).setValidFrom(WINDOW_FROM));
        masterObjectService.addMappingEntry(entry(objectId, secondRevision, "K-2"));

        PageResult<AiMasterObjectRevisionDO> all = masterObjectService.getRevisionPage(PAGE, objectId, null);
        assertThat(all.getTotal()).isEqualTo(2L);
        assertThat(all.getList())
                .extracting(AiMasterObjectRevisionDO::getRevisionNo)
                .containsExactly(2L, 1L);

        // 状态过滤：草稿只剩 v2
        PageResult<AiMasterObjectRevisionDO> drafts = masterObjectService.getRevisionPage(PAGE, objectId, "DRAFT");
        assertThat(drafts.getTotal()).isEqualTo(1L);
        assertThat(drafts.getList().get(0).getRevisionNo()).isEqualTo(2L);

        // 已发布只剩 v1
        assertThat(masterObjectService
                        .getRevisionPage(PAGE, objectId, "PUBLISHED")
                        .getTotal())
                .isEqualTo(1L);

        // 另一个对象没有版本
        Long otherObjectId = createObject("md_pg_rev_other", "另一个对象", "CUSTOMER");
        assertThat(masterObjectService
                        .getRevisionPage(PAGE, otherObjectId, null)
                        .getTotal())
                .isEqualTo(0L);

        assertCode(() -> masterObjectService.getRevisionPage(PAGE, objectId, "BOGUS"), AI_REQUEST_INVALID);
        assertCode(() -> masterObjectService.getRevisionPage(null, objectId, null), AI_REQUEST_INVALID);
    }

    /**
     * 草稿条目删除：乐观锁**只允许单赢家**，且已发布版本的条目不可删。
     *
     * <p>这是 {@code AiMasterObjectMappingMapper.deleteEntry} 唯一会真正下发 DELETE 的入口，
     * 同时钉住"版本号不匹配即拒绝"而不是静默删成功。
     */
    @Test
    void draftEntryDeletionUsesOptimisticLock() {
        Long objectId = createObject("md_pg_del", "删除路径对象", "CUSTOMER");
        Long revisionNo = masterObjectService.createRevision(
                new AiMasterRevisionDraftDTO().setMasterObjectId(objectId).setValidFrom(WINDOW_FROM));
        Long entryId = masterObjectService.addMappingEntry(entry(objectId, revisionNo, "DEL-1"));

        // 草稿条目可删
        masterObjectService.removeMappingEntry(entryId, 0);
        assertThat(mappingMapper.selectById(entryId)).isNull();

        // 陈旧版本号（乐观锁失配）必须拒绝，而不是"照样删掉"
        Long secondEntry = masterObjectService.addMappingEntry(entry(objectId, revisionNo, "DEL-2"));
        assertCode(() -> masterObjectService.removeMappingEntry(secondEntry, 7), AI_STATE_CONFLICT);
        assertThat(mappingMapper.selectById(secondEntry)).isNotNull();

        // 不存在的条目与非法版本号
        assertCode(() -> masterObjectService.removeMappingEntry(999_999L, 0), AI_MASTER_OBJECT_REVISION_NOT_EXISTS);
        assertCode(() -> masterObjectService.removeMappingEntry(null, 0), AI_MASTER_OBJECT_REVISION_NOT_EXISTS);
        assertCode(() -> masterObjectService.removeMappingEntry(secondEntry, null), AI_REQUEST_INVALID);
        assertCode(() -> masterObjectService.removeMappingEntry(secondEntry, -1), AI_REQUEST_INVALID);

        // 发布后条目随版本冻结：不可删
        loginAs(1002L);
        masterObjectService.publishRevision(objectId, revisionNo, 0);
        assertCode(
                () -> masterObjectService.removeMappingEntry(secondEntry, 0),
                AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT);
        assertThat(mappingMapper.selectById(secondEntry)).isNotNull();
    }

    /**
     * 三个 Mapper 的**入参防御分支**：标识/版本号缺失时短路返回，不打数据库。
     *
     * <p>服务层在调用前已做 null 判断（见 {@code getObject} / {@code getRevision} / {@code requireVersion}），
     * 所以这些分支从服务层**不可达**；这里直接调 Mapper 把契约钉住——入参缺失时返回空/0，
     * 而不是构造出 {@code eq(id, null)} 这种永远查不到行的查询。
     */
    @Test
    void mapperNullGuardsShortCircuitWithoutQuerying() {
        assertThat(objectMapper.selectByCode(null)).isNull();
        assertThat(revisionMapper.selectByRevisionNo(null, 1L)).isNull();
        assertThat(revisionMapper.selectByRevisionNo(1L, null)).isNull();
        assertThat(revisionMapper.selectLatest(null)).isNull();
        assertThat(mappingMapper.deleteEntry(null, 0)).isZero();
        assertThat(mappingMapper.deleteEntry(1L, null)).isZero();
    }

    private Long createObject(String objectCode, String objectName, String objectType) {
        loginAs(1001L);
        return masterObjectService.createObject(new AiMasterObjectSaveDTO()
                .setObjectCode(objectCode)
                .setObjectName(objectName)
                .setObjectType(objectType)
                .setDescription("Y02 管理维护路径夹具"));
    }

    private AiMasterMappingEntrySaveDTO entry(Long objectId, Long revisionNo, String sourceKey) {
        return new AiMasterMappingEntrySaveDTO()
                .setMasterObjectId(objectId)
                .setRevisionNo(revisionNo)
                .setApplicationId(sourceApplicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName("管理维护来源")
                .setMatchMethod("MANUAL")
                .setValidFrom(WINDOW_FROM);
    }

    private static void loginAs(Long operatorId) {
        LoginUser loginUser = new LoginUser().setId(operatorId).setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private void cleanUpData() {
        jdbcTemplate.update("DELETE FROM ai_master_object_mapping");
        jdbcTemplate.update("DELETE FROM ai_master_object_revision");
        jdbcTemplate.update("DELETE FROM ai_master_object");
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, SOURCE_APP);
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
    }

    /** 断言抛出的稳定错误码（用常量而非裸编号：编号跨卡复用，写死会在迁移时静默漂移）。 */
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
