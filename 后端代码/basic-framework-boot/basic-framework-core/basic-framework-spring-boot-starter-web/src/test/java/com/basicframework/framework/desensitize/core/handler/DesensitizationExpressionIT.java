package com.basicframework.framework.desensitize.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.extra.spring.SpringUtil;
import com.basicframework.framework.desensitize.core.regex.annotation.EmailDesensitize;
import com.basicframework.framework.desensitize.core.regex.handler.EmailDesensitizationHandler;
import com.basicframework.framework.desensitize.core.slider.annotation.SliderDesensitize;
import com.basicframework.framework.desensitize.core.slider.handler.DefaultDesensitizationHandler;
import java.lang.annotation.Annotation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/** 使用真实 Spring BeanFactory 验证脱敏注解的 disable 表达式。 */
@SpringBootTest(
        classes = DesensitizationExpressionIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DesensitizationExpressionIT {

    private static final String EMAIL = "example@gmail.com";
    private static final String MOBILE = "13812345678";

    @Autowired
    private DesensitizationPolicy desensitizationPolicy;

    @Test
    void beanExpression_controlsRegexAndSliderDesensitization() throws NoSuchFieldException {
        EmailDesensitize emailAnnotation = annotation("email", EmailDesensitize.class);
        SliderDesensitize mobileAnnotation = annotation("mobile", SliderDesensitize.class);
        EmailDesensitizationHandler emailHandler = new EmailDesensitizationHandler();
        DefaultDesensitizationHandler mobileHandler = new DefaultDesensitizationHandler();

        desensitizationPolicy.setDisabled(true);
        assertThat(emailHandler.desensitize(EMAIL, emailAnnotation)).isEqualTo(EMAIL);
        assertThat(mobileHandler.desensitize(MOBILE, mobileAnnotation)).isEqualTo(MOBILE);

        desensitizationPolicy.setDisabled(false);
        assertThat(emailHandler.desensitize(EMAIL, emailAnnotation)).isEqualTo("e****@gmail.com");
        assertThat(mobileHandler.desensitize(MOBILE, mobileAnnotation)).isEqualTo("138****5678");
    }

    private <T extends Annotation> T annotation(String fieldName, Class<T> annotationType) throws NoSuchFieldException {
        return AnnotatedFields.class.getDeclaredField(fieldName).getAnnotation(annotationType);
    }

    private static final class AnnotatedFields {

        @EmailDesensitize(disable = "@desensitizationPolicy.disabled")
        private String email;

        @SliderDesensitize(prefixKeep = 3, suffixKeep = 4, disable = "@desensitizationPolicy.disabled")
        private String mobile;
    }

    static final class DesensitizationPolicy {

        private boolean disabled;

        public boolean isDisabled() {
            return disabled;
        }

        void setDisabled(boolean disabled) {
            this.disabled = disabled;
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    static class TestApplication {

        @Bean
        static SpringUtil springUtil() {
            return new SpringUtil();
        }

        @Bean
        DesensitizationPolicy desensitizationPolicy() {
            return new DesensitizationPolicy();
        }
    }
}
