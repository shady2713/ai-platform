import type {
  CaptchaCheckApi,
  CaptchaGetApi,
  CaptchaHttpResponse,
} from '@vben/types';

import type { VerificationProps } from '../typing';

import { mount } from '@vue/test-utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import VerifySlide from '../verify-slide.vue';

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

const challengeResponse = (): CaptchaHttpResponse<{
  jigsawImageBase64: string;
  originalImageBase64: string;
  token: string;
}> => ({
  data: {
    repCode: '0000',
    repData: {
      jigsawImageBase64: 'BBB',
      originalImageBase64: 'AAA',
      token: 'token-1',
    },
  },
});

const okCheckResponse: CaptchaHttpResponse<unknown> = {
  data: { repCode: '0000' },
};

function mountSlide(props: Partial<VerificationProps> = {}) {
  return mount(VerifySlide, {
    props: {
      checkCaptchaApi: vi.fn(async () => okCheckResponse) as CaptchaCheckApi,
      getCaptchaApi: vi.fn(async () => challengeResponse()) as CaptchaGetApi,
      // type '2' renders the image panel and the refresh button
      type: '2',
      ...props,
    },
  });
}

async function settle() {
  await Promise.resolve();
  await Promise.resolve();
}

function drag(
  wrapper: ReturnType<typeof mountSlide>,
  from: number,
  to: number,
) {
  const block = wrapper.get('.verify-move-block').element;
  block.dispatchEvent(new MouseEvent('mousedown', { clientX: from }));
  window.dispatchEvent(new MouseEvent('mousemove', { clientX: to }));
  window.dispatchEvent(new MouseEvent('mouseup'));
}

describe('verifySlide', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('loads the puzzle challenge, renders images and emits onReady', async () => {
    const wrapper = mountSlide();
    await settle();
    expect(wrapper.emitted('onReady')).toBeTruthy();
    expect(wrapper.find('img').attributes('src')).toContain(
      'data:image/png;base64,AAA',
    );
  });

  it('succeeds when the dragged distance passes the check', async () => {
    const checkCaptchaApi = vi.fn(async (_payload: unknown) => okCheckResponse);
    const wrapper = mountSlide({ checkCaptchaApi });
    await settle();

    drag(wrapper, 100, 200);
    await settle();
    vi.advanceTimersByTime(1000);
    await settle();

    expect(checkCaptchaApi).toHaveBeenCalledTimes(1);
    expect(checkCaptchaApi.mock.calls[0]?.[0]).toMatchObject({
      captchaType: 'blockPuzzle',
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
        data: { repCode: '0001', repMsg: 'try again' },
      }),
    );
    const wrapper = mountSlide({ checkCaptchaApi });
    await settle();

    drag(wrapper, 100, 200);
    await settle();

    expect(wrapper.emitted('onError')).toBeTruthy();
    expect(wrapper.text()).toContain('try again');
    // auto refresh and tip cleanup run 1s later
    vi.advanceTimersByTime(1000);
    await settle();
    // the reset callback restores the default prompt text 300ms after refresh
    vi.advanceTimersByTime(300);
    await settle();
    expect(wrapper.text()).toContain('ui.captcha.sliderDefaultText');
  });

  it('emits onError when the check api throws', async () => {
    const checkCaptchaApi = vi.fn(async () => {
      throw new Error('network');
    });
    const wrapper = mountSlide({ checkCaptchaApi });
    await settle();

    drag(wrapper, 100, 200);
    await settle();
    vi.advanceTimersByTime(1000);
    await settle();
    expect(wrapper.emitted('onError')).toBeTruthy();
  });

  it('does nothing when no token was loaded yet', async () => {
    const checkCaptchaApi = vi.fn(async (_payload: unknown) => okCheckResponse);
    const getCaptchaApi = vi.fn(
      async (): Promise<CaptchaHttpResponse<unknown>> => ({
        data: { repCode: '0001', repMsg: 'no challenge' },
      }),
    ) as unknown as CaptchaGetApi;
    const wrapper = mountSlide({ checkCaptchaApi, getCaptchaApi });
    await settle();

    drag(wrapper, 40, 120);
    vi.advanceTimersByTime(1000);
    await settle();
    expect(checkCaptchaApi).not.toHaveBeenCalled();
    expect(wrapper.emitted('onError')).toBeTruthy();
  });

  it('refreshes the image and resets the block position', async () => {
    const wrapper = mountSlide();
    await settle();
    await wrapper.get('.verify-refresh').trigger('click');
    await settle();
    const block = wrapper.get('.verify-move-block').element as HTMLElement;
    expect(block.style.left).toBe('0px');
    expect(wrapper.find('img').attributes('src')).toContain(
      'data:image/png;base64,AAA',
    );
  });
});
