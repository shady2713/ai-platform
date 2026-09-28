/**
 * 图片识别附件（X02）：图片上传 → 受控图片引用 → 图片理解 / OCR 结果引用与失败态。
 *
 * <p>三条不变量，全部由本模块而不是页面保证：
 * <ol>
 *   <li><b>只有私有文件标识</b>：上传返回、请求携带、结果引用的都是中台文件编号
 *       （`fileId` + 声明元数据），类型里没有 URL 字段——上游临时地址无法进入协议（FR-35）；</li>
 *   <li><b>失败要说清楚且不回显宿主文本</b>：能力未开通按稳定 `reasonCode`/`errorCode` 映射成固定提示，
 *       宿主异常的原文（可能带票据或上游地址）不进入界面；</li>
 *   <li><b>不伪装识别质量</b>：OCR 结果必须带页码/范围/置信度来源与"机器识别未核验"提示，
 *       置信度只有上游给出时才展示数值。</li>
 * </ol>
 *
 * <p>词汇与上限（白名单格式、体积上限、用量语义、不可用原因码、错误码）复用
 * `@vben/ai-contracts` 的 X01 冻结契约；响应校验用本模块的**显式形状校验**（字段缺失、越界、
 * 未知结构一律按失败处理），不"尽力渲染"。
 */

import type {
  ImageMimeType,
  MultimodalAvailability,
  MultimodalCapability,
  MultimodalUnavailableReason,
  UsageSource,
  UsageUnit,
} from '@vben/ai-contracts';

import {
  IMAGE_MIME_TYPES,
  MULTIMODAL_LIMITS,
  MULTIMODAL_UNAVAILABLE_ERROR_CODES,
  MULTIMODAL_UNAVAILABLE_REASONS,
  usageSourceSchema,
  usageUnitSchema,
} from '@vben/ai-contracts';

import { formatSize, sanitizeFileName } from './attachment';

/** 平台私有图片引用：只有文件编号与展示元数据，**没有地址字段**。 */
export interface PrivateImageRef {
  fileId: number;
  height?: number;
  mime: ImageMimeType;
  name: string;
  size: number;
  width?: number;
}

/** 待上传图片（来自宿主文件选择器；大小与类型先在本层判定，再交宿主上传）。 */
export interface ImageUploadCandidate {
  mime: string;
  name: string;
  size: number;
}

/** 上传校验失败原因（稳定码，界面按固定文案展示）。 */
export type ImageUploadRejection = 'EMPTY' | 'TOO_LARGE' | 'UNSUPPORTED_MIME';

/** 上传校验结论。 */
export type ImageUploadCheck =
  | { file: ImageUploadCandidate; ok: true }
  | { message: string; ok: false; reason: ImageUploadRejection };

/** 用量视图（UNKNOWN 时 quantity 为 null，不显示假 0）。 */
export interface VisionUsageView {
  quantity: null | number;
  source: UsageSource;
  unit: UsageUnit;
}

/** OCR 区域视图（相对图片的归一化坐标；置信度只有上游给出时才有值）。 */
export interface VisionOcrRegionView {
  confidence: number;
  height: number;
  text: string;
  width: number;
  x: number;
  y: number;
}

/** 识别成功：理解与 OCR 共用的结果引用（引用私有文件，不带地址）。 */
export interface VisionOk {
  capability: MultimodalCapability;
  confidenceSource?: 'PROVIDER' | 'UNKNOWN';
  fileId: number;
  page?: number;
  regions?: VisionOcrRegionView[];
  regionSource?: 'PROVIDER' | 'WHOLE_PAGE';
  reviewRequired: boolean;
  state: 'OK';
  text: string;
  usage?: VisionUsageView;
}

/** 能力不可用：稳定原因码 + 平台错误码（终态，不得自动改走其它端点/供应商）。 */
export interface VisionUnavailable {
  capability: MultimodalCapability;
  errorCode: string;
  message: string;
  reasonCode: MultimodalUnavailableReason;
  state: 'UNAVAILABLE';
}

/** 其它失败：只给固定提示与可选稳定错误码，不回显宿主异常文本。 */
export interface VisionFailed {
  capability: MultimodalCapability;
  errorCode: null | string;
  message: string;
  state: 'FAILED';
}

/** 识别结论。 */
export type VisionOutcome = VisionFailed | VisionOk | VisionUnavailable;

/** 图片识别附件端口（宿主实现：上传/读取走应用端文件接口，识别走视觉接口）。 */
export interface VisionAttachmentApi {
  /** OCR：语义同后端"单张图片 OCR"（语言提示可选）。 */
  ocr?(request: {
    image: PrivateImageRef;
    languageHint?: string;
  }): Promise<unknown>;
  /** 受控读取图片字节用于预览（失权即 reject）。 */
  readImage(fileId: number): Promise<Blob>;
  /** 图片理解。 */
  understand?(request: {
    image: PrivateImageRef;
    instruction: string;
  }): Promise<unknown>;
  /** 受控上传：返回平台私有图片引用（只有文件编号）。 */
  uploadImage(file: ImageUploadCandidate): Promise<PrivateImageRef>;
}

