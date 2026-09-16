package com.basicframework.framework.lock4j.core;

import com.baomidou.lock.LockFailureStrategy;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;

/**
 * 自定义获取锁失败策略，抛出 {@link ServiceException} 异常
 */
@Slf4j
public class DefaultLockFailureStrategy implements LockFailureStrategy {

    /**
     * 兜底文案：{@link GlobalErrorCodeConstants#LOCKED} 的 msg 为 null 时使用，
     * 保证抛出的 {@link ServiceException} 永远携带非 null 错误提示
     */
    private static final String FALLBACK_MESSAGE = "请求失败，请稍后重试";

    @Override
    public void onLockFailure(String key, Method method, Object[] arguments) {
        log.debug(
                "[onLockFailure][线程({}) method({}) key({}) 获取锁失败]",
                Thread.currentThread().getName(),
                method != null ? method.getName() : null,
                key);
        String message = GlobalErrorCodeConstants.LOCKED.getMsg();
        if (message == null || message.isBlank()) {
            message = FALLBACK_MESSAGE;
        }
        throw new ServiceException(GlobalErrorCodeConstants.LOCKED.getCode(), message);
    }
}
