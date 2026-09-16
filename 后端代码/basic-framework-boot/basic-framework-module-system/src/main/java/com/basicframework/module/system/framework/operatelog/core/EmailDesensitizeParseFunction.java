package com.basicframework.module.system.framework.operatelog.core;

import com.mzt.logapi.service.IParseFunction;
import org.springframework.stereotype.Component;

/** 操作审计中邮箱的脱敏展示函数。 */
@Component
public class EmailDesensitizeParseFunction implements IParseFunction {

    public static final String NAME = "maskEmail";

    @Override
    public String functionName() {
        return NAME;
    }

    @Override
    public String apply(Object value) {
        return OperationLogSensitiveDataMasker.maskEmail(value);
    }
}
