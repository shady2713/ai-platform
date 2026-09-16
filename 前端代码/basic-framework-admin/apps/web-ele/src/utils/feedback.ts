import type {
  ElMessageBoxOptions,
  MessageBoxData,
  MessageHandler,
  NotificationHandle,
} from 'element-plus';

import { ElMessage, ElMessageBox, ElNotification } from 'element-plus';

const REQUEST_PARAM_PREFIX_RE =
  /^\u8BF7\u6C42\u53C2\u6570\u4E0D\u6B63\u786E[:\uFF1A]\s*/;

type UnknownRecord = Record<string, unknown>;

function toRecord(value: unknown): UnknownRecord {
  return typeof value === 'object' && value !== null
    ? (value as UnknownRecord)
    : {};
}

function toMessage(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  if (value === null || value === undefined) {
    return '';
  }
  return String(value);
}

export function normalizeErrorMessage(message?: null | string) {
  if (!message) {
    return '';
  }
  return message.trim().replace(REQUEST_PARAM_PREFIX_RE, '');
}

export function extractErrorMessage(error: unknown, fallback = '') {
  if (!error) {
    return fallback;
  }
  if (typeof error === 'string') {
    return normalizeErrorMessage(error) || fallback;
  }

  const errorObject = toRecord(error);
  const response = toRecord(errorObject.response);
  const responseData = toRecord(
    response.data ?? errorObject.data ?? errorObject.error,
  );
  const candidates = [
    responseData.error,
    responseData.message,
    responseData.msg,
    errorObject.message,
  ];
  for (const candidate of candidates) {
    const message = normalizeErrorMessage(toMessage(candidate));
    if (message) {
      return message;
    }
  }
  return fallback;
}

export function showErrorMessage(
  message?: null | string,
  fallback = '\u64CD\u4F5C\u5931\u8D25',
) {
  return ElMessage.error(normalizeErrorMessage(message) || fallback);
}

export function showError(
  error: unknown,
  fallback = '\u64CD\u4F5C\u5931\u8D25',
) {
  return showErrorMessage(extractErrorMessage(error, fallback), fallback);
}

/**
 * 展示由全局请求拦截器消费的错误。401 由重新认证流程接管，不重复弹出 toast。
 */
export function showRequestError(error: unknown, fallback: string) {
  const errorObject = toRecord(error);
  const response = toRecord(errorObject.response);
  const status = response.status;
  if (status === 401) {
    return;
  }
  const responseData = toRecord(response.data);
  const responseMessage = [
    responseData.error,
    responseData.message,
    responseData.msg,
  ]
    .map((candidate) => normalizeErrorMessage(toMessage(candidate)))
    .find(Boolean);
  return showErrorMessage(responseMessage || fallback);
}

export function showWarningMessage(message: string): MessageHandler {
  return ElMessage.warning(message);
}

export function showSuccessMessage(message: string): MessageHandler {
  return ElMessage.success(message);
}

export function showLoadingMessage(message: string): MessageHandler {
  return ElMessage({
    message,
    plain: true,
    type: 'success',
  });
}

/** 二次确认对话框：Promise reject 表示用户取消，调用方捕获后直接结束流程 */
export function showConfirmDialog(
  message: string,
  options?: ElMessageBoxOptions,
): Promise<MessageBoxData> {
  return ElMessageBox.confirm(message, options);
}

/** 结果展示对话框，支持 HTML 内容（调用方需先转义动态文本） */
export function showAlertDialog(
  message: string,
  title: string,
  options?: ElMessageBoxOptions,
): Promise<MessageBoxData> {
  return ElMessageBox.alert(message, title, options);
}

export function showSuccessNotification(options: {
  duration?: number;
  message: string;
  title: string;
}): NotificationHandle {
  return ElNotification.success(options);
}
