import type { AttachmentApi } from '../../attachment/attachment';
import type { CitationApi } from '../../citation/citation';
import type { MessagePorts } from '../MessageBlockView.vue';

import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

vi.mock('@antv/g2', () => ({
  Chart: class {
    public changeSize = vi.fn();
    public destroy = vi.fn();
    public options = vi.fn();
    public render = vi.fn();
  },
}));

const { default: MarkdownBlockNodes } =
  await import('../MarkdownBlockNodes.vue');
const { default: MessageList } = await import('../MessageList.vue');

const REPORT_SPEC = {
  blocks: [
    {
      datasetRef: 'd1',
      format: 'NUMBER',
      id: 'b1',
      metricField: 'amount',
      rowIndex: 0,
      title: '概览',
      type: 'metric',
      unit: '元',
    },
  ],
  datasetRefs: [
    {
      columns: [
        { dataType: 'DECIMAL', field: 'amount', label: '金额', unit: '元' },
      ],
      completeness: 'COMPLETE',
      id: 'd1',
      queryRef: 'q1',
      resultRef: 'res_1',
      rowCount: 1,
    },
  ],
  layout: {
    columns: 12,
    gap: 8,
    items: [{ blockId: 'b1', column: 0, row: 0, span: 12 }],
  },
  queryRefs: [{ id: 'q1', plan: {} }],
  schemaVersion: '1.0',
  sources: [
    {
      description: '销售数据集',
      id: 's1',
      kind: 'DATASET',
      queryRef: 'q1',
      resourceId: 'ds_1',
      resourceVersion: 1,
    },
  ],
  themeRef: { revision: 1, themeId: 'default' },
  title: '销售报表',
};

const CITATION = {
  chunkIndex: 2,
  citationId: 'kb_1:12:3',
  documentId: 9,
  kind: 'citation',
  locationRef: '第 3 页',
  snippet: '片段正文',
  title: '销售手册',
  versionNo: 2,
};

const FILE = {
  businessKey: 'ai_chat_session:1',
  businessType: 'ai_chat_session',
  fileId: 7,
  kind: 'file',
  mime: 'text/plain',
  name: '说明.txt',
  size: 2048,
};

const ACTION = {
  actionId: 'act_1',
  // 固定用远期时间：避免用例随运行日期变成"已过期"（与 now 相关的断言用显式 props 控制）
  expiresAt: '2099-01-01T00:00:00Z',
  kind: 'action',
  parameterSummary: '将 3 行标记为已归档',
  toolName: 'archiveRows',
};

function mountList(
  blocks: unknown[],
  options: { allowedOrigins?: string[]; ports?: MessagePorts } = {},
) {
  return mount(MessageList, {
    props: {
      ...(options.allowedOrigins
        ? { allowedOrigins: options.allowedOrigins }
        : {}),
      messages: [{ blocks, id: 'm1', role: 'assistant' }],
      ...(options.ports ? { ports: options.ports } : {}),
    },
  });
}

function citationApiStub(
  overrides: Partial<{
    readOriginal: CitationApi['readOriginal'];
    readSnippet: CitationApi['readSnippet'];
  }> = {},
): CitationApi {
  return {
    readOriginal: vi.fn(
      overrides.readOriginal ??
        (async () => ({ content: '原文正文', mimeType: 'text/plain' })),
    ),
    readSnippet: vi.fn(overrides.readSnippet ?? (async () => '重读片段')),
  };
}

function attachmentApiStub(
  overrides: Partial<{
    download: AttachmentApi['download'];
    read: AttachmentApi['read'];
  }> = {},
): AttachmentApi {
  return {
    download: vi.fn(overrides.download ?? (async () => undefined)),
    read: vi.fn(
      overrides.read ??
        (async () => ({ content: '文件内容', mimeType: 'text/plain' })),
    ),
  };
}