/** 上传拒绝原因的固定文案。 */
const UPLOAD_REJECTIONS: Record<ImageUploadRejection, string> = {
  EMPTY: '图片内容为空，无法上传',
  TOO_LARGE: `图片超过 ${Math.floor(MULTIMODAL_LIMITS.maxImageBytes / 1024 / 1024)} MiB 上限`,
  UNSUPPORTED_MIME: '只支持 PNG / JPEG / WebP 图片',
};

/** 能力不可用原因的固定文案（与 X01 的 reasonCode 一一对应）。 */
const UNAVAILABLE_REASONS: Record<MultimodalUnavailableReason, string> = {
  CAPABILITY_NOT_ENABLED: '该端点未开通图片识别能力（需先声明并通过真实探测）',
  ENDPOINT_DISABLED: '该端点已停用，请联系管理员',
  OUTBOUND_BLOCKED: '当前资源等级不允许把图片发送到该端点',
  SERVICE_RESOURCE_UNAVAILABLE: '该服务发布版本依赖的资源绑定当前不可用',
};

/** 通用失败提示（不回显宿主异常文本）。 */
export const VISION_FAILURE_MESSAGE =
  '图片识别失败：请稍后重试，或改用文本输入';

/** 未接入识别端口时的只读提示。 */
export const VISION_READONLY_MESSAGE = '未接入图片识别端口：仅展示附件信息';

/** 机器识别未核验提示（OCR 结果必须展示）。 */
export const VISION_REVIEW_NOTICE = '机器识别结果，未经人工核验';

/** 预览失败提示（失权/撤回/不存在同语义，不回显宿主异常）。 */
export const IMAGE_PREVIEW_FAILURE_MESSAGE =
  '图片不可预览：无权限、已撤回或不存在';

/** 上传前校验：媒体类型白名单、体积上限、空内容；文件名只做清洗（失败才给提示）。 */
export function checkImageUpload(
  candidate: ImageUploadCandidate,
): ImageUploadCheck {
  const mime =
    typeof candidate?.mime === 'string'
      ? candidate.mime.trim().toLowerCase()
      : '';
  const name = sanitizeFileName(candidate?.name ?? '');
  const size =
    typeof candidate?.size === 'number' ? candidate.size : Number.NaN;
  if (!(IMAGE_MIME_TYPES as readonly string[]).includes(mime)) {
    return {
      message: UPLOAD_REJECTIONS.UNSUPPORTED_MIME,
      ok: false,
      reason: 'UNSUPPORTED_MIME',
    };
  }
  if (!Number.isFinite(size) || size <= 0) {
    return { message: UPLOAD_REJECTIONS.EMPTY, ok: false, reason: 'EMPTY' };
  }
  if (size > MULTIMODAL_LIMITS.maxImageBytes) {
    return {
      message: UPLOAD_REJECTIONS.TOO_LARGE,
      ok: false,
      reason: 'TOO_LARGE',
    };
  }
  return { file: { mime, name, size }, ok: true };
}

/** 上传拒绝原因的固定文案。 */
export function uploadRejectionMessage(reason: ImageUploadRejection): string {
  return UPLOAD_REJECTIONS[reason];
}

/** 能力不可用原因的固定文案。 */
export function unavailableReasonMessage(
  reason: MultimodalUnavailableReason,
): string {
  return UNAVAILABLE_REASONS[reason];
}

/** 原因码对应的平台错误码（复用 X01 登记表）。 */
export function unavailableErrorCode(
  reason: MultimodalUnavailableReason,
): string {
  return MULTIMODAL_UNAVAILABLE_ERROR_CODES[reason];
}

/** 能力当前是否可用（不可用必须原样提示，不得静默改走别的通道）。 */
export function capabilityUsable(
  availability: MultimodalAvailability | undefined,
): boolean {
  return availability?.state === 'AVAILABLE';
}

/** 能力目录的不可用提示（含平台错误码）；可用或未提供目录时返回空串。 */
export function availabilityNoticeText(
  capability: MultimodalCapability,
  availability: MultimodalAvailability | undefined,
): string {
  const unavailable = unavailableFromAvailability(capability, availability);
  return unavailable === null
    ? ''
    : `${unavailable.message}（${unavailable.errorCode}）`;
}

