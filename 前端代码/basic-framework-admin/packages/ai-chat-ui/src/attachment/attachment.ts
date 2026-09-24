/**
 * 附件读取（C03）：附件的**受控下载/预览**。块里没有任何 URL，只有服务端签发的文件编号。
 *
 * <p>为什么块里不能有 URL：设计契约要求"受控下载，不透传上游临时 URL"
 * （`docs/ai-platform/04-api-chat-integration.md` §5）。上游临时地址一旦进了消息体，
 * 就等于把过期时间、签名与存储位置交给客户端，因此本层连"URL 字段"都不定义——
 * 宿主只能按 `fileId` 用当前票据请求应用端文件接口。
 *
 * <p>与引用模块同一口径：失败只给固定提示，不回显宿主异常文本；文件名只用于**显示与建议下载名**，
 * 逐段剥离目录成分与控制字符，避免 `../` 之类被下游当成路径使用。
 */

import type { FileBlock } from '../message/blocks';

import { stripControlCharacters } from '../message/markdown';

/** 附件受控读取端口（宿主实现：`GET /ai/file/{fileId}`，票据只在请求头）。 */
export interface AttachmentApi {
  /** 受控下载：由宿主用当前票据取回内容并落地，失败即 reject。 */
  download(fileId: number, fileName: string): Promise<void>;
  /** 受控预览：取回文本内容用于就地展示，失败即 reject。 */
  read(fileId: number): Promise<AttachmentContent>;
}

/** 受控读取返回的内容。 */
export interface AttachmentContent {
  content: string;
  mimeType: string;
}

/** 读取失败（原因只给固定提示）。 */
export interface AttachmentFailure {
  message: string;
  ok: false;
}

/** 读取成功。 */
export interface AttachmentOk {
  content: string;
  mimeType: string;
  ok: true;
}

/** 读取结果。 */
export type AttachmentOutcome = AttachmentFailure | AttachmentOk;

/** 附件动作。 */
export type AttachmentAction = 'download' | 'read';

const FAILED: Record<AttachmentAction, string> = {
  download: '文件不可下载：无权限、已撤回或不存在',
  read: '文件不可预览：无权限、已撤回或不存在',
};

const SIZE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];
const PREVIEWABLE_MIME =
  /^(?:application\/(?:json|xml|x-ndjson)|text\/)|[+/](?:json|xml)$/u;

/** 读取失败的固定提示（界面只输出它，绝不回显宿主异常文本）。 */
export function attachmentFailureMessage(action: AttachmentAction): string {
  return FAILED[action];
}

/** 人类可读大小；非法输入给"未知大小"而不是 0（不把未知说成 0）。 */
export function formatSize(bytes: number): string {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) {
    return '未知大小';
  }
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < SIZE_UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const shown =
    unit === 0
      ? String(Math.round(value))
      : value.toFixed(1).replace(/\.0$/u, '');
  return `${shown} ${SIZE_UNITS[unit] ?? 'B'}`;
}

/** 建议使用的文件名：剥离目录成分与控制字符，空结果回退为固定名。 */
export function sanitizeFileName(name: string): string {
  const base =
    String(name ?? '')
      .replaceAll('\\', '/')
      .split('/')
      .pop() ?? '';
  const cleaned = stripControlCharacters(base)
    .replace(/^\.+/u, '')
    .trim()
    .slice(0, 200);
  return cleaned.length === 0 ? 'attachment' : cleaned;
}

/** 是否可就地预览：只有文本类媒体类型才预览，二进制一律走下载。 */
export function isPreviewable(mime: string | undefined): boolean {
  return typeof mime === 'string' && PREVIEWABLE_MIME.test(mime);
}

/** 附件摘要（名称 · 大小 · 媒体类型）。 */
export function attachmentSummary(block: FileBlock): string {
  const mime = block.mime ? ` · ${block.mime}` : '';
  return `${block.name} · ${formatSize(block.size)}${mime}`;
}

/** 受控读取文件内容用于预览（失权后再次读取必须失败）。 */
export async function readAttachment(
  api: AttachmentApi,
  fileId: number,
): Promise<AttachmentOutcome> {
  try {
    const content = await api.read(fileId);
    return {
      content: typeof content?.content === 'string' ? content.content : '',
      mimeType:
        typeof content?.mimeType === 'string' ? content.mimeType : 'text/plain',
      ok: true,
    };
  } catch {
    return { message: attachmentFailureMessage('read'), ok: false };
  }
}

/** 受控下载（下载名用清洗后的名称；失败同样只给固定提示）。 */
export async function downloadAttachment(
  api: AttachmentApi,
  block: FileBlock,
): Promise<AttachmentOutcome> {
  try {
    await api.download(block.fileId, sanitizeFileName(block.name));
    return { content: '', mimeType: '', ok: true };
  } catch {
    return { message: attachmentFailureMessage('download'), ok: false };
  }
}
