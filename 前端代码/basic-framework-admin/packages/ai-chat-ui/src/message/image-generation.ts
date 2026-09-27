/**
 * 图片生成/编辑卡片的视图模型与展示口径（X03）：卡片只吃一份**平台侧视图数据**，
 * 自己不发请求、不接触上游地址。
 *
 * <p>为什么卡片里连"URL 字段"都没有：与附件模块（`attachment.ts`）同一口径——
 * 产物只以平台私有文件编号出现，预览/下载由宿主按**当前票据**走平台端点
 * （`docs/ai-platform/04-api-chat-integration.md` §5）。上游临时地址带签名与过期时间，
 * 一旦进入消息体就是把存储位置和凭据交给客户端，所以本模块不定义任何 URL 字段。
 *
 * <p>冻结词汇复用（`@vben/ai-contracts`）：
 * <ul>
 *   <li>能力名取 {@link ImageGenerationCapability}（`IMAGE_GENERATION` / `IMAGE_EDIT`），
 *       不写 `GENERATE_IMAGE` 之类同义词；</li>
 *   <li>任务状态取 {@link ImageGenerationStatus}（`RunStatus` 的子集，与后端任务状态同名）；</li>
 *   <li>产物媒体类型取 `ImageMimeType`（png/jpeg/webp 白名单）。</li>
 * </ul>
 *
 * <p>已知的契约差异（消费方按原样展示，不改写语义，差异记录在交接说明里）：
 * `usage` 只有 `unit` + `quantity`，表达不了 `MultimodalUsage` 必填的 `source`
 * （REPORTED/ESTIMATED/UNKNOWN），因此卡片无法区分"实测"与"估算"，只能如实显示数字或"用量未知"。
 *
 * <p>展示口径：未知一律说"未知"（尺寸/用量/进度），**不补 0、不编造百分比、不摆空占位**。
 */

import type {
  ImageMimeType,
  MultimodalCapability,
  RunStatus,
} from '@vben/ai-contracts';

/** 卡片承接的能力：冻结能力名中的两个生成类能力（不做同义词映射）。 */
export type ImageGenerationCapability = Extract<
  MultimodalCapability,
  'IMAGE_EDIT' | 'IMAGE_GENERATION'
>;

/**
 * 卡片展示的任务状态：与后端任务状态同名，取自冻结的 `RunStatus` 子集
 * （`WAITING_INPUT` / `WAITING_CONFIRMATION` 与生成任务无关，故不在此列）。
 */
export type ImageGenerationStatus = Extract<
  RunStatus,
  'CANCELLED' | 'FAILED' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED'
>;

/** 生成/编辑产物：只有平台私有文件编号与展示元数据，宽高未知为 null（不编造）。 */
export interface GeneratedImageAsset {
  fileId: number;
  height: null | number;
  mimeType: ImageMimeType;
  sizeBytes: number;
  width: null | number;
}

/** 实际用量：上游未报告时 `quantity` 为 null（不用 0 冒充实测）。 */
export interface ImageGenerationUsage {
  quantity: null | number;
  unit: string;
}

/** 卡片视图：由宿主/平台侧组装，卡片从不自行取数。 */
export interface ImageGenerationView {
  /** 后端稳定错误码：界面只加固定文案展示，不改写语义。 */
  failureCode: null | string;
  /** 产物：只有成功且有产物时非空。 */
  images: GeneratedImageAsset[];
  /** 后端给出的进度；没有进度就是 null，界面显示"进行中"而不是假百分比。 */
  progressPercent: null | number;
  /** 生成提示词 / 编辑指令。 */
  prompt: string;
  /** 冻结能力名：生成模式没有源图。 */
  requestKind: ImageGenerationCapability;
  /** 编辑模式的源图（平台文件编号）；生成模式为 null。 */
  sourceFileId: null | number;
  /** 任务状态：与后端同名。 */
  status: ImageGenerationStatus;
  /** 实际用量；未知为 null。 */
  usage: ImageGenerationUsage | null;
}

/** 状态文案：一个状态一条文案，不把"排队"说成"进行中"。 */
export const IMAGE_GENERATION_STATUS_LABELS = {
  CANCELLED: '已取消',
  FAILED: '失败',
  QUEUED: '排队中',
  RUNNING: '进行中',
  SUCCEEDED: '已完成',
} as const satisfies Record<ImageGenerationStatus, string>;

