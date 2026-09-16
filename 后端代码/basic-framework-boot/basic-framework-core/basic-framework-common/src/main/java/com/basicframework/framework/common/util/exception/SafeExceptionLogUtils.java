package com.basicframework.framework.common.util.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 将异常转换为可诊断但不包含异常消息的有界日志文本。 */
public final class SafeExceptionLogUtils {

    private static final int MAX_STACK_TRACE_LENGTH = 32_000;
    private static final int MAX_CAUSE_DEPTH = 8;

    private SafeExceptionLogUtils() {}

    /**
     * 保留异常类型、调用位置和 cause 结构，删除可能携带凭据、SQL 参数或请求原值的异常消息。
     *
     * @param throwable 待记录异常
     * @return 不含异常消息且长度受限的堆栈文本
     */
    public static String format(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && visited.add(current); depth++) {
            if (depth > 0) {
                result.append("\nCaused by: ");
            }
            result.append(current.getClass().getName());
            for (StackTraceElement element : current.getStackTrace()) {
                String frame = "\n\tat " + element;
                if (result.length() + frame.length() > MAX_STACK_TRACE_LENGTH) {
                    return result.append("\n\t... truncated").toString();
                }
                result.append(frame);
            }
            current = current.getCause();
        }
        return result.toString();
    }
}
