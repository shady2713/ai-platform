import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 主题管理 API（C09）：管理端通道（`/admin-api`），权限码与 V79 菜单种子一致。
 *
 * 主题是控制面配置（应用级、按修订发布），因此走管理端会话；运行时的**有效主题**由
 * 应用端嵌入入口给出（C05 的 `/app-api/ai/v1/embed/{appCode}/bootstrap`），两者不混用。
 */
export namespace AiThemeApi {
  /** 主题修订（响应不含凭据；token 与布局都是已校验的规范化 JSON） */
  export interface Theme {
    applicationId: number;
    createTime?: Date;
    id: number;
    layoutJson: string;
    publicId: string;
    publicationState: string;
    publishedTime?: Date;
    revision: number;
    tokensFingerprint: string;
    tokensJson: string;
    version: number;
  }

  /** 有效主题（解析结果：平台默认或应用已发布修订） */
  export interface EffectiveTheme {
    applicationId: number;
    fingerprint: string;
    layoutJson: string;
    publicId?: string;
    revision?: number;
    source: string;
    tokensJson: string;
  }

  /** 新建修订请求 */
  export interface ThemeSaveReq {
    applicationId: number;
    layoutJson?: string;
    tokensJson: string;
  }

  /** 发布/回退请求（同一动作） */
  export interface ThemePublishReq {
    id: number;
    version: number;
  }
}

/** 分页查询主题修订 */
export function getThemePage(params: {
  applicationId?: number;
  pageNo?: number;
  pageSize?: number;
  publicationState?: string;
}): Promise<PageResult<AiThemeApi.Theme>> {
  return requestClient.get<PageResult<AiThemeApi.Theme>>('/ai/theme/page', {
    params,
  });
}

/** 查询单个主题修订 */
export function getTheme(id: number): Promise<AiThemeApi.Theme> {
  return requestClient.get<AiThemeApi.Theme>('/ai/theme/get', {
    params: { id },
  });
}

/** 新建主题修订（发布后不可修改，调整即新建修订） */
export function createTheme(data: AiThemeApi.ThemeSaveReq): Promise<number> {
  return requestClient.post<number>('/ai/theme/create', data);
}

/** 发布或回退（目标为历史修订即回退） */
export function publishTheme(
  data: AiThemeApi.ThemePublishReq,
): Promise<boolean> {
  return requestClient.post<boolean>('/ai/theme/publish', data);
}

/** 解析有效主题（平台默认 → 应用已发布修订） */
export function getEffectiveTheme(
  applicationId: number,
): Promise<AiThemeApi.EffectiveTheme> {
  return requestClient.get<AiThemeApi.EffectiveTheme>('/ai/theme/effective', {
    params: { applicationId },
  });
}

/** 允许的字体栈（自托管白名单；表单下拉直接用服务端给出的取值） */
export function getThemeFonts(): Promise<string[]> {
  return requestClient.get<string[]>('/ai/theme/fonts');
}

export type { PageParam };
