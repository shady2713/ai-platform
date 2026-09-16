import { flushPromises, mount } from '@vue/test-utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CheckUpdates from './check-updates.vue';

interface ModalOptions {
  onCancel: () => void;
  onConfirm: () => void;
}

const state = vi.hoisted(() => ({
  hidden: false,
  modalOptions: undefined as ModalOptions | undefined,
  open: vi.fn(),
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('@vben-core/popup-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    useVbenModal: vi.fn((options: ModalOptions) => {
      state.modalOptions = options;
      return [
        defineComponent({
          name: 'UpdateNoticeModalStub',
          template: '<section><slot /></section>',
        }),
        { open: state.open },
      ];
    }),
  };
});

function versionResponse(version: string, header = 'etag') {
  return {
    headers: new Headers({ [header]: version }),
  } as Response;
}

describe('check updates', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    state.hidden = false;
    state.open.mockClear();
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => state.hidden);
    vi.stubGlobal('location', {
      hostname: 'admin.example.com',
      reload: vi.fn(),
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('detects a changed deployment and resumes polling after cancel', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(versionResponse('v1', 'last-modified'))
      .mockResolvedValueOnce(versionResponse('v2'))
      .mockResolvedValueOnce(versionResponse('v2'))
      .mockResolvedValueOnce(versionResponse('v3'));
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(CheckUpdates, {
      props: { checkUpdateUrl: '/version', checkUpdatesInterval: 2 },
    });

    await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/version',
      expect.objectContaining({
        cache: 'no-cache',
        method: 'HEAD',
        redirect: 'manual',
        signal: expect.any(AbortSignal),
      }),
    );
    expect(state.open).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
    expect(state.open).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);

    state.modalOptions?.onCancel();
    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
    expect(state.open).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
    expect(state.open).toHaveBeenCalledTimes(2);

    state.modalOptions?.onConfirm();
    expect(location.reload).toHaveBeenCalledOnce();
    wrapper.unmount();
  });

  it('allows only one version request at a time', async () => {
    let requestSignal: AbortSignal | undefined;
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      requestSignal = init?.signal ?? undefined;
      return new Promise<Response>((_resolve, reject) => {
        requestSignal?.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'));
        });
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(CheckUpdates);

    await vi.advanceTimersByTimeAsync(60 * 1000);
    await vi.advanceTimersByTimeAsync(60 * 1000);
    expect(fetchMock).toHaveBeenCalledOnce();

    wrapper.unmount();
    await flushPromises();
    expect(requestSignal?.aborted).toBe(true);
  });

  it('aborts an active request while hidden and stays stopped', async () => {
    let requestSignal: AbortSignal | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => {
        requestSignal = init?.signal ?? undefined;
        return new Promise<Response>((_resolve, reject) => {
          requestSignal?.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'));
          });
        });
      }),
    );
    const wrapper = mount(CheckUpdates);

    await vi.advanceTimersByTimeAsync(60 * 1000);
    state.hidden = true;
    document.dispatchEvent(new Event('visibilitychange'));
    await flushPromises();

    expect(requestSignal?.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    wrapper.unmount();
  });

  it('checks immediately when the page becomes visible again', async () => {
    const fetchMock = vi.fn().mockResolvedValue(versionResponse('v1'));
    vi.stubGlobal('fetch', fetchMock);
    state.hidden = true;
    const wrapper = mount(CheckUpdates);
    expect(vi.getTimerCount()).toBe(0);

    state.hidden = false;
    document.dispatchEvent(new Event('visibilitychange'));
    await flushPromises();

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(1);
    wrapper.unmount();
  });

  it('disables invalid intervals and ignores local or failed checks', async () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});
    const fetchMock = vi.fn().mockRejectedValue(new Error('offline'));
    vi.stubGlobal('fetch', fetchMock);
    const invalidWrapper = mount(CheckUpdates, {
      props: { checkUpdatesInterval: Number.NaN },
    });
    expect(vi.getTimerCount()).toBe(0);
    invalidWrapper.unmount();

    vi.stubGlobal('location', { hostname: 'localhost', reload: vi.fn() });
    const localWrapper = mount(CheckUpdates);
    await vi.advanceTimersByTimeAsync(60 * 1000);
    expect(fetchMock).not.toHaveBeenCalled();
    localWrapper.unmount();

    vi.stubGlobal('location', {
      hostname: 'admin.example.com',
      reload: vi.fn(),
    });
    const failedWrapper = mount(CheckUpdates);
    await vi.advanceTimersByTimeAsync(60 * 1000);
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(consoleError).toHaveBeenCalledWith('Failed to fetch version tag');
    failedWrapper.unmount();
  });
});
