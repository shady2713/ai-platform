package com.basicframework.framework.mybatis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IKeyGenerator;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.incrementer.DmKeyGenerator;
import com.baomidou.mybatisplus.extension.incrementer.H2KeyGenerator;
import com.baomidou.mybatisplus.extension.incrementer.KingbaseKeyGenerator;
import com.baomidou.mybatisplus.extension.incrementer.OracleKeyGenerator;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.mybatis.core.handler.DefaultDBFieldHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class BasicFrameworkMybatisAutoConfigurationTest {

    private static final String PRIMARY_KEY = "spring.datasource.dynamic.primary";
    private static final String PRIMARY_URL_KEY = "spring.datasource.dynamic.datasource.primary.url";

    private final BasicFrameworkMybatisAutoConfiguration configuration = new BasicFrameworkMybatisAutoConfiguration();

    private ObjectMapper originalObjectMapper;

    @BeforeEach
    void captureObjectMapper() {
        originalObjectMapper = JacksonTypeHandler.getObjectMapper();
    }

    @AfterEach
    void restoreObjectMapper() {
        JacksonTypeHandler.setObjectMapper(originalObjectMapper);
    }

    @ParameterizedTest
    @MethodSource("supportedKeyGenerators")
    void keyGenerator_selectsImplementationForSupportedDatabase(
            String url, Class<? extends IKeyGenerator> expectedType) {
        assertThat(configuration.keyGenerator(databaseEnvironment(url))).isInstanceOf(expectedType);
    }

    @ParameterizedTest
    @MethodSource("unsupportedDatabaseEnvironments")
    void keyGenerator_rejectsDatabaseWithoutACompatibleGenerator(MockEnvironment environment) {
        assertThatThrownBy(() -> configuration.keyGenerator(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到合适的 IKeyGenerator 实现类");
    }

    @Test
    void createsPaginationInterceptorAndMetaObjectHandler() {
        MybatisPlusInterceptor interceptor = configuration.mybatisPlusInterceptor();
        MetaObjectHandler metaObjectHandler = configuration.defaultMetaObjectHandler(emptyCurrentUserProvider());

        assertThat(interceptor.getInterceptors()).singleElement().isInstanceOf(PaginationInnerInterceptor.class);
        assertThat(metaObjectHandler).isInstanceOf(DefaultDBFieldHandler.class);
    }

    @Test
    void jacksonTypeHandler_usesSuppliedObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        Object handler = configuration.jacksonTypeHandler(List.of(objectMapper));

        assertThat(handler).isInstanceOf(JacksonTypeHandler.class);
        assertThat(JacksonTypeHandler.getObjectMapper()).isSameAs(objectMapper);
    }

    @Test
    void jacksonTypeHandler_usesFrameworkMapperWhenNoMapperIsConfigured() {
        Object handler = configuration.jacksonTypeHandler(List.of());

        assertThat(handler).isInstanceOf(JacksonTypeHandler.class);
        assertThat(JacksonTypeHandler.getObjectMapper()).isSameAs(JsonUtils.getObjectMapper());
    }

    private static MockEnvironment databaseEnvironment(String url) {
        return new MockEnvironment().withProperty(PRIMARY_KEY, "primary").withProperty(PRIMARY_URL_KEY, url);
    }

    private static Stream<Arguments> supportedKeyGenerators() {
        return Stream.of(
                Arguments.of("jdbc:postgresql://localhost:5432/framework", PostgreKeyGenerator.class),
                Arguments.of("jdbc:oracle:thin:@localhost:1521:framework", OracleKeyGenerator.class),
                Arguments.of("jdbc:h2:mem:framework", H2KeyGenerator.class),
                Arguments.of("jdbc:kingbase8://localhost:54321/framework", KingbaseKeyGenerator.class),
                Arguments.of("jdbc:dm://localhost:5236/framework", DmKeyGenerator.class));
    }

    private static Stream<Arguments> unsupportedDatabaseEnvironments() {
        return Stream.of(
                Arguments.of(databaseEnvironment("jdbc:mysql://localhost:3306/framework")),
                Arguments.of(new MockEnvironment()));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CurrentUserProvider> emptyCurrentUserProvider() {
        return mock(ObjectProvider.class);
    }
}
