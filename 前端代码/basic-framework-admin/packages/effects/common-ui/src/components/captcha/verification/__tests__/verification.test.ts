import type { VueWrapper } from '@vue/test-utils';

import type {
  CaptchaCheckApi,
  CaptchaGetApi,
  CaptchaHttpResponse,
  CaptchaSuccessPayload,
} from '@vben/types';

import { flushPromises, mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import Verification from '../index.vue';
import VerifyPoints from '../verify-points.vue';
import VerifySlide from '../verify-slide.vue';

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

/**
 * Verification 分发组件的公开面：弹窗开合/关闭、子组件事件透传、刷新转发，
 * 以及 blockPuzzle/clickWord 两种验证码的分发。覆盖 index.vue 的 defineExpose 全部方法。
 */
const okCheckResponse: CaptchaHttpResponse<unknown> = {
  data: { repCode: '0000' },
};

const slideChallenge = (): CaptchaHttpResponse<{
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

const pointsChallenge = (): CaptchaHttpResponse<{
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

interface VerificationExposed {
  onClose: () => void;
  onError: (proxy: null) => void;
  onReady: (proxy: null) => void;
  onSuccess: (payload: CaptchaSuccessPayload) => void;
  refresh: () => Promise<void> | void;
  show: () => void;
}

function exposed(wrapper: VueWrapper): VerificationExposed {
  return wrapper.vm as unknown as VerificationExposed;
}

function propsFor(captchaType: 'blockPuzzle' | 'clickWord') {
  return {
    captchaType,
    checkCaptchaApi: vi.fn(async () => okCheckResponse) as CaptchaCheckApi,
    getCaptchaApi: vi.fn(async () =>
      captchaType === 'blockPuzzle' ? slideChallenge() : pointsChallenge(),
    ) as unknown as CaptchaGetApi,
  };
}

const globalOptions = { mocks: { $t: (key: string) => key } };

describe('verification（分发组件）', () => {
  it('pop 模式：关闭按钮关闭、show 重新打开、透传子组件事件并转发 refresh', async () => {
    const props = propsFor('blockPuzzle');
    const wrapper = mount(Verification, {
      global: globalOptions,
      props: { ...props, mode: 'pop' },
    });
    await flushPromises();

    // blockPuzzle 分发到 VerifySlide
    const slide = wrapper.findComponent(VerifySlide);
    expect(slide.exists()).toBe(true);

    // pop 模式初始隐藏，show() 打开（登录页通过 ref 调用）
    expect(wrapper.attributes('style')).toContain('display: none');
    exposed(wrapper).show();
    await flushPromises();
    expect(wrapper.attributes('style') ?? '').not.toContain('display: none');

    // 子组件挂载即上报 onReady；error/success 由公开方法原样透传
    expect(wrapper.emitted('onReady')).toHaveLength(1);
    exposed(wrapper).onError(null);
    const payload: CaptchaSuccessPayload = {
      captchaVerification: 'token-1---{x:1}',
    };
    exposed(wrapper).onSuccess(payload);
    expect(wrapper.emitted('onError')?.[0]).toEqual([null]);
    expect(wrapper.emitted('onSuccess')?.[0]).toEqual([payload]);

    // refresh 转发给子组件（再次拉题）
    await exposed(wrapper).refresh();
    await flushPromises();
    expect(props.getCaptchaApi).toHaveBeenCalledTimes(2);

    // 关闭按钮：onClose 事件 + 隐藏
    await wrapper.get('.verifybox-close').trigger('click');
    expect(wrapper.emitted('onClose')).toHaveLength(1);
    expect(wrapper.attributes('style')).toContain('display: none');
  });

  it('clickWord 分发到 VerifyPoints', async () => {
    const wrapper = mount(Verification, {
      global: globalOptions,
      props: { ...propsFor('clickWord'), mode: 'pop' },
    });
    await flushPromises();

    expect(wrapper.findComponent(VerifyPoints).exists()).toBe(true);
    expect(wrapper.findComponent(VerifySlide).exists()).toBe(false);
  });

  it('fixed 模式：直接展示、无弹窗标题行，show() 不改变展示状态', async () => {
    const wrapper = mount(Verification, {
      global: globalOptions,
      props: { ...propsFor('blockPuzzle'), mode: 'fixed' },
    });

    // 异步子组件尚未就绪时 refresh 是空操作（instance 未绑定）
    expect(() => exposed(wrapper).refresh()).not.toThrow();
    await flushPromises();

    expect(wrapper.attributes('style') ?? '').not.toContain('display: none');
    expect(wrapper.find('.verifybox-top').exists()).toBe(false);

    // fixed 模式下 show() 是空操作
    exposed(wrapper).show();
    await flushPromises();
    expect(wrapper.attributes('style') ?? '').not.toContain('display: none');
  });
});
