package com.basicframework.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类契约钉住测试：扫描包保持占位符（由 boot build 注入 base-package），
 * 且启动类可独立实例化而不触发 Spring 上下文启动。
 */
class BasicFrameworkServerApplicationTest {

    @Test
    void bootEntryPoint_declaresConfiguredBasePackages() {
        SpringBootApplication annotation =
                BasicFrameworkServerApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.scanBasePackages())
                .containsExactly(
                        "${basic-framework.info.base-package}.server", "${basic-framework.info.base-package}.module");
    }

    @Test
    void bootEntryPoint_canBeInstantiatedWithoutStartingSpring() {
        assertThat(new BasicFrameworkServerApplication()).isNotSameAs(new BasicFrameworkServerApplication());
    }
}
