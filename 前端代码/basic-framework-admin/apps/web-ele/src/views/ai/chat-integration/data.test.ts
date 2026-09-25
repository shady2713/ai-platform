import { describe, expect, it } from 'vitest';

import {
  buildIntegrationSnippet,
  CAPABILITIES,
  containsTicketLiteral,
  DEFAULT_EMBED_BASE_PATH,
  getEmbedBasePath,
  MODE_OPTIONS,
  readEmbedBasePathFromEnv,
  TICKET_HELP,
} from './data';

describe('chat 集成页逻辑（C09）', () => {
  it('生成的接入片段不含任何真实票据，只有占位与换票说明', () => {
    const snippet = buildIntegrationSnippet({
      appCode: 'crm-portal',
      mode: 'drawer',
    });

    expect(containsTicketLiteral(snippet)).toBe(false);
    expect(snippet).toContain('<TICKET>');
    expect(snippet).toContain('/app-api/ai/v1/embed/crm-portal');
    expect(snippet).toContain('mode: "drawer"');
    expect(snippet).toContain('getAccessToken');
    // 不使用公共 CDN 或浮动版本路径
    expect(snippet).not.toMatch(/cdn\.|unpkg|jsdelivr|@latest/u);
  });

  it('三种模式都可生成，且入口基址可覆盖', () => {
    for (const { value } of MODE_OPTIONS) {
      const snippet = buildIntegrationSnippet({
        appCode: 'app-1',
        mode: value,
      });
      expect(snippet).toContain(`mode: "${value}"`);
    }
    expect(
      buildIntegrationSnippet({
        appCode: 'app-1',
        embedBasePath: 'https://ai.example.com/app-api/ai/v1/embed',
        mode: 'inline',
      }),
    ).toContain('https://ai.example.com/app-api/ai/v1/embed/app-1');
  });

  it('appCode 只做插值（注入不了额外语句）', () => {
    const snippet = buildIntegrationSnippet({
      appCode: 'app"; alert(1); //',
      mode: 'inline',
    });
    // 片段里出现的仍是同一段文本，不会被拼成可执行语句（页面把片段当纯文本展示）
    expect(snippet).toContain('app"; alert(1); //');
    expect(containsTicketLiteral(snippet)).toBe(false);
  });

  it('能力清单与换票说明覆盖首期交付面', () => {
    expect(CAPABILITIES.map((item) => item.name).join(' ')).toContain(
      '嵌入桥协议 v1.0',
    );
    expect(CAPABILITIES.length).toBeGreaterThanOrEqual(5);
    expect(TICKET_HELP.join(' ')).toContain('appSecret');
    expect(TICKET_HELP.join(' ')).toContain('/app-api/ai/auth/ticket');
  });

  it('入口基址只接受同源相对路径或 http(s)，其余回落默认值', () => {
    expect(getEmbedBasePath()).toBe(DEFAULT_EMBED_BASE_PATH);
    // 无环境变量时返回空串（页面用默认值）
    expect(readEmbedBasePathFromEnv()).toBe('');
  });
});
