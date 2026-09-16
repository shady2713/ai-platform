package com.basicframework.framework.common.util.spring;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.extra.spring.SpringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SpringExpressionUtilsTest {

    private AnnotationConfigApplicationContext applicationContext;

    @BeforeEach
    void setUpApplicationContext() {
        applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.registerBean(SpringUtil.class);
        applicationContext.registerBean("desensitizationPolicy", DesensitizationPolicy.class);
        applicationContext.refresh();
    }

    @AfterEach
    void closeApplicationContext() {
        applicationContext.close();
    }

    @Test
    void parseExpression_handlesBlankLiteralAndBeanExpressions() {
        assertThat(SpringExpressionUtils.parseExpression(" ")).isNull();
        assertThat(SpringExpressionUtils.parseExpression("false")).isEqualTo(false);
        assertThat(SpringExpressionUtils.parseExpression("@desensitizationPolicy.disabled"))
                .isEqualTo(true);
    }

    static final class DesensitizationPolicy {

        public boolean isDisabled() {
            return true;
        }
    }
}
