/**
 * 引用读取（C03）：引用的**受控打开**。打开动作只带服务端签发的标识，不带 URL。
 *
 * <p>三条不可让步的规则（对应卡片验收）：
 * <ol>
 *   <li><b>引用不可编造</b>：`citationId` 只能来自服务端（K06 的检索候选），本层不做任何拼装；
 *       没有 `documentId` 时"打开原文"入口**不出现**，而不是给一个必然失败的按钮（AT-026）；</li>
 *   <li><b>打开即再鉴权</b>：片段与原文都走宿主注入的端口（服务端按当前 ACL 判定，无权限与不存在同语义），
 *       前端不缓存"曾经可读"的结论——失权后再次打开必须失败（AT-048）；</li>
 *   <li><b>不回显原始错误文本</b>：宿主端口抛出的异常里可能带请求 URL 与票据参数，
 *       界面只输出固定提示，绝不把 `error.message` 渲染出去。</li>
 * </ol>
 */

import type { CitationBlock } from '../message/blocks';

/** 引用受控读取端口（宿主实现：浏览器端用当前票据请求应用端引用接口，票据只在请求头）。 */
export interface CitationApi {
  /** 读取文档原文（服务端按当前权限重取，失败即 reject）。 */
  readOriginal(documentId: number): Promise<CitationContent>;
  /** 重新读取引用片段（服务端按当前权限重取，失败即 reject）。 */
  readSnippet(citationId: string): Promise<string>;
}

/** 受控读取返回的内容。 */
export interface CitationContent {
  content: string;
  mimeType: string;
}

/** 打开失败（原因只给固定提示）。 */
export interface CitationOpenFailure {
  message: string;
  ok: false;
}

/** 打开成功（内容来自服务端本次响应）。 */
export interface CitationOpenOk {
  content: string;
  mimeType: string;
  ok: true;
}

/** 打开结果。 */
export type CitationOpenOutcome = CitationOpenFailure | CitationOpenOk;

/** 打开动作（提示文案按动作区分，但都不含服务端原文）。 */
export type CitationOpenAction = 'original' | 'snippet';

const OPEN_FAILED: Record<CitationOpenAction, string> = {
  original: '原文不可打开：无权限、已撤回或不存在',
  snippet: '引用片段不可读取：无权限、已撤回或不存在',
};

/** 打开失败的固定提示（界面只输出它，绝不回显宿主异常文本）。 */
export function citationFailureMessage(action: CitationOpenAction): string {
  return OPEN_FAILED[action];
}

/** 是否具备"打开原文"的条件：服务端给过文档编号才渲染入口。 */
export function canOpenOriginal(citation: CitationBlock): boolean {
  return typeof citation.documentId === 'number' && citation.documentId > 0;
}

/** 引用标签：标题 + 版本 + 位置（位置缺失时只显示标题与版本）。 */
export function citationLabel(citation: CitationBlock): string {
  const location = citation.locationRef.trim();
  const suffix = location.length === 0 ? '' : ` · ${location}`;
  return `${citation.title}（v${citation.versionNo}${suffix}）`;
}

/** 引用片段文本：只取服务端给的片段，缺失即返回空串（不编造"摘要"）。 */
export function citationSnippetText(citation: CitationBlock): string {
  return citation.snippet?.trim() ?? '';
}

/**
 * 受控打开文档原文。
 *
 * <p>成功路径只返回服务端本次响应的内容；失败一律收敛为固定提示。
 */
export async function openCitationOriginal(
  api: CitationApi,
  documentId: number,
): Promise<CitationOpenOutcome> {
  try {
    const content = await api.readOriginal(documentId);
    return {
      content: typeof content?.content === 'string' ? content.content : '',
      mimeType:
        typeof content?.mimeType === 'string' ? content.mimeType : 'text/plain',
      ok: true,
    };
  } catch {
    return { message: citationFailureMessage('original'), ok: false };
  }
}

/** 受控重读引用片段（失权后再次读取必须失败，界面据此提示而不是沿用旧片段）。 */
export async function openCitationSnippet(
  api: CitationApi,
  citationId: string,
): Promise<CitationOpenOutcome> {
  try {
    const snippet = await api.readSnippet(citationId);
    return {
      content: typeof snippet === 'string' ? snippet : '',
      mimeType: 'text/plain',
      ok: true,
    };
  } catch {
    return { message: citationFailureMessage('snippet'), ok: false };
  }
}
