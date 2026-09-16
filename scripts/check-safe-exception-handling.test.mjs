import assert from 'node:assert/strict';
import test from 'node:test';

import {
  unsafeCaughtExceptionMessageAccesses,
  unsafeExceptionLogCalls,
} from './check-safe-exception-handling.mjs';

test('拒绝直接记录 catch 异常及其消息', () => {
  const source = `
    try { execute(); } catch (RuntimeException failure) {
      log.error("failed", failure);
      logger.warn("failed: {}", failure.getMessage());
    }
  `;

  assert.deepEqual(unsafeExceptionLogCalls(source), [
    { argument: 'failure', level: 'error', line: 3 },
  ]);
  assert.deepEqual(unsafeCaughtExceptionMessageAccesses(source), [
    { accessor: '.getMessage(', line: 4 },
  ]);
});

test('允许安全格式器、异常类名以及注释和字符串中的示例', () => {
  const source = `
    try { execute(); } catch (RuntimeException exception) {
      log.error("failed: {}", format(exception));
      log.warn("failed: {}", exception.getClass().getName());
      // log.error("unsafe", exception);
      String example = "log.error(ignored, exception)";
    }
  `;

  assert.deepEqual(unsafeExceptionLogCalls(source), []);
  assert.deepEqual(unsafeCaughtExceptionMessageAccesses(source), []);
});

test('能处理日志模板中的括号、逗号和多行调用', () => {
  const source = `
    try { execute(); } catch (Exception ex) {
      log.error(
        "failed ({}, {})",
        Map.of("key", "value"),
        ex);
    }
  `;

  assert.deepEqual(unsafeExceptionLogCalls(source), [
    { argument: 'ex', level: 'error', line: 3 },
  ]);
});

test('拒绝 catch 块通过别名、根因工具或非日志出口读取异常正文', () => {
  const source = `
    try { execute(); } catch (RuntimeException exception) {
      Throwable current = exception;
      response.put("failure", current.getLocalizedMessage());
      throw new IllegalStateException(ExceptionUtil.getRootCauseMessage(exception));
    }
  `;

  assert.deepEqual(unsafeCaughtExceptionMessageAccesses(source), [
    { accessor: '.getLocalizedMessage(', line: 4 },
    { accessor: 'ExceptionUtil.getRootCauseMessage(', line: 5 },
  ]);
});

test('允许 catch 块使用显式公开业务文案和安全辅助方法', () => {
  const source = `
    try { execute(); } catch (ServiceException exception) {
      response.put("failure", exception.getPublicMessage());
    } catch (ConstraintViolationException exception) {
      response.put("failure", firstConstraintViolationMessage(exception));
    }
  `;

  assert.deepEqual(unsafeCaughtExceptionMessageAccesses(source), []);
});
