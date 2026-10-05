import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  buildIntegrationSnippet,
  CAPABILITIES,
  containsTicketLiteral,
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
    // 断言字面量而不是常量本身：原来的写法是
    // `expect(getEmbedBasePath()).toBe(DEFAULT_EMBED_BASE_PATH)`，
    // 而实现就是 `return DEFAULT_EMBED_BASE_PATH`——把常量跟它自己比，
    // 改成任何值测试照样通过。基址是拼进复制出去的 iframe src 的，值本身就该被钉住。
    expect(getEmbedBasePath()).toBe('/app-api/ai/v1/embed');
    // 无环境变量时返回空串（页面用默认值）
    expect(readEmbedBasePathFromEnv()).toBe('');
  });

  /**
   * 上面那条 `it` 的标题声称覆盖"只接受同源相对路径或 http(s)"，
   * 但 `readEmbedBasePathFromEnv` 的接受分支与全部拒绝分支此前零覆盖——
   * 而这个值会进入复制给用户的接入代码，正是它自己注释里警告的
   * "不能成为任意地址的来源"。这里按表驱动补齐。
   */
  describe('readEmbedBasePathFromEnv 的取值白名单', () => {
    afterEach(() => {
      vi.unstubAllEnvs();
    });

    const accepted: Array<[string, string]> = [
      ['/app-api/ai/v1/embed', '/app-api/ai/v1/embed'],
      ['/custom/prefix', '/custom/prefix'],
      ['  /trimmed/path  ', '/trimmed/path'],
      ['https://ai.example.com/embed', 'https://ai.example.com/embed'],
      ['http://ai.example.com/embed', 'http://ai.example.com/embed'],
    ];

    it.each(accepted)('接受 %j', (input, expected) => {
      vi.stubEnv('VITE_AI_EMBED_BASE_PATH', input);
      expect(readEmbedBasePathFromEnv()).toBe(expected);
    });

    // 全部回落为空串：页面随后用 DEFAULT_EMBED_BASE_PATH
    const rejected: Array<[string, string | undefined]> = [
      ['协议相对地址（可被改写成任意外域）', '//evil.example.com'],
      ['javascript: 伪协议', 'javascript:alert(1)'],
      ['data: 伪协议', 'data:text/html,<script>'],
      ['非 http(s) 的绝对地址', 'ftp://ai.example.com'],
      ['纯空白', '   '],
      ['空串', ''],
      ['未配置', undefined],
      ['普通裸词（既非路径也非 URL）', 'not a url'],
    ];

    it.each(rejected)('拒绝 %s', (_label, input) => {
      vi.stubEnv('VITE_AI_EMBED_BASE_PATH', input);
      expect(readEmbedBasePathFromEnv()).toBe('');
    });
  });
});
