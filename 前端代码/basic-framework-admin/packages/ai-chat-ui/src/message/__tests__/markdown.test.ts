import { describe, expect, it } from 'vitest';

import {
  isAllowedLinkUrl,
  markdownText,
  MAX_INLINE_DEPTH,
  parseMarkdown,
  parseMarkdownInline,
} from '../markdown';

describe('受控 Markdown 解析', () => {
  it('识别标题/段落/列表/引用/围栏代码块', () => {
    const nodes = parseMarkdown(
      [
        '# 标题一',
        '',
        '第一段文字',
        '',
        '- 甲',
        '- 乙',
        '',
        '1. 一',
        '',
        '> 引用内容',
        '',
        '```json',
        '{"a": 1}',
        '```',
      ].join('\n'),
    );

    expect(nodes.map((node) => node.type)).toStrictEqual([
      'heading',
      'paragraph',
      'list',
      'list',
      'blockquote',
      'codeBlock',
    ]);
    const heading = nodes[0];
    expect(heading?.type === 'heading' && heading.level).toBe(1);
    const unordered = nodes[2];
    expect(unordered?.type === 'list' && unordered.ordered).toBe(false);
    const ordered = nodes[3];
    expect(ordered?.type === 'list' && ordered.items).toHaveLength(1);
    const code = nodes[5];
    expect(code?.type === 'codeBlock' && code.language).toBe('json');
    expect(code?.type === 'codeBlock' && code.code).toBe('{"a": 1}');
  });

  it('把看起来像 HTML 的内容当作纯文本，不产生任何节点', () => {
    const payload = '<script>alert(1)</script><img src=x onerror=alert(2)>';
    const nodes = parseMarkdown(payload);

    const paragraph = nodes[0];
    expect(paragraph?.type).toBe('paragraph');
    expect(
      paragraph?.type === 'paragraph'
        ? paragraph.children.map((child) => child.type)
        : [],
    ).toStrictEqual(['text']);
    expect(
      paragraph?.type === 'paragraph' ? markdownText(paragraph.children) : '',
    ).toBe(payload);
  });

  it('围栏代码块内部不解析标记（# 与 * 保持原样）', () => {
    const nodes = parseMarkdown('```\n# 不是标题\n- 不是列表\n```');
    const code = nodes[0];
    expect(code?.type).toBe('codeBlock');
    expect(code?.type === 'codeBlock' ? code.code : '').toBe(
      '# 不是标题\n- 不是列表',
    );
  });

  it('强调/加粗/行内代码/链接各自成节点', () => {
    const nodes = parseMarkdownInline(
      '前 **粗** 中 *斜* `代码` [站点](https://docs.example.com/a) 后',
    );
    expect(nodes.map((node) => node.type)).toStrictEqual([
      'text',
      'strong',
      'text',
      'emphasis',
      'text',
      'code',
      'text',
      'link',
      'text',
    ]);
    expect(markdownText(nodes)).toBe('前 粗 中 斜 代码 站点 后');
  });

  it('危险协议与相对地址都不是链接，只保留标签文本', () => {
    for (const href of [
      'javascript:alert(1)',
      'JaVaScRiPt:alert(1)',
      'data:text/html,<script>alert(1)</script>',
      'vbscript:msgbox(1)',
      'file:///etc/passwd',
      '//evil.example.com/x',
      '/relative/path',
      String.raw`http://a.example.com/\@b.example.com/`,
      `https://docs.example.com/${'x'.repeat(3000)}`,
    ]) {
      const nodes = parseMarkdownInline(`[标签](${href})`);
      expect(nodes.some((node) => node.type === 'link')).toBe(false);
      expect(markdownText(nodes)).toBe('标签');
    }
  });

  it('配置 Origin 白名单后只接受白名单链接', () => {
    const withAllowlist = parseMarkdownInline(
      '[内](https://docs.example.com/a) [外](https://evil.example.com/b)',
      ['https://docs.example.com'],
    );
    const links = withAllowlist.filter((node) => node.type === 'link');
    expect(links).toHaveLength(1);
    expect(links[0]?.type === 'link' ? links[0].href : '').toBe(
      'https://docs.example.com/a',
    );
  });

  it('未配置白名单时接受任意 http/https 绝对地址', () => {
    expect(isAllowedLinkUrl('https://docs.example.com/a')).toBe(true);
    expect(isAllowedLinkUrl('http://10.0.0.1:8080/a?b=1#c')).toBe(true);
    expect(isAllowedLinkUrl('')).toBe(false);
    expect(isAllowedLinkUrl('not a url')).toBe(false);
  });

  it('嵌套深度封顶后不再展开（防止深层嵌套放大）', () => {
    const deep = `${'**'.repeat(60)}x${'**'.repeat(60)}`;
    const nodes = parseMarkdownInline(deep);
    // 不抛异常、不无限递归，最终仍能取到文本
    expect(typeof markdownText(nodes)).toBe('string');
    expect(MAX_INLINE_DEPTH).toBe(8);
  });

  it('空输入与非字符串输入都返回空结果，不抛异常', () => {
    expect(parseMarkdown('')).toStrictEqual([]);
    expect(parseMarkdown(undefined as unknown as string)).toStrictEqual([]);
    expect(parseMarkdownInline('')).toStrictEqual([]);
  });

  it('超长文本按上限截断而不是报错', () => {
    const long = 'a'.repeat(30_000);
    const nodes = parseMarkdownInline(long);
    expect(markdownText(nodes)).toHaveLength(20_000);
  });

  it('行数上限生效（超出部分不处理）', () => {
    const nodes = parseMarkdown('甲\n乙\n丙', { maxLines: 2 });
    // 连续行合成一个段落；上限外的"丙"不进入结果
    expect(nodes).toHaveLength(1);
    expect(
      nodes[0]?.type === 'paragraph' ? markdownText(nodes[0].children) : '',
    ).toBe('甲 乙');
  });

  it('未闭合围栏代码块按到文末处理', () => {
    const nodes = parseMarkdown('```\n未闭合');
    expect(nodes).toHaveLength(1);
    expect(nodes[0]?.type === 'codeBlock' ? nodes[0].code : '').toBe('未闭合');
  });
});
