/**
 * 消息块目录（C03）：会话消息里可渲染的**首期结果块全集**与其判别渲染入口。
 *
 * <p>为什么把"块类型"收敛到一个模块：Chat、报表与宿主看到的是同一份消息。渲染器一旦对
 * "看起来像块"的数据尽力而为，注入面就出现了（AT-043）。所以这里逐类型按契约校验，
 * 未知类型在解析期拒绝、在渲染期**显式降级为提示**——既不猜字段，也不静默丢弃。
 *
 * <p>契约来源（本文件是消费者，不新增字段语义）：
 * <ul>
 *   <li>冻结 v1：`docs/contracts/ai/result-block.schema.json`（text / chart / error）。
 *       这三种直接交给 `@vben/ai-contracts` 的判别联合解析，本文件不重复实现，
 *       避免"同一协议两份校验器"漂移；</li>
 *   <li>平台 API 契约：`docs/ai-platform/contracts/openapi-core.json` 的 `ResultBlock`
 *       （clarification 的 question/options、report 的 reportId/version 及其取值域）；</li>
 *   <li>后端已冻结的应用端 VO：引用取 `AiKnowledgeSearchRespVO.Citation`、
 *       附件取 `AiFileUploadRespVO`（字段名逐一对齐，宿主可直接映射，无需二次改名）；</li>
 *   <li>`docs/ai-platform/04-api-chat-integration.md` §5 的首期块目录（table / file / action）。</li>
 * </ul>
 *
 * <p>判别键统一用 `kind`（冻结 v1 的口径）。平台 API 草案里写作 `type`+`id`，两者的差异记在
 * C03 交接记录里，由拥有方任务（X01/Q 系列）把新类型并入正式 Schema——本层不私自扩散第二套判别键。
 */

import type { ResultBlock } from '@vben/ai-contracts';

import type { ReportData, ReportSpec } from '../report/reportSpec';

import { parseResultBlocks } from '@vben/ai-contracts';

import {
  cellText,
  parseReportData,
  parseReportSpec,
} from '../report/reportSpec';
import { hasControlCharacter } from './markdown';

/** 待确认动作的参数摘要（文本，绝不是请求体）。 */
export interface ActionBlock {
  actionId: string;
  expiresAt: string;
  kind: 'action';
  parameterSummary: string;
  status?: string;
  toolName: string;
}

/** 引用块（字段与 K06 应用端引用 VO 对齐）。 */
export interface CitationBlock {
  chunkIndex: number;
  citationId: string;
  documentId?: number;
  kind: 'citation';
  locationRef: string;
  snippet?: string;
  title: string;
  versionNo: number;
}

/** 追问块：候选项或所需字段，回答后形成**新输入**，不伪装成结果。 */
export interface ClarificationBlock {
  fields?: ClarificationField[];
  kind: 'clarification';
  options: string[];
  question: string;
}

/** 追问所需的字段声明。 */
export interface ClarificationField {
  label: string;
  name: string;
  required: boolean;
}

/** 附件块（字段与 A07 应用端上传响应 VO 对齐）。 */
export interface FileBlock {
  businessKey?: string;
  businessType?: string;
  fileId: number;
  kind: 'file';
  mime?: string;
  name: string;
  size: number;
}

/** 报表块：`reportId`+`version` 是引用（打开/保存/刷新由宿主按当前 ACL 决定）。 */
export interface ReportBlock {
  data?: ReportData;
  kind: 'report';
  reportId: string;
  spec?: ReportSpec;
  title?: string;
  version: number;
}

/** 表格块（类型化单元格；数值与金额保留原始文本）。 */
export interface TableBlock {
  columns: TableColumn[];
  completeness?: string;
  kind: 'table';
  pageInfo?: TablePageInfo;
  rows: Record<string, unknown>[];
}

/** 表格列。 */
export interface TableColumn {
  field: string;
  label: string;
  unit?: string;
}

/** 表格分页信息。 */
export interface TablePageInfo {
  page: number;
  size: number;
  total: number;
}

