package com.basicframework.framework.common.validation;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EmailExValidator implements ConstraintValidator<EmailEx, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (StrUtil.isEmpty(value)) {
            return true;
        }
        return ValidationUtils.isEmail(value);
    }
}
