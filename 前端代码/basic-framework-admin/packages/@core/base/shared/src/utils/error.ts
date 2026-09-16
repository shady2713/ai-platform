type ErrorLike = {
  code?: number | string;
  message?: string;
  name?: string;
  response?: {
    data?: unknown;
    status?: number;
  };
  status?: number;
};

function toErrorLike(error: unknown): ErrorLike {
  if (!error || typeof error !== 'object') {
    return {};
  }
  return error as ErrorLike;
}

export function getErrorMessage(error: unknown, fallback = 'Unknown error') {
  if (typeof error === 'string') {
    return error;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  const errorLike = toErrorLike(error);
  if (typeof errorLike.message === 'string' && errorLike.message) {
    return errorLike.message;
  }
  const responseData = errorLike.response?.data;
  if (responseData && typeof responseData === 'object') {
    const data = responseData as {
      error?: string;
      message?: string;
      msg?: string;
    };
    if (typeof data.error === 'string' && data.error) {
      return data.error;
    }
    if (typeof data.message === 'string' && data.message) {
      return data.message;
    }
    if (typeof data.msg === 'string' && data.msg) {
      return data.msg;
    }
  }
  return fallback;
}

function getErrorMeta(error: unknown) {
  const errorLike = toErrorLike(error);
  const meta: Record<string, number | string> = {};
  if (typeof errorLike.name === 'string' && errorLike.name) {
    meta.name = errorLike.name;
  }
  if (
    typeof errorLike.code === 'string' ||
    typeof errorLike.code === 'number'
  ) {
    meta.code = errorLike.code;
  }
  if (typeof errorLike.status === 'number') {
    meta.status = errorLike.status;
  } else if (typeof errorLike.response?.status === 'number') {
    meta.status = errorLike.response.status;
  }
  return meta;
}

function formatLogMessage(
  level: 'error' | 'warn',
  scope: string,
  error?: unknown,
) {
  const hasError = error !== null && error !== undefined;
  const message = hasError ? getErrorMessage(error, 'Unknown error') : '';
  const meta = hasError ? getErrorMeta(error) : {};
  const suffix = Object.keys(meta).length > 0 ? ` ${JSON.stringify(meta)}` : '';
  const base = `[${scope}]${message ? ` ${message}` : ''}${suffix}`;
  if (level === 'warn') {
    console.warn(base);
    return;
  }
  console.error(base);
}

export function logWarn(scope: string, error?: unknown) {
  formatLogMessage('warn', scope, error);
}

export function logError(scope: string, error?: unknown) {
  formatLogMessage('error', scope, error);
}