/** 首期可渲染的消息块（冻结 v1 的三类 + 首期目录的六类）。 */
export type MessageBlock =
  | ActionBlock
  | CitationBlock
  | ClarificationBlock
  | FileBlock
  | ReportBlock
  | ResultBlock
  | TableBlock;

/** 可渲染项：解析成功，或"明确降级"。 */
export interface SupportedBlock {
  block: MessageBlock;
  kind: 'supported';
}

/** 降级项：保留来源类型与原因，界面据此给出提示（不渲染、不猜测）。 */
export interface UnsupportedBlock {
  kind: 'unsupported';
  reason: string;
  sourceKind: string;
}

/** 渲染入口的输入（每块恰为二者之一）。 */
export type RenderableBlock = SupportedBlock | UnsupportedBlock;

/** 冻结 v1 的判别键（交给 `@vben/ai-contracts` 解析）。 */
const FROZEN_KINDS = new Set(['chart', 'error', 'text']);

const IDENTIFIER = /^[a-z][a-z0-9_]{0,63}$/;
const OPAQUE_ID = /^[\w:-]{1,128}$/;
const REPORT_ID = /^rpt_[\w-]{3,35}$/;
const MIME_TYPE = /^[\w.+-]{1,64}\/[\w.+-]{1,64}$/;
const BUSINESS_TYPE = /^[\w.:-]{1,64}$/;
const RFC3339 =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/;

const COMPLETENESS = new Set(['COMPLETE', 'PARTIAL', 'UNKNOWN']);
const ACTION_STATUS = new Set(['CANCELLED', 'CONFIRMED', 'EXPIRED', 'PENDING']);

const MAX_TABLE_ROWS = 10_000;

function fail(message: string): never {
  throw new Error(`消息块不合法：${message}`);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 键白名单：未知键一律拒绝（与冻结协议的 `additionalProperties: false` 同口径）。 */
function object(value: unknown, allowed: string[]): Record<string, unknown> {
  if (!isPlainObject(value)) {
    return fail('必须是对象');
  }
  const allowedSet = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) {
      return fail(`未知字段 ${key}`);
    }
  }
  return value;
}

function text(value: unknown, maxLength: number, required = true): string {
  if (value === undefined || value === null) {
    if (required) {
      return fail('缺少必填文本');
    }
    return '';
  }
  if (typeof value !== 'string') {
    return fail('必须是文本');
  }
  if (value.length > maxLength || (required && value.length === 0)) {
    return fail('文本长度不合法');
  }
  return value;
}

function pattern(value: unknown, regex: RegExp, label: string): string {
  if (typeof value !== 'string' || !regex.test(value)) {
    return fail(`${label}不合法`);
  }
  return value;
}

function integer(value: unknown, min: number, max: number): number {
  if (
    typeof value !== 'number' ||
    !Number.isInteger(value) ||
    value < min ||
    value > max
  ) {
    return fail('整数越界');
  }
  return value;
}

function enumeration(value: unknown, allowed: Set<string>): string {
  if (typeof value !== 'string' || !allowed.has(value)) {
    return fail('取值不在允许域内');
  }
  return value;
}

function array(value: unknown, min: number, max: number): unknown[] {
  if (!Array.isArray(value) || value.length < min || value.length > max) {
    return fail('数组长度不合法');
  }
  return value;
}

function resultRows(value: unknown): Record<string, unknown>[] {
  if (value === undefined || value === null) {
    return [];
  }
  return array(value, 0, MAX_TABLE_ROWS).map((item) =>
    isPlainObject(item) ? item : fail('结果行必须是对象'),
  );
}