/** 不可用说明：来自能力目录的稳定原因码 + 平台错误码。 */
export function unavailableFromAvailability(
  capability: MultimodalCapability,
  availability: MultimodalAvailability | undefined,
): null | VisionUnavailable {
  if (availability === undefined || availability.state === 'AVAILABLE') {
    return null;
  }
  return {
    capability,
    errorCode: availability.errorCode,
    message:
      availability.message || unavailableReasonMessage(availability.reasonCode),
    reasonCode: availability.reasonCode,
    state: 'UNAVAILABLE',
  };
}

/** 用量展示：UNKNOWN 说"未提供"，不显示假 0。 */
export function usageText(usage: undefined | VisionUsageView): string {
  if (
    usage === undefined ||
    usage.source === 'UNKNOWN' ||
    usage.quantity === null
  ) {
    return '用量：未提供';
  }
  const label = usage.source === 'ESTIMATED' ? '平台估算' : '上游提供';
  return `用量：${usage.quantity} ${usage.unit}（${label}）`;
}

/** OCR 溯源展示：页码、识别范围与置信度来源必须一起出现。 */
export function ocrProvenanceText(outcome: VisionOk): string {
  const parts = [
    `第 ${outcome.page ?? 1} 页`,
    outcome.regionSource === 'PROVIDER'
      ? '识别范围：上游逐块'
      : '识别范围：整页',

    outcome.confidenceSource === 'PROVIDER'
      ? '置信度：上游提供'
      : '置信度：未提供（上游未返回，平台不猜测）',
    VISION_REVIEW_NOTICE,
  ];
  return parts.join(' · ');
}

/** 区域展示文案：坐标与置信度一起给出（置信度是上游给出的实测值）。 */
export function regionText(region: VisionOcrRegionView): string {
  const percent = (value: number): string => `${Math.round(value * 100)}%`;
  return `区域 ${percent(region.x)},${percent(region.y)} ${percent(region.width)}×${percent(
    region.height,
  )} · 置信度 ${region.confidence} · ${region.text}`;
}

/** 解析识别响应：形状不合法一律按失败处理（不猜字段、不部分渲染）。 */
export function interpretVisionResponse(response: unknown): VisionOutcome {
  const capability = capabilityOf(response);
  if (!isRecord(response)) {
    return failed(capability, null);
  }
  if (response.outcome === 'UNAVAILABLE') {
    const unavailable = response.unavailable;
    if (
      !isRecord(unavailable) ||
      !isUnavailableReason(unavailable.reasonCode)
    ) {
      return failed(capability, null);
    }
    return {
      capability,
      errorCode: isNonEmptyString(unavailable.errorCode)
        ? unavailable.errorCode
        : unavailableErrorCode(unavailable.reasonCode),
      message: isNonEmptyString(unavailable.message)
        ? unavailable.message
        : unavailableReasonMessage(unavailable.reasonCode),
      reasonCode: unavailable.reasonCode,
      state: 'UNAVAILABLE',
    };
  }
  if (response.outcome !== 'COMPLETED' || !isRecord(response.result)) {
    return failed(capability, null);
  }
  const result = response.result;
  if (result.kind === 'image_ocr') {
    return interpretOcrResult(capability, result);
  }
  if (result.kind === 'image_understanding') {
    if (!isNonEmptyString(result.text) || !isPositiveInteger(result.fileId)) {
      return failed(capability, null);
    }
    return {
      capability,
      fileId: result.fileId,
      reviewRequired: false,
      state: 'OK',
      text: result.text,
      usage: toUsageView(result.usage),
    };
  }
  return failed(capability, null);
}

/** 从宿主异常映射失败结论：只认结构化的稳定码，不解析异常文本。 */
export function failureFromThrown(
  capability: MultimodalCapability,
  thrown: unknown,
): VisionOutcome {
  return structuredFailure(thrown) ?? failed(capability, null);
}

/** 结果引用摘要（只含私有文件标识与展示元数据，没有地址）。 */
export function resultReferenceSummary(
  outcome: VisionOk,
  name: string,
): string {
  return `${sanitizeFileName(name)} (#${outcome.fileId})`;
}

/** 附件摘要（名称 · 大小 · 媒体类型）。 */
export function imageAttachmentSummary(file: PrivateImageRef): string {
  return `${sanitizeFileName(file.name)} · ${formatSize(file.size)} · ${file.mime}`;
}

function interpretOcrResult(
  capability: MultimodalCapability,
  result: Record<string, unknown>,
): VisionOutcome {
  if (!isNonEmptyString(result.text) || !isPositiveInteger(result.fileId)) {
    return failed(capability, null);
  }
  const page = isPositiveInteger(result.page) ? result.page : 1;
  const regionSource =
    result.regionSource === 'PROVIDER' ? 'PROVIDER' : 'WHOLE_PAGE';
  const confidenceSource =
    result.confidenceSource === 'PROVIDER' ? 'PROVIDER' : 'UNKNOWN';
  const regions = parseRegions(result.regions);
  return {
    capability,
    confidenceSource,
    fileId: result.fileId,
    page,
    regionSource,
    regions,
    reviewRequired: result.reviewRequired !== false,
    state: 'OK',
    text: result.text,
    usage: toUsageView(result.usage),
  };
}