/** 能力文案：编辑与生成在界面上必须可区分。 */
export const IMAGE_GENERATION_KIND_LABELS = {
  IMAGE_EDIT: '图片编辑',
  IMAGE_GENERATION: '图片生成',
} as const satisfies Record<ImageGenerationCapability, string>;

/** 预览失败固定提示：不回显宿主异常文本（异常里可能带请求地址与票据）。 */
export const IMAGE_PREVIEW_FAILURE = '图片不可预览：无权限、已撤回或不存在';

/** 状态文案。 */
export function imageStatusLabel(status: ImageGenerationStatus): string {
  return IMAGE_GENERATION_STATUS_LABELS[status];
}

/** 能力文案。 */
export function capabilityLabel(kind: ImageGenerationCapability): string {
  return IMAGE_GENERATION_KIND_LABELS[kind];
}

/**
 * 是否处于**非终态**（排队/进行中）：只有这两种状态给取消入口，
 * 成功/失败/已取消都是终态，不再提供"点了必然失败"的取消按钮。
 */
export function isImageTaskActive(status: ImageGenerationStatus): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

/**
 * 进度百分比：后端没给（或给了非有限值）返回 null —— 界面显示"进行中"，不画假进度条；
 * 越界值收敛到 0..100（进度条只能表示这个区间），四舍五入为整数。
 */
export function progressPercentOf(
  progress: null | number | undefined,
): null | number {
  if (typeof progress !== 'number' || !Number.isFinite(progress)) {
    return null;
  }
  return Math.min(100, Math.max(0, Math.round(progress)));
}

/**
 * 尺寸文案：宽高**都**已知才显示 `宽 × 高`，任一未知显示"尺寸未知"——
 * 不用 0 补齐，也不显示 `0×0`（"单边未知"会被渲染成假尺寸）。
 */
export function imageDimensionText(
  width: null | number,
  height: null | number,
): string {
  if (!isKnownDimension(width) || !isKnownDimension(height)) {
    return '尺寸未知';
  }
  return `${width} × ${height}`;
}

/** 是否是可展示的正整数尺寸（0/负数/小数/NaN 都算未知）。 */
function isKnownDimension(value: null | number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

/** 用量文案：未知一律"用量未知"，绝不显示 0 或编造数字。 */
export function usageText(usage: ImageGenerationUsage | null): string {
  if (
    usage === null ||
    usage.quantity === null ||
    !Number.isFinite(usage.quantity)
  ) {
    return '用量未知';
  }
  const unit = usage.unit.trim();
  return unit.length === 0
    ? String(usage.quantity)
    : `${usage.quantity} ${unit}`;
}

/** 预览地址解析结果：丢弃（组件已销毁）与失败都不向外抛出。 */
export type ImagePreviewResolution =
  | { ok: false; reason: 'DISCARDED' | 'FAILED' }
  | { ok: true; url: string };

/**
 * 受控解析预览地址：只把 `fileId` 交给宿主（平台端点由宿主按当前票据解析）。
 *
 * <p>两类边界在这里被吸收成返回值：
 * <ul>
 *   <li>解析回来时组件已销毁（`isDisposed()`）→ `DISCARDED`，界面不再变化；</li>
 *   <li>宿主解析失败（拒绝）→ `FAILED`，界面只给固定提示，不复述异常文本。</li>
 * </ul>
 * 因此调用方拿到的永远是一个**已结算的普通值**，不会产生未处理的 Promise。
 */
export async function resolvePreviewUrl(
  resolve: (fileId: number) => Promise<null | string>,
  fileId: number,
  isDisposed: () => boolean,
): Promise<ImagePreviewResolution> {
  let url: null | string;
  try {
    url = await resolve(fileId);
  } catch {
    return isDisposed()
      ? { ok: false, reason: 'DISCARDED' }
      : { ok: false, reason: 'FAILED' };
  }
  if (isDisposed()) {
    return { ok: false, reason: 'DISCARDED' };
  }
  return typeof url === 'string' && url.length > 0
    ? { ok: true, url }
    : { ok: false, reason: 'FAILED' };
}
