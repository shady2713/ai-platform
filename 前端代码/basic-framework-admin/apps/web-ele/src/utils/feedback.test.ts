import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  extractErrorMessage,
  normalizeErrorMessage,
  showAlertDialog,
  showConfirmDialog,
  showRequestError,
  showSuccessNotification,
} from './feedback';

const { alertMock, confirmMock, errorMessageMock, notificationSuccessMock } =
  vi.hoisted(() => ({
    alertMock: vi.fn(),
    confirmMock: vi.fn(),
    errorMessageMock: vi.fn(),
    notificationSuccessMock: vi.fn(),
  }));

vi.mock('element-plus', () => ({
  ElMessage: Object.assign(vi.fn(), {
    error: errorMessageMock,
    success: vi.fn(),
    warning: vi.fn(),
  }),
  ElMessageBox: {
    alert: alertMock,
    confirm: confirmMock,
  },
  ElNotification: {
    success: notificationSuccessMock,
  },
}));

describe('feedback utils', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('strips the bad request prefix', () => {
    expect(normalizeErrorMessage('请求参数不正确:手机号格式不正确')).toBe(
      '手机号格式不正确',
    );
  });

  it('prefers backend response messages', () => {
    expect(
      extractErrorMessage({
        response: {
          data: {
            msg: '请求参数不正确:邮箱格式不正确',
          },
        },
      }),
    ).toBe('邮箱格式不正确');
  });

  it('falls back to the error message field', () => {
    expect(extractErrorMessage(new Error('系统异常'), '操作失败')).toBe(
      '系统异常',
    );
  });

  it('lets the authentication flow own a body-less 401 without a toast', () => {
    showRequestError({ response: { status: 401 } }, '认证失败');
    expect(errorMessageMock).not.toHaveBeenCalled();
  });

  it.each([
    [400, '邮箱格式不正确', '请求错误'],
    [409, '数据已存在', '数据冲突'],
    [422, '合同日期不正确', '请求内容无法处理'],
    [429, '请求过于频繁', '请求过于频繁'],
    [503, undefined, '服务暂时不可用'],
  ])(
    'shows HTTP %i once and prefers a safe business message',
    (status, message, fallback) => {
      showRequestError(
        {
          message: `Request failed with status code ${status}`,
          response: { data: message ? { msg: message } : {}, status },
        },
        fallback,
      );
      expect(errorMessageMock).toHaveBeenCalledOnce();
      expect(errorMessageMock).toHaveBeenCalledWith(message || fallback);
    },
  );
});

describe('feedback dialog wrappers', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('showConfirmDialog forwards message and options', async () => {
    confirmMock.mockResolvedValue('confirm');
    const options = {
      cancelButtonText: '取消',
      confirmButtonText: '确定',
      type: 'warning' as const,
    };
    await expect(showConfirmDialog('确认删除？', options)).resolves.toBe(
      'confirm',
    );
    expect(confirmMock).toHaveBeenCalledWith('确认删除？', options);
  });

  it('showConfirmDialog works without options and propagates rejection', async () => {
    confirmMock.mockRejectedValue(new Error('cancel'));
    await expect(showConfirmDialog('确认删除？')).rejects.toThrow('cancel');
    expect(confirmMock).toHaveBeenCalledWith('确认删除？', undefined);
  });

  it('showAlertDialog forwards message, title and options', async () => {
    alertMock.mockResolvedValue('confirm');
    const options = {
      confirmButtonText: '确定',
      dangerouslyUseHTMLString: true,
      type: 'warning' as const,
    };
    await expect(
      showAlertDialog('<p>结果</p>', '导入结果', options),
    ).resolves.toBe('confirm');
    expect(alertMock).toHaveBeenCalledWith('<p>结果</p>', '导入结果', options);
  });

  it('showAlertDialog works without options', async () => {
    alertMock.mockResolvedValue('confirm');
    await expect(showAlertDialog('内容', '标题')).resolves.toBe('confirm');
    expect(alertMock).toHaveBeenCalledWith('内容', '标题', undefined);
  });

  it('showSuccessNotification forwards the notification payload', () => {
    const handle = { close: vi.fn() };
    notificationSuccessMock.mockReturnValue(handle);
    const options = { duration: 3, message: '欢迎:管理员', title: '登录成功' };
    expect(showSuccessNotification(options)).toBe(handle);
    expect(notificationSuccessMock).toHaveBeenCalledWith(options);
  });
});