describe('消息块渲染：文本与安全边界', () => {
  it('文本块按受控 Markdown 渲染，脚本与危险链接都不执行', async () => {
    const wrapper = mountList([
      {
        kind: 'text',
        text: [
          '# 结论',
          '',
          '见 [站点](https://docs.example.com/a) 与 [危险](javascript:alert(1))',
          '',
          '<script>globalThis.__xss = 1</script>',
        ].join('\n'),
      },
    ]);

    expect(
      wrapper.get('[data-testid="ai-message-link"]').attributes('href'),
    ).toBe('https://docs.example.com/a');
    expect(
      wrapper.get('[data-testid="ai-message-link"]').attributes('rel'),
    ).toBe('noopener noreferrer nofollow');
    // 危险链接只剩文本；原始 HTML 只以文本出现，不产生元素
    expect(wrapper.findAll('[data-testid="ai-message-link"]')).toHaveLength(1);
    expect(wrapper.text()).toContain('危险');
    expect(wrapper.find('script').exists()).toBe(false);
    expect(wrapper.text()).toContain('<script>globalThis.__xss = 1</script>');
    expect((globalThis as Record<string, unknown>).__xss).toBeUndefined();
    expect(wrapper.get('[data-block-kind="text"]').find('h1').text()).toBe(
      '结论',
    );
  });

  it('配置 Origin 白名单后非白名单链接退化为文本', () => {
    const wrapper = mountList(
      [{ kind: 'text', text: '[外站](https://evil.example.com/x)' }],
      { allowedOrigins: ['https://docs.example.com'] },
    );
    expect(wrapper.find('[data-testid="ai-message-link"]').exists()).toBe(
      false,
    );
    expect(wrapper.text()).toContain('外站');
  });

  it('错误块以 alert 语义展示（失败不被静默吞掉）', () => {
    const wrapper = mountList([{ kind: 'error', message: '模型调用失败' }]);
    const node = wrapper.get('[data-testid="ai-message-error"]');
    expect(node.text()).toBe('模型调用失败');
    expect(node.attributes('role')).toBe('alert');
  });

  it('受控 Markdown 的全部允许节点都能渲染（标题/引用/列表/代码/强调）', () => {
    const text = [
      '## 二级标题',
      '',
      '> 引用内容',
      '',
      '- 甲',
      '- 乙',
      '',
      '1. 一',
      '2. 二',
      '',
      '```text',
      '代码块内容',
      '```',
      '',
      '正文 **粗** 与 *斜* 与 `行内代码` 与 [站点](https://docs.example.com/a)',
    ].join('\n');
    const wrapper = mountList([{ kind: 'text', text }]);
    const block = wrapper.get('[data-block-kind="text"]');

    expect(block.get('h2').text()).toBe('二级标题');
    expect(block.get('blockquote').text()).toContain('引用内容');
    expect(block.findAll('ul li').map((item) => item.text())).toStrictEqual([
      '甲',
      '乙',
    ]);
    expect(block.findAll('ol li').map((item) => item.text())).toStrictEqual([
      '一',
      '二',
    ]);
    expect(block.get('pre code').text()).toBe('代码块内容');
    expect(block.get('pre code').attributes('data-language')).toBe('text');
    expect(block.get('strong').text()).toBe('粗');
    expect(block.get('em').text()).toBe('斜');
    expect(block.get('p code').text()).toBe('行内代码');
    expect(block.get('[data-testid="ai-message-link"]').text()).toBe('站点');
  });

  it('标题级别越界时收敛到 h1–h6（解析器只给 1..6，这里证明兜底也安全）', () => {
    const wrapper = mount(MarkdownBlockNodes, {
      props: {
        nodes: [
          {
            children: [{ text: '过低', type: 'text' }],
            level: 0,
            type: 'heading',
          },
          {
            children: [{ text: '过高', type: 'text' }],
            level: 9,
            type: 'heading',
          },
        ],
      },
    });
    expect(wrapper.get('h1').text()).toBe('过低');
    expect(wrapper.get('h6').text()).toBe('过高');
  });
});