function parseTableBlock(node: Record<string, unknown>): TableBlock {
  const input = object(node, [
    'kind',
    'columns',
    'rows',
    'pageInfo',
    'completeness',
  ]);
  const parsed: TableBlock = {
    columns: array(input.columns, 1, 50).map((column) => {
      const item = object(column, ['field', 'label', 'unit']);
      return {
        field: pattern(item.field, IDENTIFIER, '列标识'),
        label: text(item.label, 100),
        ...(item.unit === undefined ? {} : { unit: text(item.unit, 32) }),
      };
    }),
    kind: 'table',
    rows: resultRows(input.rows),
  };
  if (input.pageInfo !== undefined) {
    const page = object(input.pageInfo, ['page', 'size', 'total']);
    parsed.pageInfo = {
      page: integer(page.page, 1, 1_000_000),
      size: integer(page.size, 1, 1000),
      total: integer(page.total, 0, 100_000_000),
    };
  }
  if (input.completeness !== undefined) {
    parsed.completeness = enumeration(input.completeness, COMPLETENESS);
  }
  return parsed;
}

function parseCitationBlock(node: Record<string, unknown>): CitationBlock {
  const input = object(node, [
    'kind',
    'citationId',
    'title',
    'versionNo',
    'chunkIndex',
    'locationRef',
    'snippet',
    'documentId',
  ]);
  return {
    chunkIndex: integer(input.chunkIndex, 0, 1_000_000),
    citationId: pattern(input.citationId, OPAQUE_ID, '引用标识'),
    ...(input.documentId === undefined
      ? {}
      : { documentId: integer(input.documentId, 1, Number.MAX_SAFE_INTEGER) }),
    kind: 'citation',
    locationRef: text(input.locationRef, 64),
    ...(input.snippet === undefined
      ? {}
      : { snippet: text(input.snippet, 4000, false) }),
    title: text(input.title, 200),
    versionNo: integer(input.versionNo, 1, 1_000_000),
  };
}

function parseClarificationBlock(
  node: Record<string, unknown>,
): ClarificationBlock {
  const input = object(node, ['kind', 'question', 'options', 'fields']);
  const parsed: ClarificationBlock = {
    kind: 'clarification',
    options: array(input.options, 0, 10).map((option) => text(option, 200)),
    question: text(input.question, 1000),
  };
  if (input.fields !== undefined) {
    parsed.fields = array(input.fields, 0, 10).map((field) => {
      const item = object(field, ['name', 'label', 'required']);
      if (typeof item.required !== 'boolean') {
        return fail('字段必填标记必须是布尔');
      }
      return {
        label: text(item.label, 64),
        name: pattern(item.name, IDENTIFIER, '字段名'),
        required: item.required,
      };
    });
  }
  return parsed;
}

function parseFileBlock(node: Record<string, unknown>): FileBlock {
  const input = object(node, [
    'kind',
    'fileId',
    'name',
    'size',
    'mime',
    'businessType',
    'businessKey',
  ]);
  const name = text(input.name, 255);
  if (hasControlCharacter(name)) {
    return fail('文件名含控制字符');
  }
  return {
    ...(input.businessKey === undefined
      ? {}
      : {
          businessKey: pattern(input.businessKey, /^[\w.:-]{1,128}$/, '业务键'),
        }),
    ...(input.businessType === undefined
      ? {}
      : {
          businessType: pattern(input.businessType, BUSINESS_TYPE, '业务类型'),
        }),
    fileId: integer(input.fileId, 1, Number.MAX_SAFE_INTEGER),
    kind: 'file',
    ...(input.mime === undefined
      ? {}
      : { mime: pattern(input.mime, MIME_TYPE, '媒体类型') }),
    name,
    size: integer(input.size, 0, Number.MAX_SAFE_INTEGER),
  };
}

function parseReportBlock(node: Record<string, unknown>): ReportBlock {
  const input = object(node, [
    'kind',
    'reportId',
    'version',
    'title',
    'spec',
    'data',
  ]);
  const parsed: ReportBlock = {
    kind: 'report',
    reportId: pattern(input.reportId, REPORT_ID, '报表标识'),
    version: integer(input.version, 1, 1_000_000),
  };
  if (input.title !== undefined) {
    parsed.title = text(input.title, 200, false);
  }
  if (input.spec !== undefined) {
    // 报表规格复用 R01/R07 的解析口径：不合法即拒绝，不把未校验规格交给渲染器
    parsed.spec = parseReportSpec(input.spec);
  }
  if (input.data !== undefined) {
    parsed.data = parseReportData(input.data);
  }
  return parsed;
}

