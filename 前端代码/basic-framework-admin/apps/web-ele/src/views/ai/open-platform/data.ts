import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiOpenPlatformApi } from '#/api/ai/open-platform';

import { z } from '@vben/common-ui';

import { AI_OPEN_API_CATALOG } from '#/api/ai/open-platform';

/** 开放平台页面权限码（与 V65 迁移的菜单种子一致；仅用于菜单可见性） */
export const AI_OPEN_PLATFORM_PERMISSIONS = {
  query: 'ai:open-platform:query',
} as const;

/** 目录条目里的凭据占位符：示例永远用占位符，不出现真实 token 或 secret */
export const PLACEHOLDER_PATTERN = /<(?:APP_SECRET|TICKET)>/;

/**
 * 校验目录示例不含真实凭据：
 * 允许占位符（`<APP_SECRET>` / `<TICKET>`），不允许出现任何看起来像真实令牌的片段。
 */
export function exampleLeaksCredential(example: string | undefined): boolean {
  if (!example) {
    return false;
  }
  const withoutPlaceholders = example.replaceAll(
    /<(?:APP_SECRET|TICKET)>/g,
    '',
  );
  return /aitkt_|aiapp_|sk-|Bearer\s+[\w.-]{12,}/.test(withoutPlaceholders);
}

/** 目录分组（按能力域） */
export function catalogTags(): string[] {
  return [...new Set(AI_OPEN_API_CATALOG.map((endpoint) => endpoint.tag))];
}

/** 目录条目（可按能力域过滤） */
export function catalogEntries(tag?: string): AiOpenPlatformApi.Endpoint[] {
  return tag
    ? AI_OPEN_API_CATALOG.filter((endpoint) => endpoint.tag === tag)
    : AI_OPEN_API_CATALOG;
}

/** 目录统计：总数、异步受理数、SSE 数 */
export function catalogSummary(entries = AI_OPEN_API_CATALOG) {
  return {
    asynchronous: entries.filter((endpoint) => endpoint.asynchronous).length,
    sse: entries.filter((endpoint) => endpoint.sse).length,
    total: entries.length,
  };
}

/** 复制用示例文本：占位符保留，确保复制出去的示例不含真实凭据 */
export function exampleForCopy(endpoint: AiOpenPlatformApi.Endpoint): string {
  return [
    `${endpoint.method} ${endpoint.path}`,
    endpoint.requestExample ? `请求：${endpoint.requestExample}` : '',
    endpoint.responseExample ? `响应：${endpoint.responseExample}` : '',
  ]
    .filter((line) => line.length > 0)
    .join('\n');
}

/** 目录表格列 */
export function useCatalogColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'tag', title: '能力域', width: 100 },
    { field: 'method', title: '方法', width: 90 },
    { field: 'path', title: '路径', minWidth: 260, showOverflow: 'tooltip' },
    { field: 'summary', title: '说明', minWidth: 240 },
    {
      field: 'asynchronous',
      title: '形态',
      width: 120,
      formatter: ({ row }) => {
        const item = row as unknown as AiOpenPlatformApi.Endpoint;
        if (item.sse) {
          return 'SSE 事件流';
        }
        return item.asynchronous ? '异步受理' : '同步';
      },
    },
    {
      field: 'auth',
      title: '鉴权与归属',
      minWidth: 220,
      showOverflow: 'tooltip',
    },
  ];
}

/** 调试表单：票据来源与目标接口 */
export function useDebugSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: {
        maxlength: 64,
        placeholder: '请输入应用标识（仅用于换票）',
      },
      fieldName: 'appCode',
      label: '应用标识',
      rules: z
        .string()
        .min(1, '请输入应用标识')
        .max(64, '应用标识不能超过64个字符'),
    },
    {
      component: 'InputPassword',
      componentProps: {
        autocomplete: 'off',
        maxlength: 128,
        placeholder: '应用秘密（只用于换票，不回显、不落盘）',
      },
      fieldName: 'appSecret',
      label: '应用秘密',
      rules: z
        .string()
        .min(1, '请输入应用秘密')
        .max(128, '应用秘密不能超过128个字符'),
    },
    {
      component: 'Select',
      componentProps: {
        options: [
          { label: '用户主体', value: 'USER' },
          { label: '应用主体', value: 'APP' },
        ],
      },
      fieldName: 'subjectType',
      label: '测试主体',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: {
        maxlength: 64,
        placeholder: '外部用户标识（用户主体必填）',
      },
      fieldName: 'externalUserId',
      label: '主体标识',
    },
    {
      component: 'Select',
      componentProps: {
        options: AI_OPEN_API_CATALOG.map((endpoint) => ({
          label: `${endpoint.method} ${endpoint.path}`,
          value: endpoint.id,
        })),
        placeholder: '选择要调试的开放接口（仅已发布接口）',
      },
      fieldName: 'endpointId',
      label: '调试接口',
      rules: 'required',
    },
  ];
}

/** 调试前置说明：票据短期有效、只驻留内存、凭据不回显 */
export const DEBUG_NOTICE =
  '调试使用短期受限票据：票据只驻留当前页面内存，刷新即失效；应用秘密只在换票请求里发送，' +
  '不在响应区域回显，也不写入任何持久化存储。调试权限与真实身份一致——票据对应的主体没有的权限，调试也拿不到。';
