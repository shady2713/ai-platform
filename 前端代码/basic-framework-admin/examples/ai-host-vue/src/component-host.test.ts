import { describe, expect, it, vi } from 'vitest';

import {
  buildComponentAttributes,
  COMPONENT_API_BASE,
  COMPONENT_ARTIFACT_PATH,
  COMPONENT_FONT_FAMILY,
  createComponentTicketProvider,
  loadComponentArtifact,
  switchComponentUser,
} from './component-host';
import { THEME_PRESETS, VUE_HOST_ROUTES } from './host';

/** 一次性假票据：运行时拼接，避免把看起来像真票据的字面量留在源码里。 */
const FAKE_TICKET = ['aitkt', 'vue', 'example'].join('_');

describe('vue 宿主的组件路径（X09）', () => {
  it('产物路径固定版本、基址是同源网关，主题用白名单字体', () => {
    expect(COMPONENT_ARTIFACT_PATH).toBe(
      '/component/ai-web-component-5.6.0.js',
    );
    expect(COMPONENT_ARTIFACT_PATH).not.toMatch(/https?:\/\//u);
    expect(COMPONENT_API_BASE.startsWith('/')).toBe(true);

    const attributes = buildComponentAttributes({
      appCode: 'crm-portal',
      instanceId: 'vue-inst-1',
      serviceId: 'svc_1',
    });
    expect(attributes['api-base-url']).toBe(COMPONENT_API_BASE);
    expect(attributes['app-code']).toBe('crm-portal');
    expect(attributes['service-id']).toBe('svc_1');
    // 与 iframe 路径共用同一张路由登记表
    expect(JSON.parse(attributes.routes ?? '')).toEqual(VUE_HOST_ROUTES);
    const theme = JSON.parse(attributes.theme ?? '') as {
      fontFamily: string;
      primaryColor: string;
      radius: number;
    };
    expect(theme.fontFamily).toBe(COMPONENT_FONT_FAMILY);
    expect(theme.primaryColor).toBe(THEME_PRESETS.light.primaryColor);
    expect(theme.radius).toBe(THEME_PRESETS.light.radius);
    // 凭据只经换票回调进入组件内存，绝不进属性
    for (const value of Object.values(attributes)) {
      expect(value).not.toMatch(/secret|token|ticket|Bearer/iu);
    }
  });

  it('未配置服务标识时不写 service-id 属性', () => {
    const attributes = buildComponentAttributes({
      appCode: 'crm-portal',
      instanceId: 'vue-inst-2',
    });
    expect('service-id' in attributes).toBe(false);
  });

  it('产物加载：形状不符即明确失败，正常模块返回注册函数', async () => {
    const define = vi.fn();
    await expect(
      loadComponentArtifact(async () => ({ version: '5.6.0' })),
    ).rejects.toThrow(/defineAiChatElement/u);

    const artifact = await loadComponentArtifact(async () => ({
      defineAiChatElement: define,
    }));
    artifact.defineAiChatElement();
    expect(define).toHaveBeenCalledTimes(1);
  });

  it('换票回调只走宿主后端，失败即抛错（不返回假票据）', async () => {
    const fetchImpl = vi.fn(async () => ({
      json: async () => ({
        expiresAt: '2026-09-27T10:00:00Z',
        token: FAKE_TICKET,
      }),
      ok: true,
      status: 200,
    })) as unknown as typeof fetch;
    const provider = createComponentTicketProvider(fetchImpl);
    await expect(provider()).resolves.toMatchObject({
      token: FAKE_TICKET,
    });
    expect(fetchImpl).toHaveBeenCalledWith('/your-backend/ai-ticket', {
      method: 'POST',
    });

    const failing = createComponentTicketProvider((async () => ({
      ok: false,
      status: 503,
    })) as unknown as typeof fetch);
    await expect(failing()).rejects.toThrow('ticket 503');
  });

  it('切用户走换代的 resetSession（不新建元素）', () => {
    const resetSession = vi.fn();
    switchComponentUser({ resetSession } as never);
    expect(resetSession).toHaveBeenCalledTimes(1);
  });
});
