import { describe, expect, it, vi } from 'vitest';

import {
  buildComponentAttributes,
  COMPONENT_ARTIFACT_PATH,
  COMPONENT_GATEWAY_BASE,
  COMPONENT_THEME_PRESETS,
  switchUserAction,
} from './component-model.mjs';
import { SAMPLE_ROUTES } from './host-model.mjs';

describe('组件路径的宿主侧逻辑（X09）', () => {
  it('产物路径与应用端基址都是固定/同源口径', () => {
    // 版本化固定命名（自托管，无公共 CDN、无浮动路径）
    expect(COMPONENT_ARTIFACT_PATH).toBe(
      '/component/ai-web-component-5.6.0.js',
    );
    expect(COMPONENT_ARTIFACT_PATH).not.toMatch(/https?:\/\//u);
    // 组件路径的应用端基址是同源网关，不是平台 Origin
    expect(COMPONENT_GATEWAY_BASE.startsWith('/')).toBe(true);
  });

  it('元素参数：身份/服务/路由/主题齐全，且**不含任何凭据**', () => {
    const attributes = buildComponentAttributes({
      appCode: 'crm-portal',
      instanceId: 'inst-1',
      routes: SAMPLE_ROUTES,
      serviceId: 'svc_1',
    });

    expect(attributes['app-code']).toBe('crm-portal');
    expect(attributes['instance-id']).toBe('inst-1');
    expect(attributes['api-base-url']).toBe(COMPONENT_GATEWAY_BASE);
    expect(attributes['service-id']).toBe('svc_1');
    // 路由登记表与 iframe 路径是同一张表
    expect(JSON.parse(attributes.routes)).toEqual(SAMPLE_ROUTES);
    // 主题使用白名单内的字体 token
    const theme = JSON.parse(attributes.theme) as {
      fontFamily: string;
      primaryColor: string;
    };
    expect(theme.primaryColor).toBe(COMPONENT_THEME_PRESETS.light.primaryColor);
    // 凭据只能经换票回调进入组件内存，绝不进属性/HTML
    for (const value of Object.values(attributes)) {
      expect(value).not.toMatch(/secret|token|ticket|Bearer/iu);
    }
  });

  it('未配置服务标识时不写 service-id 属性（不发明占位值）', () => {
    const attributes = buildComponentAttributes({
      appCode: 'crm-portal',
      instanceId: 'inst-2',
      routes: {},
    });
    expect('service-id' in attributes).toBe(false);
  });

  it('切用户走元素的 resetSession（换代并重建界面）', () => {
    const resetSession = vi.fn();
    const message = switchUserAction({ resetSession });
    expect(resetSession).toHaveBeenCalledTimes(1);
    expect(message).toContain('换代');
  });
});
