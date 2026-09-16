import type {
  CaptchaCheckApi,
  CaptchaGetApi,
  CaptchaHttpResponse,
} from '@vben/types';

import type { VerificationProps } from '../typing';

import { mount } from '@vue/test-utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import VerifyPoints from '../verify-points.vue';

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

const challengeResponse = (): CaptchaHttpResponse<{
  originalImageBase64: string;
  token: string;
  wordList: string[];
}> => ({
  data: {
    repCode: '0000',
    repData: {
      originalImageBase64: 'AAA',
      token: 'token-1',
      wordList: ['甲', '乙'],
    },
  },
});

const okCheckResponse: CaptchaHttpResponse<unknown> = {
  data: { repCode: '0000' },
};

function mountPoints(props: Partial<VerificationProps> = {}) {
  return mount(VerifyPoints, {
    props: {
      checkCaptchaApi: vi.fn(async () => okCheckResponse) as CaptchaCheckApi,
      getCaptchaApi: vi.fn(async () => challengeResponse()) as CaptchaGetApi,
      ...props,
    },
  });
}

async function settle() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('verifyPoints', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('loads the picture challenge and emits onReady', async () => {
    const wrapper = mountPoints();
    await settle();
    expect(wrapper.emitted('onReady')).toBeTruthy();
    expect(wrapper.find('img').attributes('src')).toContain(
      'data:image/png;base64,AAA',
    );
    expect(wrapper.text()).toContain('甲,乙');
  });

  it('verifies the selected points and succeeds when the check passes', async () => {
    const checkCaptchaApiMock = vi.fn(
      async (_payload: unknown) => okCheckResponse,
    );
    const checkCaptchaApi = checkCaptchaApiMock as CaptchaCheckApi;
    const wrapper = mountPoints({ checkCaptchaApi });
    await settle();

    const img = wrapper.get('img');
    await img.trigger('click', { offsetX: 30, offsetY: 40 });
    await img.trigger('click', { offsetX: 80, offsetY: 90 });
    vi.advanceTimersByTime(400);
    await settle();

    expect(checkCaptchaApiMock).toHaveBeenCalledTimes(1);
    expect(checkCaptchaApiMock.mock.calls[0]?.[0]).toMatchObject({
      captchaType: 'clickWord',
      token: 'token-1',
    });
    expect(wrapper.emitted('onSuccess')).toBeTruthy();
    const payload = wrapper.emitted('onSuccess')?.[0]?.[0] as {
      captchaVerification: string;
    };
    expect(payload.captchaVerification).toContain('---');
  });

  it('fails the verification when the check is rejected', async () => {
    const checkCaptchaApi = vi.fn(
      async (): Promise<CaptchaHttpResponse<unknown>> => ({
        data: { repCode: '0001', repMsg: 'wrong order' },
      }),
    ) as CaptchaCheckApi;
    const wrapper = mountPoints({ checkCaptchaApi });
    await settle();

    const img = wrapper.get('img');
    await img.trigger('click', { offsetX: 10, offsetY: 10 });
    await img.trigger('click', { offsetX: 20, offsetY: 20 });
    vi.advanceTimersByTime(400);
    await settle();

    expect(wrapper.emitted('onError')).toBeTruthy();
    expect(wrapper.text()).toContain('wrong order');
    // failure schedules an auto refresh after 700ms
    vi.advanceTimersByTime(700);
  });

  it('emits onError when the check api throws', async () => {
    const checkCaptchaApi = vi.fn(async () => {
      throw new Error('network');
    });
    const wrapper = mountPoints({ checkCaptchaApi });
    await settle();

    const img = wrapper.get('img');
    await img.trigger('click', { offsetX: 10, offsetY: 10 });
    await img.trigger('click', { offsetX: 20, offsetY: 20 });
    vi.advanceTimersByTime(400);
    await settle();
    expect(wrapper.emitted('onError')).toBeTruthy();
    vi.advanceTimersByTime(700);
  });

  it('gets disabled while verifying and refreshes via the refresh button', async () => {
    const wrapper = mountPoints();
    await settle();
    await wrapper.get('.verify-refresh').trigger('click');
    await settle();
    expect(wrapper.emitted('onReady')).toHaveLength(1);
    // after refresh the challenge is fetched again
    expect(wrapper.find('img').attributes('src')).toContain(
      'data:image/png;base64,AAA',
    );
  });
});
