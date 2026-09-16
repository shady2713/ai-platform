package com.basicframework.module.system.framework.operatelog.core;

import com.mzt.logapi.service.IParseFunction;
import org.springframework.stereotype.Component;

/** 操作审计中手机号的脱敏展示函数。 */
@Component
public class MobileDesensitizeParseFunction implements IParseFunction {

    public static final String NAME = "maskMobile";

    @Override
    public String functionName() {
        return NAME;
    }

    @Override
    public String apply(Object value) {
        return OperationLogSensitiveDataMasker.maskMobile(value);
    }
}
