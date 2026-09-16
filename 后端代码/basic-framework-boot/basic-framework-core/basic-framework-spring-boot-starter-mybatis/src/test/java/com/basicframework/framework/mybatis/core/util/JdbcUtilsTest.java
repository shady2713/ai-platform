package com.basicframework.framework.mybatis.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.mybatisplus.annotation.DbType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

class JdbcUtilsTest {

    @Test
    void getDbType_andSqlServerDetection_followJdbcUrl() {
        String sqlServerUrl = "jdbc:sqlserver://localhost:1433;databaseName=test";

        assertThat(JdbcUtils.getDbType("jdbc:mysql://localhost:3306/test")).isEqualTo(DbType.MYSQL);
        assertThat(JdbcUtils.getDbType(sqlServerUrl)).isEqualTo(DbType.SQL_SERVER);
        assertThat(JdbcUtils.isSQLServer(sqlServerUrl)).isTrue();
        assertThat(JdbcUtils.isSQLServer(DbType.SQL_SERVER2005)).isTrue();
        assertThat(JdbcUtils.isSQLServer(DbType.MYSQL)).isFalse();
    }

    @Test
    void isConnectionOK_returnsFalseWhenNoDriverCanHandleUrl() {
        assertThat(JdbcUtils.isConnectionOK("jdbc:unsupported:test", "user", "password"))
                .isFalse();
    }

    @Test
    void isConnectionOK_returnsTrueWhenARegisteredDriverConnects() throws SQLException {
        String url = "jdbc:basic-framework-test:connection-ok";
        Driver driver = mock(Driver.class);
        Connection connection = mock(Connection.class);
        when(driver.connect(eq(url), any(Properties.class))).thenReturn(connection);
        DriverManager.registerDriver(driver);

        try {
            assertThat(JdbcUtils.isConnectionOK(url, "user", "password")).isTrue();
        } finally {
            DriverManager.deregisterDriver(driver);
        }

        verify(connection).close();
    }

    @Test
    void getDbType_readsDatasourceSelectedByDynamicRouting() throws SQLException {
        DynamicRoutingDataSource dynamicRoutingDataSource = mock(DynamicRoutingDataSource.class);
        DataSourceFixture fixture = dataSourceWithProductName("PostgreSQL");
        when(dynamicRoutingDataSource.determineDataSource()).thenReturn(fixture.dataSource());

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil
                    .when(() -> SpringUtil.getBean(DynamicRoutingDataSource.class))
                    .thenReturn(dynamicRoutingDataSource);

            assertThat(JdbcUtils.getDbType()).isEqualTo(DbType.POSTGRE_SQL);
        }

        verify(fixture.connection()).close();
    }

    @Test
    void getDbType_fallsBackToPlainDatasourceWhenDynamicRoutingIsUnavailable() throws SQLException {
        DataSourceFixture fixture = dataSourceWithProductName("MySQL");

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil
                    .when(() -> SpringUtil.getBean(DynamicRoutingDataSource.class))
                    .thenThrow(new NoSuchBeanDefinitionException(DynamicRoutingDataSource.class));
            springUtil.when(() -> SpringUtil.getBean(DataSource.class)).thenReturn(fixture.dataSource());

            assertThat(JdbcUtils.getDbType()).isEqualTo(DbType.MYSQL);
        }

        verify(fixture.connection()).close();
    }

    @Test
    void getDbType_retainsSqlExceptionAsCause() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        SQLException exception = new SQLException("connection unavailable");
        when(dataSource.getConnection()).thenThrow(exception);

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil
                    .when(() -> SpringUtil.getBean(DynamicRoutingDataSource.class))
                    .thenThrow(new NoSuchBeanDefinitionException(DynamicRoutingDataSource.class));
            springUtil.when(() -> SpringUtil.getBean(DataSource.class)).thenReturn(dataSource);

            assertThatThrownBy(JdbcUtils::getDbType)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("无法从数据源连接确定数据库类型")
                    .hasCause(exception);
        }
    }

    private static DataSourceFixture dataSourceWithProductName(String productName) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getDatabaseProductName()).thenReturn(productName);
        return new DataSourceFixture(dataSource, connection);
    }

    private record DataSourceFixture(DataSource dataSource, Connection connection) {}
}
