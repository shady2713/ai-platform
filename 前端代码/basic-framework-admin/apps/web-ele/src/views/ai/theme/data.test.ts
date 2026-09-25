import { describe, expect, it } from 'vitest';

import {
  canPublish,
  layoutSummary,
  publicationText,
  publishActionText,
  tokenSummary,
  toTokensJson,
} from './data';

describe('主题管理页面逻辑（C09）', () => {
  it('发布状态文案与可发布判定：当前生效的修订不给发布按钮', () => {
    expect(publicationText('DRAFT')).toBe('草稿');
    expect(publicationText('PUBLISHED')).toBe('当前生效');
    expect(publicationText('SUPERSEDED')).toBe('已被取代');
    expect(publicationText('WEIRD')).toBe('未知状态');

    expect(canPublish('DRAFT')).toBe(true);
    expect(canPublish('SUPERSEDED')).toBe(true);
    expect(canPublish('PUBLISHED')).toBe(false);
  });

  it('已被取代的修订重新发布即回退（按钮文案不同）', () => {
    expect(publishActionText('DRAFT')).toBe('发布');
    expect(publishActionText('SUPERSEDED')).toBe('回退到此版本');
  });

  it('token 与布局摘要只做展示，非法 JSON 显示占位符', () => {
    expect(
      tokenSummary(
        '{"primaryColor":"#1677ff","radius":6,"colorScheme":"dark"}',
      ),
    ).toBe('主色 #1677ff · 圆角 6px · 深色');
    expect(tokenSummary('{}')).toBe('—');
    expect(tokenSummary('not-json')).toBe('—');
    expect(
      layoutSummary(
        '{"fontScale":"large","density":"compact","narrowBreakpoint":640}',
      ),
    ).toBe('字号 large · 密度 compact · 窄屏 640px');
    expect(layoutSummary('[]')).toBe('—');
  });

  it('表单转提交 JSON：键序固定、可选深浅色缺省不写空值', () => {
    expect(
      toTokensJson({
        colorScheme: 'dark',
        fontFamily: 'system-ui',
        primaryColor: '#16a34a',
        radius: 8,
      }),
    ).toBe(
      '{"primaryColor":"#16a34a","radius":8,"fontFamily":"system-ui","colorScheme":"dark"}',
    );
    expect(
      toTokensJson({
        fontFamily: 'system-ui',
        primaryColor: '#1677ff',
        radius: 6,
      }),
    ).toBe('{"primaryColor":"#1677ff","radius":6,"fontFamily":"system-ui"}');
  });
});