/** 区域数组：非数组或任一区域不合法即视为没有区域（整页归因），不做部分渲染。 */
function parseRegions(raw: unknown): undefined | VisionOcrRegionView[] {
  if (raw === undefined) {
    return undefined;
  }
  if (
    !Array.isArray(raw) ||
    raw.length === 0 ||
    raw.length > MULTIMODAL_LIMITS.maxOcrRegions
  ) {
    return undefined;
  }
  const regions: VisionOcrRegionView[] = [];
  for (const candidate of raw) {
    if (!isRecord(candidate)) {
      return undefined;
    }
    const confidence = candidate.confidence;
    const x = candidate.x;
    const y = candidate.y;
    const width = candidate.width;
    const height = candidate.height;
    if (
      typeof confidence !== 'number' ||
      confidence < 0 ||
      confidence > 1 ||
      !isUnitInterval(x) ||
      !isUnitInterval(y) ||
      !isUnitInterval(width) ||
      !isUnitInterval(height) ||
      x + width > 1 ||
      y + height > 1
    ) {
      return undefined;
    }
    regions.push({
      confidence,
      height,
      text: typeof candidate.text === 'string' ? candidate.text : '',
      width,
      x,
      y,
    });
  }
  return regions;
}

const USAGE_SOURCES = usageSourceSchema.options as readonly UsageSource[];

const USAGE_UNITS = usageUnitSchema.options as readonly UsageUnit[];

/** 只有冻结词表里的取值才算成立的用量来源（未知形状一律丢弃，不猜）。 */
function isUsageSource(value: unknown): value is UsageSource {
  return (
    typeof value === 'string' &&
    (USAGE_SOURCES as readonly string[]).includes(value)
  );
}

/** 只有冻结词表里的取值才算成立的计量单位。 */
function isUsageUnit(value: unknown): value is UsageUnit {
  return (
    typeof value === 'string' &&
    (USAGE_UNITS as readonly string[]).includes(value)
  );
}

function toUsageView(usage: unknown): undefined | VisionUsageView {
  if (!isRecord(usage)) {
    return undefined;
  }
  const source = usage.source;
  const unit = usage.unit;
  const quantity = usage.quantity;
  if (!isUsageSource(source) || !isUsageUnit(unit)) {
    return undefined;
  }
  // UNKNOWN 必须 quantity=null：上游没给就不显示任何数字（AT-060）
  if (source === 'UNKNOWN') {
    return { quantity: null, source, unit };
  }
  return typeof quantity === 'number' && quantity >= 0
    ? { quantity, source, unit }
    : undefined;
}

/** 宿主可以把平台错误码包装成 `{ state, errorCode, reasonCode }`；只接受登记过的稳定值。 */
function structuredFailure(
  thrown: unknown,
): null | VisionFailed | VisionUnavailable {
  if (!isRecord(thrown)) {
    return null;
  }
  const capability = capabilityOf(thrown);
  if (
    thrown.state === 'UNAVAILABLE' &&
    isUnavailableReason(thrown.reasonCode)
  ) {
    return {
      capability,
      errorCode: isNonEmptyString(thrown.errorCode)
        ? thrown.errorCode
        : unavailableErrorCode(thrown.reasonCode),
      message: unavailableReasonMessage(thrown.reasonCode),
      reasonCode: thrown.reasonCode,
      state: 'UNAVAILABLE',
    };
  }
  if (thrown.state === 'FAILED') {
    return {
      capability,
      errorCode: isNonEmptyString(thrown.errorCode) ? thrown.errorCode : null,
      message: VISION_FAILURE_MESSAGE,
      state: 'FAILED',
    };
  }
  return null;
}

function failed(
  capability: MultimodalCapability,
  errorCode: null | string,
): VisionFailed {
  return {
    capability,
    errorCode,
    message: VISION_FAILURE_MESSAGE,
    state: 'FAILED',
  };
}

function capabilityOf(value: unknown): MultimodalCapability {
  const capability = isRecord(value) ? value.capability : undefined;
  return typeof capability === 'string'
    ? (capability as MultimodalCapability)
    : 'IMAGE_OCR';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

function isUnitInterval(value: unknown): value is number {
  return typeof value === 'number' && value >= 0 && value <= 1;
}

function isUnavailableReason(
  value: unknown,
): value is MultimodalUnavailableReason {
  return (
    typeof value === 'string' &&
    (MULTIMODAL_UNAVAILABLE_REASONS as readonly string[]).includes(value)
  );
}
