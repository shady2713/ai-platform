package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.framework.idempotent.core.annotation.Idempotent;
import com.basicframework.framework.idempotent.core.keyresolver.impl.ExpressionIdempotentKeyResolver;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ExpressionRateLimiterKeyResolver;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.mysql.dict.DictDataMapper;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dict.DictDataService;
import com.basicframework.module.system.service.permission.PermissionService;
import com.basicframework.module.system.service.permission.RoleService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 使用真实 Redis 与 Spring AOP 验证缓存、幂等和限流切面。 */
@Import(CacheAndProtectionIT.ProtectionTestConfiguration.class)
class CacheAndProtectionIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private DictDataMapper dictDataMapper;

    @Autowired
    private DictDataService dictDataService;

    @Autowired
    private DeptService deptService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ProtectionProbe protectionProbe;

    @Test
    void cachesAndProtectionAspects_succeedAgainstRealRedis() {
        verifyRoleDatabaseReads();
        verifyDictLocalCache();
        verifyDictDataRedisCache();
        verifyProtectionAspects();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void revokedUserRoleCannotBeRestoredByALateRedisWrite() {
        Cache legacyUserRoles = cacheManager.getCache("user_role_ids#30m");
        assertThat(legacyUserRoles).isNotNull();
        String username = "it_cache_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO system_users (username, nickname, status) VALUES (?, '缓存撤销测试', 0)", username);
        Long userId =
                jdbcTemplate.queryForObject("SELECT id FROM system_users WHERE username = ?", Long.class, username);
        try {
            permissionService.assignUserRole(1L, userId, Set.of(1L));
            assertThat(permissionService.hasAnyRoles(userId, "super_admin")).isTrue();
            // 撤销事务提交后，下一次请求重新读取授权，不能复用测试事务的 SqlSession。
            permissionService.assignUserRole(1L, userId, Set.of());
            legacyUserRoles.put(userId, Set.of(1L));
            assertThat(permissionService.hasAnyRoles(userId, "super_admin")).isFalse();
            assertThat(permissionService.hasAnyPermissions(userId, "system:user:query"))
                    .isFalse();
        } finally {
            legacyUserRoles.evict(userId);
            jdbcTemplate.update("DELETE FROM system_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM system_user_session WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM system_operate_log WHERE biz_id = ?", userId);
            jdbcTemplate.update("DELETE FROM system_users WHERE id = ?", userId);
        }
    }

    private void verifyRoleDatabaseReads() {
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        Cache legacyRoleCache = cacheManager.getCache("role");
        assertThat(legacyRoleCache).isNotNull();
        try {
            RoleDO initial = roleService.getRoleList(List.of(1L)).get(0);
            assertThat(initial.getName()).isEqualTo("超级管理员");
            assertThat(roleService.hasAnySuperAdmin(List.of(1L))).isTrue();
            roleMapper.updateById(new RoleDO().setId(1L).setName("集成测试管理员"));
            legacyRoleCache.put(1L, initial);
            assertThat(roleService.getRoleList(List.of(1L)).get(0).getName()).isEqualTo("集成测试管理员");
        } finally {
            legacyRoleCache.clear();
        }
    }

    private void verifyDictLocalCache() {
        // 字典标签解析经过两层缓存：DictFrameworkUtils 本地缓存 + Service 层 Redis 缓存
        Cache dictDataCache = cacheManager.getCache(RedisKeyConstants.DICT_DATA_LIST_BY_TYPE);
        assertThat(dictDataCache).isNotNull();
        dictDataCache.clear();
        DictFrameworkUtils.clearCache();
        try {
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男");

            DictDataDO dictUpdate = new DictDataDO();
            dictUpdate.setId(1L);
            dictUpdate.setLabel("男性");
            dictDataMapper.updateById(dictUpdate);
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男");

            // 绕过 Service 的直改不失效任何缓存：清本地缓存后仍命中 Redis 旧值
            DictFrameworkUtils.clearCache();
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男");

            dictDataCache.clear();
            DictFrameworkUtils.clearCache();
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男性");
        } finally {
            DictFrameworkUtils.clearCache();
            dictDataCache.clear();
        }
    }

    private void verifyDictDataRedisCache() {
        Cache dictDataCache = cacheManager.getCache(RedisKeyConstants.DICT_DATA_LIST_BY_TYPE);
        assertThat(dictDataCache).isNotNull();
        dictDataCache.clear();
        try {
            // 首次查询走数据库并写入 Redis，键带 #30m TTL 兜底
            List<DictDataDO> first = dictDataService.getDictDataListByDictType("system_user_sex");
            String initialLabel = first.stream()
                    .filter(d -> "1".equals(d.getValue()))
                    .findFirst()
                    .orElseThrow()
                    .getLabel();
            assertThat(stringRedisTemplate.getExpire("dict_data_list_by_type:system_user_sex", TimeUnit.SECONDS))
                    .isPositive();

            // 直改库不经过 Service，第二次查询命中缓存仍返回旧值
            DictDataDO dictUpdate = new DictDataDO();
            dictUpdate.setId(1L);
            dictUpdate.setLabel("缓存钉住");
            dictDataMapper.updateById(dictUpdate);
            List<DictDataDO> cached = dictDataService.getDictDataListByDictType("system_user_sex");
            assertThat(cached.stream()
                            .filter(d -> "1".equals(d.getValue()))
                            .findFirst()
                            .orElseThrow()
                            .getLabel())
                    .isEqualTo(initialLabel);

            // 经过 Service 写操作触发缓存失效，第三次查询读到新值
            DictDataDO serviceUpdate = new DictDataDO();
            serviceUpdate.setId(1L);
            serviceUpdate.setDictType("system_user_sex");
            serviceUpdate.setValue("1");
            serviceUpdate.setLabel("缓存钉住");
            dictDataService.updateDictData(serviceUpdate);
            List<DictDataDO> reloaded = dictDataService.getDictDataListByDictType("system_user_sex");
            assertThat(reloaded.stream()
                            .filter(d -> "1".equals(d.getValue()))
                            .findFirst()
                            .orElseThrow()
                            .getLabel())
                    .isEqualTo("缓存钉住");
        } finally {
            // 测试事务回滚只还数据库，Redis 缓存需显式清理，避免脏值污染其他测试
            dictDataCache.clear();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void departmentReadsIgnoreLateCacheWritesAfterReparentCommit() {
        Cache legacyDeptCache = cacheManager.getCache("dept_children_ids#30m");
        assertThat(legacyDeptCache).isNotNull();
        String name = "缓存部门_" + UUID.randomUUID().toString().substring(0, 8);
        Long parentId =
                deptService.createDept(new DeptDO().setName(name).setStatus(0).setSort(0));
        Long childId = null;
        try {
            childId = deptService.createDept(new DeptDO()
                    .setName(name + "_child")
                    .setParentId(parentId)
                    .setStatus(0)
                    .setSort(0));
            var children = deptService.getChildDeptIdList(parentId);
            assertThat(children).containsExactly(childId);
            deptService.updateDept(
                    new DeptDO().setId(childId).setName(name + "_child").setParentId(0L));
            legacyDeptCache.put(parentId, children);
            assertThat(deptService.getChildDeptIdList(parentId)).isEmpty();
        } finally {
            legacyDeptCache.evict(parentId);
            jdbcTemplate.update("DELETE FROM system_dept WHERE id IN (?, ?)", childId, parentId);
        }
    }

    private void verifyProtectionAspects() {
        assertThat(AopUtils.isAopProxy(protectionProbe)).isTrue();

        assertThat(protectionProbe.idempotent("idempotent-integration")).isEqualTo(1);
        assertServiceException(
                GlobalErrorCodeConstants.REPEATED_REQUESTS.getCode(),
                () -> protectionProbe.idempotent("idempotent-integration"));
        assertThat(protectionProbe.getIdempotentInvocations()).isEqualTo(1);

        assertThat(protectionProbe.rateLimited("rate-limit-integration")).isEqualTo(1);
        assertServiceException(
                GlobalErrorCodeConstants.TOO_MANY_REQUESTS.getCode(),
                () -> protectionProbe.rateLimited("rate-limit-integration"));
        assertThat(protectionProbe.getRateLimitedInvocations()).isEqualTo(1);
    }

    static class ProtectionProbe {

        private int idempotentInvocations;
        private int rateLimitedInvocations;

        @Idempotent(timeout = 30, keyResolver = ExpressionIdempotentKeyResolver.class, keyArg = "#key")
        public int idempotent(String key) {
            return ++idempotentInvocations;
        }

        @RateLimiter(count = 1, time = 30, keyResolver = ExpressionRateLimiterKeyResolver.class, keyArg = "#key")
        public int rateLimited(String key) {
            return ++rateLimitedInvocations;
        }

        int getIdempotentInvocations() {
            return idempotentInvocations;
        }

        int getRateLimitedInvocations() {
            return rateLimitedInvocations;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtectionTestConfiguration {

        @Bean
        ProtectionProbe protectionProbe() {
            return new ProtectionProbe();
        }
    }
}
