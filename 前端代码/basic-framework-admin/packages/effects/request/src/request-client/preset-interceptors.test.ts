import type { RequestClient } from './request-client';

import { describe, expect, it, vi } from 'vitest';

import {
  authenticateResponseInterceptor,
  defaultResponseInterceptor,
  errorMessageResponseInterceptor,
} from './preset-interceptors';

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

describe('defaultResponseInterceptor', () => {
  const interceptor = defaultResponseInterceptor({
    codeField: 'code',
    dataField: 'data',
    successCode: 0,
  });

  it('unwraps the data field on 2xx with the success code', () => {
    const response = {
      config: { responseReturn: 'data' },
      data: { code: 0, data: { id: 1 } },
      status: 200,
    };
    expect(
      interceptor.fulfilled?.(
        response as unknown as Parameters<
          NonNullable<typeof interceptor.fulfilled>
        >[0],
      ),
    ).toEqual({ id: 1 });
  });

  it('still rejects a 2xx response whose body code is not the success code', () => {
    const response = {
      config: { responseReturn: 'data' },
      data: { code: 1_000_000_001, msg: 'boom' },
      status: 200,
    };
    let thrown: unknown;
    try {
      interceptor.fulfilled?.(
        response as unknown as Parameters<
          NonNullable<typeof interceptor.fulfilled>
        >[0],
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeDefined();
    expect(thrown).toEqual(expect.objectContaining({ response }));
  });
});

describe('authenticateResponseInterceptor', () => {
  function buildInterceptor(enableRefreshToken = false) {
    const doReAuthenticate = vi.fn().mockResolvedValue(undefined);
    const doRefreshToken = vi.fn();
    const client = {
      isRefreshing: false,
      refreshTokenQueue: [] as RequestClient['refreshTokenQueue'],
      request: vi.fn(),
    };
    const interceptor = authenticateResponseInterceptor({
      client: client as unknown as RequestClient,
      doReAuthenticate,
      doRefreshToken,
      enableRefreshToken,
      formatToken: (token: string) => token,
    });
    return { client, doReAuthenticate, doRefreshToken, interceptor };
  }

  it('ignores body code 401 when the HTTP status is not 401', async () => {
    const { doReAuthenticate, interceptor } = buildInterceptor();
    const error = {
      config: {},
      data: { code: 401 },
      response: { status: 500 },
    };
    await expect(interceptor.rejected?.(error)).rejects.toBe(error);
    expect(doReAuthenticate).not.toHaveBeenCalled();
  });

  it('re-authenticates on HTTP 401 when refresh token is disabled', async () => {
    const { doReAuthenticate, interceptor } = buildInterceptor();
    const error = { config: {}, response: { status: 401 } };
    await expect(interceptor.rejected?.(error)).rejects.toBe(error);
    expect(doReAuthenticate).toHaveBeenCalledTimes(1);
  });

  it('preserves the original 401 when token refresh fails', async () => {
    const { client, doReAuthenticate, doRefreshToken, interceptor } =
      buildInterceptor(true);
    const refreshError = new Error('Refresh token is null');
    doRefreshToken.mockRejectedValue(refreshError);
    const error = {
      config: { headers: {}, url: '/system/user/get' },
      response: { status: 401 },
    };

    await expect(interceptor.rejected?.(error)).rejects.toBe(error);

    expect(doReAuthenticate).toHaveBeenCalledTimes(1);
    expect(client.isRefreshing).toBe(false);
  });

  it('rejects every queued request with its original 401 when refresh fails', async () => {
    const { client, doRefreshToken, interceptor } = buildInterceptor(true);
    let rejectRefresh: ((reason: unknown) => void) | undefined;
    doRefreshToken.mockReturnValue(
      new Promise<string>((_resolve, reject) => {
        rejectRefresh = reject;
      }),
    );
    const firstError = {
      config: { headers: {}, url: '/first' },
      response: { status: 401 },
    };
    const secondError = {
      config: { headers: {}, url: '/second' },
      response: { status: 401 },
    };

    const firstRequest = interceptor.rejected?.(firstError);
    await vi.waitFor(() => expect(client.isRefreshing).toBe(true));
    const secondRequest = interceptor.rejected?.(secondError);
    expect(client.refreshTokenQueue).toHaveLength(1);

    rejectRefresh?.(new Error('refresh unavailable'));

    await expect(firstRequest).rejects.toBe(firstError);
    await expect(secondRequest).rejects.toBe(secondError);
    expect(client.refreshTokenQueue).toHaveLength(0);
    expect(client.request).not.toHaveBeenCalled();
  });

  it('retries each queued request only once after a successful refresh', async () => {
    const { client, doRefreshToken, doReAuthenticate, interceptor } =
      buildInterceptor(true);
    let finish!: (value: string) => void;
    doRefreshToken.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    client.request.mockImplementation(async (url, config) => {
      if (url === '/queued') {
        return interceptor.rejected?.({ config, response: { status: 401 } });
      }
      return 'success';
    });
    const first = interceptor.rejected?.({
      config: { headers: {}, url: '/first' },
      response: { status: 401 },
    });
    const queued = interceptor.rejected?.({
      config: { headers: {}, url: '/queued' },
      response: { status: 401 },
    });
    finish('renewed');
    await expect(first).resolves.toBe('success');
    await expect(queued).rejects.toMatchObject({ response: { status: 401 } });
    expect(doRefreshToken).toHaveBeenCalledOnce();
    expect(doReAuthenticate).toHaveBeenCalledOnce();
    expect(client.refreshTokenQueue).toHaveLength(0);
  });
});

describe('errorMessageResponseInterceptor', () => {
  it('normalizes the error by HTTP status only, ignoring body code', async () => {
    const makeErrorMessage = vi.fn();
    const { rejected } = errorMessageResponseInterceptor(makeErrorMessage);
    const error = {
      response: { data: { code: 500 }, status: 404 },
      toString: () => 'Error',
    };
    await expect(rejected?.(error)).rejects.toBe(error);
    expect(makeErrorMessage).toHaveBeenCalledWith(
      'ui.fallback.http.notFound',
      error,
    );
  });

  it.each([
    [400, 'ui.fallback.http.badRequest'],
    [409, 'ui.fallback.http.conflict'],
    [422, 'ui.fallback.http.unprocessableEntity'],
    [429, 'ui.fallback.http.tooManyRequests'],
    [503, 'ui.fallback.http.serviceUnavailable'],
  ])('maps HTTP %i to %s exactly once', async (status, expectedMessage) => {
    const makeErrorMessage = vi.fn();
    const { rejected } = errorMessageResponseInterceptor(makeErrorMessage);
    const error = {
      config: { errorMode: 'global' },
      response: { data: {}, status },
      toString: () => 'Error',
    };
    await expect(rejected?.(error)).rejects.toBe(error);
    expect(makeErrorMessage).toHaveBeenCalledOnce();
    expect(makeErrorMessage).toHaveBeenCalledWith(expectedMessage, error);
  });

  it.each(['inline', 'silent'] as const)(
    'leaves %s errors to the caller without a global message',
    async (errorMode) => {
      const makeErrorMessage = vi.fn();
      const { rejected } = errorMessageResponseInterceptor(makeErrorMessage);
      const error = {
        config: { errorMode },
        response: { data: { details: [{ field: 'email' }] }, status: 422 },
        toString: () => 'Error',
      };
      await expect(rejected?.(error)).rejects.toBe(error);
      expect(makeErrorMessage).not.toHaveBeenCalled();
    },
  );

  it('falls back to the internal server error text for unmapped statuses', async () => {
    const makeErrorMessage = vi.fn();
    const { rejected } = errorMessageResponseInterceptor(makeErrorMessage);
    const error = {
      response: { data: {}, status: 418 },
      toString: () => 'Error',
    };
    await expect(rejected?.(error)).rejects.toBe(error);
    expect(makeErrorMessage).toHaveBeenCalledWith(
      'ui.fallback.http.internalServerError',
      error,
    );
  });

  it('keeps the network error mapping', async () => {
    const makeErrorMessage = vi.fn();
    const { rejected } = errorMessageResponseInterceptor(makeErrorMessage);
    const error = new Error('Network Error');
    await expect(rejected?.(error)).rejects.toBe(error);
    expect(makeErrorMessage).toHaveBeenCalledWith(
      'ui.fallback.http.networkError',
      error,
    );
  });
});
