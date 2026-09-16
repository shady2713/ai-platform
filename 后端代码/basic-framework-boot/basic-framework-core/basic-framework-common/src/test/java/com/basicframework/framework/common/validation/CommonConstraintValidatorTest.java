package com.basicframework.framework.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.core.ArrayValuable;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommonConstraintValidatorTest {

    @Test
    void stringValidators_delegateNonEmptyValuesAndLeaveRequirednessToBeanValidation() {
        assertThat(new UsernameValidator().isValid(null, null)).isTrue();
        assertThat(new UsernameValidator().isValid("admin", null)).isTrue();
        assertThat(new UsernameValidator().isValid("abc", null)).isFalse();

        assertThat(new PasswordValidator().isValid("", null)).isTrue();
        assertThat(new PasswordValidator().isValid("correct horse battery staple", null))
                .isTrue();
        assertThat(new PasswordValidator().isValid("short-password", null)).isFalse();

        assertThat(new NicknameValidator().isValid(null, null)).isTrue();
        assertThat(new NicknameValidator().isValid("管理员😀", null)).isTrue();
        assertThat(new NicknameValidator().isValid("管理员\n", null)).isFalse();

        assertThat(new EmailExValidator().isValid("", null)).isTrue();
        assertThat(new EmailExValidator().isValid("user@example.com", null)).isTrue();
        assertThat(new EmailExValidator().isValid("user@invalid", null)).isFalse();

        assertThat(new MobileValidator().isValid(null, null)).isTrue();
        assertThat(new MobileValidator().isValid("13812345678", null)).isTrue();
        assertThat(new MobileValidator().isValid("23812345678", null)).isFalse();
    }

    @Test
    void codePointLengthValidator_countsUnicodeCodePointsAndAllowsNull() {
        CodePointLength annotation = mock(CodePointLength.class);
        when(annotation.min()).thenReturn(1);
        when(annotation.max()).thenReturn(2);
        CodePointLengthValidator validator = new CodePointLengthValidator();
        validator.initialize(annotation);

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("😀", null)).isTrue();
        assertThat(validator.isValid("😀a", null)).isTrue();
        assertThat(validator.isValid("😀ab", null)).isFalse();
        assertThat(validator.isValid("", null)).isFalse();
    }

    @Test
    void inEnumValidator_acceptsAllowedValuesAndReportsTheAllowedRange() throws Exception {
        InEnum annotation = EnumInput.class.getDeclaredField("value").getAnnotation(InEnum.class);
        InEnumValidator validator = new InEnumValidator();
        validator.initialize(annotation);

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid(1, null)).isTrue();

        ConstraintValidatorContext context = constraintContext("必须在指定范围 {value}", "必须在指定范围 [1, 2]");
        assertThat(validator.isValid(3, context)).isFalse();
        verify(context).disableDefaultConstraintViolation();
    }

    @Test
    void inEnumCollectionValidator_reportsTheAllowedRangeRatherThanRejectedInput() throws Exception {
        InEnum annotation = EnumInput.class.getDeclaredField("values").getAnnotation(InEnum.class);
        InEnumCollectionValidator validator = new InEnumCollectionValidator();
        validator.initialize(annotation);

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid(List.of(1, 2), null)).isTrue();

        ConstraintValidatorContext context = constraintContext("必须在指定范围 {value}", "必须在指定范围 [1, 2]");
        assertThat(validator.isValid(List.of(1, 3), context)).isFalse();
        verify(context).disableDefaultConstraintViolation();
    }

    private static ConstraintValidatorContext constraintContext(String template, String resolvedTemplate) {
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = mock(ConstraintViolationBuilder.class);
        when(context.getDefaultConstraintMessageTemplate()).thenReturn(template);
        when(context.buildConstraintViolationWithTemplate(resolvedTemplate)).thenReturn(builder);
        when(builder.addConstraintViolation()).thenReturn(context);
        return context;
    }

    private static final class EnumInput {

        @InEnum(SampleEnum.class)
        private Integer value;

        @InEnum(SampleEnum.class)
        private List<Integer> values;
    }

    private enum SampleEnum implements ArrayValuable<Integer> {
        ENABLED,
        DISABLED;

        @Override
        public Integer[] array() {
            return new Integer[] {1, 2};
        }
    }
}
