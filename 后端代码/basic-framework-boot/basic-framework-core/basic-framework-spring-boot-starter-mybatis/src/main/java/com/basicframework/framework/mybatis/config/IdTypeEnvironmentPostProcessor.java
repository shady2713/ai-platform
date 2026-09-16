package com.basicframework.framework.mybatis.config;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.basicframework.framework.mybatis.core.util.JdbcUtils;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 当 IdType 为 {@link IdType#NONE} 时，根据 PRIMARY 数据源所使用的数据库，自动设置
 *
 */
@Slf4j
public class IdTypeEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String ID_TYPE_KEY = "mybatis-plus.global-config.db-config.id-type";

    private static final String DATASOURCE_DYNAMIC_KEY = "spring.datasource.dynamic";

    private static final String QUARTZ_JOB_STORE_DRIVER_KEY =
            "spring.quartz.properties.org.quartz.jobStore.driverDelegateClass";

    private static final Set<DbType> INPUT_ID_TYPES =
            Set.of(DbType.ORACLE, DbType.ORACLE_12C, DbType.POSTGRE_SQL, DbType.KINGBASE_ES, DbType.DB2, DbType.H2);

    /**
     * 需要显式指定 Quartz JobStore 委托的数据库；其余数据库（MySQL、H2 等）使用 Quartz
     * 默认的 StdJDBCDelegate，无需覆盖——不在此表中的 DbType 不做任何处理。
     */
    private static final EnumMap<DbType, String> QUARTZ_JOB_STORE_DELEGATES = new EnumMap<>(DbType.class);

    static {
        QUARTZ_JOB_STORE_DELEGATES.put(DbType.POSTGRE_SQL, "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
        QUARTZ_JOB_STORE_DELEGATES.put(DbType.ORACLE, "org.quartz.impl.jdbcjobstore.oracle.OracleDelegate");
        QUARTZ_JOB_STORE_DELEGATES.put(DbType.ORACLE_12C, "org.quartz.impl.jdbcjobstore.oracle.OracleDelegate");
        QUARTZ_JOB_STORE_DELEGATES.put(DbType.SQL_SERVER, "org.quartz.impl.jdbcjobstore.MSSQLDelegate");
        QUARTZ_JOB_STORE_DELEGATES.put(DbType.SQL_SERVER2005, "org.quartz.impl.jdbcjobstore.MSSQLDelegate");
        QUARTZ_JOB_STORE_DELEGATES.put(DbType.DM, "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
        QUARTZ_JOB_STORE_DELEGATES.put(DbType.KINGBASE_ES, "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 如果获取不到 DbType，则不进行处理
        DbType dbType = getDbType(environment);
        if (dbType == null) {
            return;
        }

        // 在环境后置处理阶段补齐 Quartz JobStore 对应的 Driver，当前放这里是为了依赖最少、启动最早。
        setJobStoreDriverIfPresent(environment, dbType);

        // 如果非 NONE，则不进行处理
        IdType idType = getIdType(environment);
        if (idType != IdType.NONE) {
            return;
        }
        // 情况一，用户输入 ID，适合 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库
        if (INPUT_ID_TYPES.contains(dbType)) {
            setIdType(environment, IdType.INPUT);
            return;
        }
        // 情况二，自增 ID，适合 MySQL、DM 达梦等直接自增的数据库
        setIdType(environment, IdType.AUTO);
    }

    public IdType getIdType(ConfigurableEnvironment environment) {
        String value = environment.getProperty(ID_TYPE_KEY);
        try {
            return StrUtil.isNotBlank(value) ? IdType.valueOf(value) : IdType.NONE;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("无法解析 MyBatis Plus id-type 配置值: " + value, ex);
        }
    }

    public void setIdType(ConfigurableEnvironment environment, IdType idType) {
        Map<String, Object> map = new HashMap<>();
        map.put(ID_TYPE_KEY, idType);
        environment.getPropertySources().addFirst(new MapPropertySource("mybatisPlusIdType", map));
        log.info("[setIdType][修改 MyBatis Plus 的 idType 为({})]", idType);
    }

    public void setJobStoreDriverIfPresent(ConfigurableEnvironment environment, DbType dbType) {
        String driverClass = environment.getProperty(QUARTZ_JOB_STORE_DRIVER_KEY);
        if (StrUtil.isNotEmpty(driverClass)) {
            return;
        }
        // 不在映射表中的数据库无需特殊委托，保持 Quartz 默认（StdJDBCDelegate），不输出任何配置
        String delegate = QUARTZ_JOB_STORE_DELEGATES.get(dbType);
        if (StrUtil.isNotEmpty(delegate)) {
            environment.getSystemProperties().put(QUARTZ_JOB_STORE_DRIVER_KEY, delegate);
        }
    }

    public static DbType getDbType(ConfigurableEnvironment environment) {
        String primary = environment.getProperty(DATASOURCE_DYNAMIC_KEY + "." + "primary");
        if (StrUtil.isEmpty(primary)) {
            return null;
        }
        String url = environment.getProperty(DATASOURCE_DYNAMIC_KEY + ".datasource." + primary + ".url");
        if (StrUtil.isEmpty(url)) {
            return null;
        }
        return JdbcUtils.getDbType(url);
    }
}