function parseActionBlock(node: Record<string, unknown>): ActionBlock {
  const input = object(node, [
    'kind',
    'actionId',
    'toolName',
    'parameterSummary',
    'expiresAt',
    'status',
  ]);
  return {
    actionId: pattern(input.actionId, OPAQUE_ID, '动作标识'),
    expiresAt: pattern(input.expiresAt, RFC3339, '到期时间'),
    kind: 'action',
    parameterSummary: text(input.parameterSummary, 500),
    ...(input.status === undefined
      ? {}
      : { status: enumeration(input.status, ACTION_STATUS) }),
    toolName: text(input.toolName, 64),
  };
}

/** 解析单个消息块；任何不合规输入都抛错（调用方用 {@link toRenderableBlock} 做降级）。 */
export function parseMessageBlock(input: unknown): MessageBlock {
  if (!isPlainObject(input)) {
    return fail('必须是对象');
  }
  const kind = input.kind;
  if (typeof kind !== 'string' || kind.length === 0) {
    return fail('缺少 kind');
  }
  if (FROZEN_KINDS.has(kind)) {
    try {
      const parsed = parseResultBlocks([input])[0];
      if (!parsed) {
        return fail('冻结 v1 结果块解析为空');
      }
      return parsed;
    } catch {
      // 冻结协议的校验细节由 @vben/ai-contracts 给出；这里只保留"不符合冻结协议"这一稳定结论
      return fail(`不符合冻结 v1 的 ${kind} 块`);
    }
  }
  switch (kind) {
    case 'action': {
      return parseActionBlock(input);
    }
    case 'citation': {
      return parseCitationBlock(input);
    }
    case 'clarification': {
      return parseClarificationBlock(input);
    }
    case 'file': {
      return parseFileBlock(input);
    }
    case 'report': {
      return parseReportBlock(input);
    }
    case 'table': {
      return parseTableBlock(input);
    }
    default: {
      return fail(`未知结果类型 ${kind}`);
    }
  }
}

/** 来源类型：`kind` 优先，其次是平台 API 草案的 `type`，都不是则给 JS 类型名。 */
export function sourceKindOf(input: unknown): string {
  if (isPlainObject(input)) {
    if (typeof input.kind === 'string' && input.kind.length > 0) {
      return input.kind;
    }
    if (typeof input.type === 'string' && input.type.length > 0) {
      return input.type;
    }
  }
  return typeof input;
}

/**
 * 渲染入口：解析成功即 `supported`，失败即 `unsupported`（带来源类型与原因）。
 *
 * <p>降级是**明确**的：界面会显示"不支持的结果类型 + 来源类型 + 原因"，而不是留白或猜测渲染。
 */
export function toRenderableBlock(input: unknown): RenderableBlock {
  try {
    return { block: parseMessageBlock(input), kind: 'supported' };
  } catch (error) {
    return {
      kind: 'unsupported',
      reason: error instanceof Error ? error.message : '结果块不合法',
      sourceKind: sourceKindOf(input),
    };
  }
}

/** 批量降级解析（消息里每块独立降级，一块不合法不影响其它块）。 */
export function toRenderableBlocks(inputs: unknown[]): RenderableBlock[] {
  return inputs.map((input) => toRenderableBlock(input));
}

/** 单元格显示文本：空值显示为 `—`（不补 0、不编造），其余沿用报表层的取值口径。 */
export function cellDisplay(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return cellText(value);
}

/**
 * 动作是否已过期：只看到期时间与服务端给的 `EXPIRED` 结论。
 *
 * <p>`CANCELLED`/`CONFIRMED` 是**已处理**而不是过期（两者提示语义不同），由渲染层先判"已处理"。
 */
export function isActionExpired(block: ActionBlock, now = Date.now()): boolean {
  if (block.status === 'EXPIRED') {
    return true;
  }
  const expiresAt = Date.parse(block.expiresAt);
  return Number.isFinite(expiresAt) && expiresAt <= now;
}