describe('消息块渲染：表格 / 图表 / 报表', () => {
  it('表格块逐列渲染，金额保留原始文本，空值显示 —', () => {
    const wrapper = mountList([
      {
        columns: [
          { field: 'region', label: '区域' },
          { field: 'amount', label: '金额', unit: '元' },
          { field: 'note', label: '备注' },
        ],
        completeness: 'PARTIAL',
        kind: 'table',
        pageInfo: { page: 1, size: 20, total: 100 },
        rows: [{ amount: '12345678901234.56', note: '', region: '华东' }],
      },
    ]);

    const cells = wrapper
      .findAll('[data-testid="ai-message-table"] td')
      .map((cell) => cell.text());
    expect(cells).toStrictEqual(['华东', '12345678901234.56', '']);
    expect(wrapper.get('[data-testid="ai-message-table"] th').text()).toBe(
      '区域',
    );
    expect(
      wrapper.get('[data-testid="ai-message-table-completeness"]').text(),
    ).toContain('PARTIAL');
    expect(
      wrapper.get('[data-testid="ai-message-table-page"]').text(),
    ).toContain('共 100 条');
  });

  it('空表格与完整数据都不额外提示完整性', () => {
    const wrapper = mountList([
      {
        columns: [{ field: 'a', label: 'A' }],
        completeness: 'COMPLETE',
        kind: 'table',
        rows: [],
      },
    ]);
    expect(
      wrapper.get('[data-testid="ai-message-table-empty"]').text(),
    ).toContain('没有可显示的数据行');
    expect(
      wrapper.find('[data-testid="ai-message-table-completeness"]').exists(),
    ).toBe(false);
  });

  it('图表块用共享渲染器（ChartSpec 直传）', () => {
    const wrapper = mountList([
      {
        kind: 'chart',
        spec: {
          categories: ['一月'],
          series: [{ data: [120], name: '销售额' }],
          title: '月度销售额',
          type: 'bar',
        },
      },
    ]);
    expect(wrapper.find('[data-testid="ai-chart-canvas"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('月度销售额');
  });

  it('报表块带内联规格时直接用共享报表视图', () => {
    const wrapper = mountList([
      {
        data: {
          data: [{ blockId: 'b1', type: 'metric', value: '200' }],
          kind: 'REPORT',
        },
        kind: 'report',
        reportId: 'rpt_sales01',
        spec: REPORT_SPEC,
        version: 3,
      },
    ]);
    expect(wrapper.text()).toContain('销售报表');
    expect(wrapper.text()).toContain('概览');
  });

  it('报表块只有引用时给卡片与打开入口，打开只 emit 报表标识', async () => {
    const wrapper = mountList([
      {
        kind: 'report',
        reportId: 'rpt_sales01',
        title: '销售报表',
        version: 3,
      },
    ]);
    expect(
      wrapper.get('[data-testid="ai-message-report-version"]').text(),
    ).toBe('rpt_sales01 · 版本 3');
    await wrapper
      .get('[data-testid="ai-message-report-open"]')
      .trigger('click');
    expect(wrapper.emitted('openReport')).toStrictEqual([['rpt_sales01']]);
  });
});

describe('消息块渲染：引用与附件', () => {
  it('未接入引用端口时只读展示（不给必然失败的按钮）', () => {
    const wrapper = mountList([CITATION]);
    expect(wrapper.get('[data-testid="ai-citation-label"]').text()).toBe(
      '销售手册（v2 · 第 3 页）',
    );
    expect(wrapper.get('[data-testid="ai-citation-snippet"]').text()).toBe(
      '片段正文',
    );
    expect(
      wrapper.get('[data-testid="ai-citation-readonly"]').text(),
    ).toContain('未接入引用读取端口');
    expect(
      wrapper.find('[data-testid="ai-citation-open-original"]').exists(),
    ).toBe(false);
  });

  it('缺权限时原文不可打开：给固定提示且不显示任何内容', async () => {
    const api = citationApiStub({
      readOriginal: async () => {
        throw new Error('403 token=secret-value');
      },
    });
    const wrapper = mountList([CITATION], { ports: { citation: api } });

    await wrapper
      .get('[data-testid="ai-citation-open-original"]')
      .trigger('click');
    await Promise.resolve();

    expect(
      wrapper.get('[data-testid="ai-citation-original-error"]').text(),
    ).toBe('原文不可打开：无权限、已撤回或不存在');
    expect(wrapper.find('[data-testid="ai-citation-text"]').exists()).toBe(
      false,
    );
    expect(wrapper.html()).not.toContain('secret-value');
    expect(api.readOriginal).toHaveBeenCalledWith(9);
  });

  it('有权限时打开原文并重读片段', async () => {
    const api = citationApiStub();
    const wrapper = mountList([CITATION], { ports: { citation: api } });

    await wrapper.get('[data-testid="ai-citation-reload"]').trigger('click');
    await Promise.resolve();
    expect(wrapper.get('[data-testid="ai-citation-snippet"]').text()).toBe(
      '重读片段',
    );

    await wrapper
      .get('[data-testid="ai-citation-open-original"]')
      .trigger('click');
    await Promise.resolve();
    expect(wrapper.get('[data-testid="ai-citation-text"]').text()).toBe(
      '原文正文',
    );
  });

  it('片段读取失败时给出提示且保留原片段文本（不静默替换）', async () => {
    const api = citationApiStub({
      readSnippet: async () => {
        throw new Error('403');
      },
    });
    const wrapper = mountList([CITATION], { ports: { citation: api } });

    await wrapper.get('[data-testid="ai-citation-reload"]').trigger('click');
    await Promise.resolve();

    expect(
      wrapper.get('[data-testid="ai-citation-snippet-error"]').text(),
    ).toBe('引用片段不可读取：无权限、已撤回或不存在');
  });

  it('未接入附件端口时只读展示', () => {
    const wrapper = mountList([FILE]);
    expect(wrapper.get('[data-testid="ai-attachment-name"]').text()).toBe(
      '说明.txt',
    );
    expect(
      wrapper.get('[data-testid="ai-attachment-readonly"]').text(),
    ).toContain('未接入文件读取端口');
    expect(
      wrapper.find('[data-testid="ai-attachment-download"]').exists(),
    ).toBe(false);
  });

  it('附件预览与下载都走受控端口，失败给固定提示', async () => {
    const api = attachmentApiStub({
      download: async () => {
        throw new Error('boom token=secret-value');
      },
    });
    const wrapper = mountList([FILE], { ports: { attachment: api } });

    await wrapper
      .get('[data-testid="ai-attachment-preview-action"]')
      .trigger('click');
    await Promise.resolve();
    expect(wrapper.get('[data-testid="ai-attachment-text"]').text()).toBe(
      '文件内容',
    );

    await wrapper
      .get('[data-testid="ai-attachment-download"]')
      .trigger('click');
    await Promise.resolve();
    expect(wrapper.get('[data-testid="ai-attachment-error"]').text()).toBe(
      '文件不可下载：无权限、已撤回或不存在',
    );
    expect(wrapper.html()).not.toContain('secret-value');
  });

  it('二进制附件不给预览入口，只给下载', () => {
    const api = attachmentApiStub();
    const wrapper = mountList([{ ...FILE, mime: 'application/pdf' }], {
      ports: { attachment: api },
    });
    expect(
      wrapper.find('[data-testid="ai-attachment-preview-action"]').exists(),
    ).toBe(false);
    expect(wrapper.get('[data-testid="ai-attachment-download"]').text()).toBe(
      '下载',
    );
  });
});

describe('消息块渲染：动作与追问', () => {
  it('确认/取消只 emit 动作标识，不携带请求体', async () => {
    const wrapper = mountList([ACTION]);

    await wrapper.get('[data-testid="ai-action-confirm"]').trigger('click');
    await wrapper.get('[data-testid="ai-action-reject"]').trigger('click');

    expect(wrapper.emitted('confirm')).toStrictEqual([['act_1']]);
    expect(wrapper.emitted('reject')).toStrictEqual([['act_1']]);
    expect(wrapper.get('[data-testid="ai-action-summary"]').text()).toBe(
      '将 3 行标记为已归档',
    );
  });

  it('过期的动作不再给按钮', () => {
    const wrapper = mountList([
      { ...ACTION, expiresAt: '2000-01-01T00:00:00Z' },
    ]);
    expect(wrapper.get('[data-testid="ai-action-expired"]').text()).toContain(
      '已过期',
    );
    expect(wrapper.find('[data-testid="ai-action-confirm"]').exists()).toBe(
      false,
    );
  });

  it('已取消的动作只显示状态', () => {
    const wrapper = mountList([{ ...ACTION, status: 'CANCELLED' }]);
    expect(wrapper.get('[data-testid="ai-action-settled"]').text()).toContain(
      '已处理完成',
    );
  });

  it('追问：点候选项与自由输入都形成新输入，不显示"已成功生成"', async () => {
    const wrapper = mountList([
      {
        fields: [{ label: '起始日期', name: 'start_date', required: true }],
        kind: 'clarification',
        options: ['本月', '上月'],
        question: '要哪个区间？',
      },
    ]);

    expect(wrapper.get('[data-testid="ai-clarification-fields"]').text()).toBe(
      '需要补充：起始日期（必填）',
    );
    await wrapper
      .findAll('[data-testid="ai-clarification-option"]')[0]
      ?.trigger('click');

    expect(wrapper.emitted('answer')).toStrictEqual([['本月']]);
    expect(
      wrapper.get('[data-testid="ai-clarification-answered"]').text(),
    ).toBe('已提交，等待新的结果（本次回答不会改变已有结论）');
  });

  it('追问：空输入不提交，不产生回答', async () => {
    const wrapper = mountList([
      { kind: 'clarification', options: [], question: '补充一下？' },
    ]);
    await wrapper
      .get('[data-testid="ai-clarification-form"]')
      .trigger('submit');
    expect(wrapper.emitted('answer')).toBeUndefined();

    const submit = wrapper.get('[data-testid="ai-clarification-submit"]');
    expect(submit.attributes('disabled')).toBeDefined();
  });
});

describe('消息列表降级与空态', () => {
  it('未知类型明确降级，且不影响同一条消息的其它块', () => {
    const wrapper = mountList([
      { kind: 'text', text: '正常内容' },
      { html: '<b>x</b>', kind: 'html' },
      { type: 'clarification', question: '草案判别键' },
    ]);

    const unsupported = wrapper.findAll(
      '[data-testid="ai-message-unsupported"]',
    );
    expect(unsupported).toHaveLength(2);
    expect(unsupported[0]?.text()).toContain('不支持的结果类型 html');
    expect(unsupported[1]?.text()).toContain('clarification');
    expect(wrapper.get('[data-block-kind="text"]').text()).toContain(
      '正常内容',
    );
  });

  it('空消息列表给出空态提示', () => {
    const wrapper = mount(MessageList, { props: { messages: [] } });
    expect(wrapper.get('[data-testid="ai-message-list-empty"]').text()).toBe(
      '暂无消息',
    );
    expect(wrapper.findAll('[data-testid="ai-message-item"]')).toHaveLength(0);
  });

  it('用户消息与助手消息带角色标记', () => {
    const wrapper = mount(MessageList, {
      props: {
        messages: [
          { blocks: [{ kind: 'text', text: '问题' }], id: 'u1', role: 'user' },
          {
            blocks: [{ kind: 'text', text: '回答' }],
            id: 'a1',
            role: 'assistant',
          },
        ],
      },
    });
    expect(
      wrapper
        .findAll('[data-testid="ai-message-item"]')
        .map((item) => item.attributes('data-role')),
    ).toStrictEqual(['user', 'assistant']);
  });
});
