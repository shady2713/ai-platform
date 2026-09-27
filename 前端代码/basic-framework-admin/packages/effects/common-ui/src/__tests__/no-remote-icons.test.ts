import type {
  CaptchaCheckApi,
  CaptchaGetApi,
  CaptchaHttpResponse,
} from '@vben/types';

import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { IconifyIcon, listIcons } from '@vben/icons';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import Verification from '../components/captcha/verification/index.vue';
import VerifyPoints from '../components/captcha/verification/verify-points.vue';
import VerifySlide from '../components/captcha/verification/verify-slide.vue';
import StatisticCard from '../components/card/statistic-card/statistic-card.vue';
import SummaryCard from '../components/card/summary-card/summary-card.vue';

/**
 * AT-067 禁公共 CDN：common-ui 的图标必须是**本地**的。
 *
 * 缺陷背景：captcha/卡片组件曾用字符串图标名（`lucide:x`、`lucide:refresh-ccw` 等）
 * 走 `@iconify/vue`，未本地注册时会在渲染期回退请求
 * `https://api.iconify.design/<prefix>.json?icons=...`（禁公网环境下的外发请求）。
 *
 * 本文件在任何组件模块加载前把全局 fetch 换成"记录并拒绝"的守卫（@iconify/vue
 * 在模块初始化时捕获 fetch，所以用 vi.hoisted 抢在静态 import 之前），然后：
 *  1. 无网络渲染 captcha/卡片，断言零外发请求且图标真的渲染出来了（本地图标）；
 *  2. 拒绝型用例：故意渲染一个未注册的字符串图标，断言守卫能检出
 *     api.iconify.design 请求 —— 否则"零请求"可能只是守卫坏了。
 */
vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

const network = vi.hoisted(() => {
  const requested: string[] = [];
  const fetchSpy = vi.fn(async (input: unknown) => {
    const url =
      typeof input === 'string'
        ? input
        : String((input as null | { url?: unknown })?.url ?? input);
    requested.push(url);
    throw new Error('offline test: 禁止任何网络请求');
  });
  vi.stubGlobal('fetch', fetchSpy);
  return { fetchSpy, requested };
});

const PUBLIC_ICON_HOSTS = [
  'api.iconify.design',
  'api.simplesvg.com',
  'api.unisvg.com',
];

function publicRequests(): string[] {
  return network.requested.filter((url) =>
    PUBLIC_ICON_HOSTS.some((host) => url.includes(host)),
  );
}

const challengeSlideResponse = (): CaptchaHttpResponse<{
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

const challengePointsResponse = (): CaptchaHttpResponse<{
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

function captchaProps(): {
  checkCaptchaApi: CaptchaCheckApi;
  getCaptchaApi: CaptchaGetApi;
} {
  return {
    checkCaptchaApi: vi.fn(async () => okCheckResponse) as CaptchaCheckApi,
    getCaptchaApi: vi.fn(async () =>
      challengeSlideResponse(),
    ) as unknown as CaptchaGetApi,
  };
}

async function settle() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

/** 模板里用全局 `$t`（脚本里用 @vben/locales 的 $t，已被 vi.mock 替换）。 */
const globalOptions = { mocks: { $t: (key: string) => key } };

describe('common-ui 图标本地化（禁公共 CDN）', () => {
  beforeEach(() => {
    network.requested.length = 0;
    network.fetchSpy.mockClear();
  });

  it('captcha 关闭/刷新图标本地渲染，且零外发请求', async () => {
    const verification = mount(Verification, {
      global: globalOptions,
      props: {
        ...captchaProps(),
        captchaType: 'blockPuzzle',
        mode: 'pop',
        type: '2',
      },
    });
    // 异步子组件（verify-slide）加载不阻塞关闭按钮的渲染
    await settle();
    expect(verification.find('.verifybox-close svg').exists()).toBe(true);

    const slide = mount(VerifySlide, {
      props: { ...captchaProps(), type: '2' },
    });
    await settle();
    expect(slide.find('.verify-refresh svg').exists()).toBe(true);

    const points = mount(VerifyPoints, {
      props: {
        checkCaptchaApi: vi.fn(async () => okCheckResponse) as CaptchaCheckApi,
        getCaptchaApi: vi.fn(async () =>
          challengePointsResponse(),
        ) as unknown as CaptchaGetApi,
      },
    });
    await settle();
    expect(points.find('.verify-refresh svg').exists()).toBe(true);

    expect(network.requested).toEqual([]);
    expect(publicRequests()).toEqual([]);

    verification.unmount();
    slide.unmount();
    points.unmount();
  });

  it('统计卡片图标本地渲染，且零外发请求', async () => {
    const withIcon = mount(SummaryCard, {
      props: {
        iconBgColor: 'bg-primary/10',
        iconColor: 'text-primary',
        percent: 5,
        title: 'up',
        tooltip: 'tip',
        value: 1,
      },
    });
    const withDown = mount(SummaryCard, {
      props: { percent: -5, title: 'down', tooltip: 'tip', value: 1 },
    });
    const statisticUp = mount(StatisticCard, {
      props: { percent: 5, title: 'up', tooltip: 'tip', value: 1 },
    });
    const statisticDown = mount(StatisticCard, {
      props: { percent: -5, title: 'down', tooltip: 'tip', value: 1 },
    });

    await settle();

    // tooltip 提示 + 涨跌箭头都渲染成了本地 <svg>（未注册时会渲染不出来）
    for (const wrapper of [withIcon, withDown, statisticUp, statisticDown]) {
      expect(wrapper.findAll('svg').length).toBeGreaterThanOrEqual(2);
    }
    // 涨/跌用不同图标（chevron-up/down、trending-up/down），不是同一个
    expect(withIcon.html()).not.toBe(withDown.html());
    expect(statisticUp.html()).not.toBe(statisticDown.html());

    expect(network.requested).toEqual([]);
    expect(publicRequests()).toEqual([]);

    withIcon.unmount();
    withDown.unmount();
    statisticUp.unmount();
    statisticDown.unmount();
  });

  it('@vben/icons 的 lucide 离线注册表包含这些图标（不依赖 API 回退）', () => {
    expect(listIcons('', 'lucide')).toEqual(
      expect.arrayContaining([
        'lucide:chevron-up',
        'lucide:refresh-ccw',
        'lucide:trending-down',
        'lucide:trending-up',
      ]),
    );
  });

  it('拒绝型用例：未本地注册的字符串图标会请求 api.iconify.design，守卫必须检出', async () => {
    const UnregisteredProbe = defineComponent({
      name: 'UnregisteredProbe',
      render: () => h(IconifyIcon, { icon: 'lucide:q06-unregistered-probe' }),
    });

    const wrapper = mount(UnregisteredProbe);
    // @iconify/vue 通过 setTimeout(0) 排队外发请求
    await vi.waitFor(() => {
      expect(network.requested.length).toBeGreaterThan(0);
    });

    expect(publicRequests().length).toBeGreaterThan(0);
    expect(
      network.requested.some((url) =>
        url.startsWith('https://api.iconify.design/lucide.json'),
      ),
    ).toBe(true);

    wrapper.unmount();
  });
});
