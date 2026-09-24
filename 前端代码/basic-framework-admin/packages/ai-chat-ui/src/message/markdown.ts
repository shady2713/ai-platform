/**
 * 受控 Markdown（C03）：把模型/文档里的文本转成**允许节点**，其余一律按文本处理。
 *
 * <p>为什么不是"通用 Markdown 渲染器"：冻结协议允许的 text 块只承诺"纯文本或受控 Markdown AST，
 * 禁原始 HTML"（`docs/ai-platform/04-api-chat-integration.md` §5）。通用渲染器会带来三个不可接受的面——
 * 原始 HTML 注入、`javascript:`/`data:` 链接、以及不受限的嵌套带来的资源放大。所以这里只认
 * 一小撮结构（标题/段落/引用/列表/围栏代码/强调/行内代码/链接），**原始 HTML 从未被解析**：
 * `<script>alert(1)</script>` 与普通文字同路，最终以文本插值进入 DOM。
 *
 * <p>URL 校验发生在**解析期**而不是渲染期：AST 里只可能存在合规链接，渲染器没有"忘记校验"的机会。
 * 相对地址与协议相对地址（`//host/path`）一律拒绝——没有可信基准就无法判断它指向哪里。
 *
 * <p>金额与精度无关：本文件只做结构转换，不改写文本内容（文本节点与源文本逐字相同）。
 */

/** 行内代码：内容原样保留，不解释任何标记。 */
export interface MarkdownCodeNode {
  text: string;
  type: 'code';
}

/** 强调（`*文本*`）。 */
export interface MarkdownEmphasisNode {
  children: MarkdownInline[];
  type: 'emphasis';
}

/** 链接（仅 `http`/`https` 且通过 Origin 校验）。 */
export interface MarkdownLinkNode {
  children: MarkdownInline[];
  href: string;
  type: 'link';
}

/** 加粗（`**文本**`）。 */
export interface MarkdownStrongNode {
  children: MarkdownInline[];
  type: 'strong';
}

/** 纯文本（任何未被识别的字符都在这里，包括看起来像 HTML 的片段）。 */
export interface MarkdownTextNode {
  text: string;
  type: 'text';
}

/** 行内节点（判别联合）。 */
export type MarkdownInline =
  | MarkdownCodeNode
  | MarkdownEmphasisNode
  | MarkdownLinkNode
  | MarkdownStrongNode
  | MarkdownTextNode;

/** 引用块（`> 文本`）。 */
export interface MarkdownBlockquoteNode {
  children: MarkdownInline[];
  type: 'blockquote';
}

/** 围栏代码块（``` 包裹）：内容不参与 Markdown 解析。 */
export interface MarkdownCodeBlockNode {
  code: string;
  language: string;
  type: 'codeBlock';
}

/** 标题（`#`…`######`）。 */
export interface MarkdownHeadingNode {
  children: MarkdownInline[];
  level: number;
  type: 'heading';
}

/** 列表（`-`/`*` 或 `1.`）：每项是一段行内序列，不支持子列表（避免嵌套放大）。 */
export interface MarkdownListNode {
  items: MarkdownInline[][];
  ordered: boolean;
  type: 'list';
}

/** 段落。 */
export interface MarkdownParagraphNode {
  children: MarkdownInline[];
  type: 'paragraph';
}

/** 块级节点（判别联合）。 */
export type MarkdownNode =
  | MarkdownBlockquoteNode
  | MarkdownCodeBlockNode
  | MarkdownHeadingNode
  | MarkdownListNode
  | MarkdownParagraphNode;

/** 解析选项。 */
export interface MarkdownParseOptions {
  /** 允许的链接 Origin（非空时逐条比对；为空表示"任意 http/https"）。 */
  allowedOrigins?: string[];
  /** 处理的行数上限（默认 1000），超出部分截断而不报错。 */
  maxLines?: number;
}

/** 允许的链接协议：其余（`javascript:`/`data:`/`file:`/`blob:`…）一律拒绝。 */
export const ALLOWED_LINK_PROTOCOLS = ['http:', 'https:'];

/** 行内文本上限（与冻结 text 块上限一致）。 */
export const MAX_INLINE_LENGTH = 20_000;

/** 代码块内容上限。 */
export const MAX_CODE_LENGTH = 20_000;

/** 行内嵌套深度上限（`**a**` 里再套 `_` 也不会无限展开）。 */
export const MAX_INLINE_DEPTH = 8;

const MAX_CODE_LINES = 2000;
const MAX_HEADING_LEVEL = 6;
const MAX_URL_LENGTH = 2048;
const DEFAULT_MAX_LINES = 1000;

/** URL 里不允许出现的字符：尖括号与反斜杠（控制字符用字符扫描判断，见 {@link hasForbiddenUrlChar}）。 */
const FORBIDDEN_URL_CHARACTERS = new Set(['<', '>', '\\', '`']);
const HEADING_PATTERN = /^ {0,3}(#{1,6})[ \t]+(\S[^\n]*)?$/u;
const BLOCKQUOTE_PATTERN = /^ {0,3}>[ \t]*(\S[^\n]*)?$/u;
const LIST_ITEM_PATTERN = /^ {0,3}([-*]|\d{1,3}[.)])[ \t]+(\S[^\n]*)?$/u;
// 链接地址允许"不跨空白、最多一层成对括号"的字符（`.../X_(Y)` 这类地址合法；
// `javascript:alert(1)` 也能被捕获，随后在协议校验里被拒——不匹配反而会把它当普通文本留在正文里）
const INLINE_PATTERN =
  /\*\*[^*\n]+\*\*|\*[^*\n]+\*|`[^`\n]*`|\[[^\]\n]*\]\((?:[^()\s]|\([^()\s]*\))*\)/u;
const LINK_PATTERN = /^\[([^\]]*)\]\(((?:[^()\s]|\([^()\s]*\))*)\)$/u;
const LANGUAGE_PATTERN = /^[\w+-]{0,32}$/u;

/** 去掉控制字符（含 DEL），其它字符原样保留。 */
export function stripControlCharacters(value: string): string {
  let cleaned = '';
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (code >= 32 && code !== 127) {
      cleaned += character;
    }
  }
  return cleaned;
}

/** 是否含控制字符（含 DEL）：用字符码判断，避免用正则表达"不可见字符"。 */
export function hasControlCharacter(value: string): boolean {
  return stripControlCharacters(value).length !== value.length;
}

/** 列表标记是否为有序（`1.`/`1)`）；`-`/`*` 为无序。 */
function isOrderedMarker(marker: string): boolean {
  return marker.length > 0 && !marker.startsWith('-') && marker !== '*';
}

/** URL 是否含禁止字符（控制字符、尖括号、反斜杠、反引号）。 */
function hasForbiddenUrlChar(value: string): boolean {
  if (hasControlCharacter(value)) {
    return true;
  }
  for (const character of value) {
    if (FORBIDDEN_URL_CHARACTERS.has(character)) {
      return true;
    }
  }
  return false;
}

/**
 * 围栏行识别：整体是 ```` ``` ```` 加可选的语言标识（单词字符，≤32）。
 *
 * <p>用字符串切分而不是正则：`^\s*```\s*(lang)?\s*$` 这类写法里两个 `\s*` 可交换字符，
 * 会在长空白串上退化成多项式回溯——而这里要处理的正是外部文本。
 */
function fenceLanguageOf(line: string): null | string {
  const trimmed = line.trimStart();
  if (!trimmed.startsWith('```')) {
    return null;
  }
  const rest = trimmed.slice(3).trim();
  return LANGUAGE_PATTERN.test(rest) ? rest : null;
}

/**
 * 链接是否可用：必须是绝对 `http`/`https` 地址；配置了允许 Origin 时还需命中白名单。
 *
 * <p>先做字符级拒绝再交给 URL 解析：`new URL` 会静默去掉制表符与换行，
 * 于是 `java\tscript:alert(1)` 会被解析成 `javascript:` 协议——这里的字符级检查先把这类输入挡掉。
 */
export function isAllowedLinkUrl(
  value: string,
  allowedOrigins: string[] = [],
): boolean {
  if (typeof value !== 'string' || value.length === 0) {
    return false;
  }
  if (value.length > MAX_URL_LENGTH || hasForbiddenUrlChar(value)) {
    return false;
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    // 相对地址、协议相对地址（//host）与非法地址：没有可信基准，一律拒绝
    return false;
  }
  if (!ALLOWED_LINK_PROTOCOLS.includes(url.protocol)) {
    return false;
  }
  if (allowedOrigins.length === 0) {
    return true;
  }
  return allowedOrigins.includes(url.origin);
}

/** 行内节点 → 纯文本（用于降级展示与标题文本）。 */
export function markdownText(nodes: MarkdownInline[]): string {
  return nodes
    .map((node) => {
      switch (node.type) {
        case 'code': {
          return node.text;
        }
        case 'text': {
          return node.text;
        }
        default: {
          return markdownText(node.children);
        }
      }
    })
    .join('');
}

/**
 * 解析行内文本为允许节点。
 *
 * <p>未通过校验的链接**保留标签文本、丢弃地址**：链接文字是内容，地址是风险面。
 */
export function parseMarkdownInline(
  source: string,
  allowedOrigins: string[] = [],
  depth = 0,
): MarkdownInline[] {
  const text =
    typeof source === 'string' ? source.slice(0, MAX_INLINE_LENGTH) : '';
  const nodes: MarkdownInline[] = [];
  let rest = text;
  while (rest.length > 0) {
    const match = INLINE_PATTERN.exec(rest);
    if (!match || match.index === undefined) {
      break;
    }
    if (match.index > 0) {
      nodes.push({ text: rest.slice(0, match.index), type: 'text' });
    }
    const token = match[0];
    rest = rest.slice(match.index + token.length);
    if (token.startsWith('**')) {
      const inner = token.slice(2, -2);
      nodes.push({
        children: nested(inner, allowedOrigins, depth),
        type: 'strong',
      });
      continue;
    }
    if (token.startsWith('*')) {
      const inner = token.slice(1, -1);
      nodes.push({
        children: nested(inner, allowedOrigins, depth),
        type: 'emphasis',
      });
      continue;
    }
    if (token.startsWith('`')) {
      nodes.push({ text: token.slice(1, -1), type: 'code' });
      continue;
    }
    const link = LINK_PATTERN.exec(token);
    const label = link?.[1] ?? '';
    const href = link?.[2] ?? '';
    const children = nested(label, allowedOrigins, depth);
    if (isAllowedLinkUrl(href, allowedOrigins)) {
      nodes.push({ children, href, type: 'link' });
    } else {
      // 地址不可用：只保留标签文本（丢弃地址，不引入任何可执行面）
      nodes.push({ text: markdownText(children), type: 'text' });
    }
  }
  if (rest.length > 0) {
    nodes.push({ text: rest, type: 'text' });
  }
  return nodes;
}

function nested(
  source: string,
  allowedOrigins: string[],
  depth: number,
): MarkdownInline[] {
  if (depth >= MAX_INLINE_DEPTH) {
    return [{ text: source, type: 'text' }];
  }
  return parseMarkdownInline(source, allowedOrigins, depth + 1);
}

/**
 * 解析受控 Markdown 文本为块级节点。
 *
 * <p>围栏代码块内的内容**不参与解析**（模型给的示例代码里出现 `#` 或 `*` 不会被当成标题/列表）；
 * 结束围栏缺失时按"到文末"处理，不抛异常。
 */
export function parseMarkdown(
  source: string,
  options: MarkdownParseOptions = {},
): MarkdownNode[] {
  const allowedOrigins = options.allowedOrigins ?? [];
  const maxLines = options.maxLines ?? DEFAULT_MAX_LINES;
  const lines =
    typeof source === 'string'
      ? source.replaceAll('\r\n', '\n').replaceAll('\r', '\n').split('\n')
      : [];
  const nodes: MarkdownNode[] = [];
  let index = 0;
  while (index < lines.length && index < maxLines) {
    const line = lines[index] ?? '';
    if (line.trim().length === 0) {
      index += 1;
      continue;
    }
    const language = fenceLanguageOf(line);
    if (language !== null) {
      const buffer: string[] = [];
      index += 1;
      // 所有内层循环都受 maxLines 约束：上限是"处理预算"，不能只在最外层生效
      while (
        index < maxLines &&
        index < lines.length &&
        fenceLanguageOf(lines[index] ?? '') === null
      ) {
        if (buffer.length < MAX_CODE_LINES) {
          buffer.push(lines[index] ?? '');
        }
        index += 1;
      }
      index += 1;
      nodes.push({
        code: buffer.join('\n').slice(0, MAX_CODE_LENGTH),
        language,
        type: 'codeBlock',
      });
      continue;
    }
    const heading = HEADING_PATTERN.exec(line);
    if (heading) {
      nodes.push({
        children: parseMarkdownInline(heading[2] ?? '', allowedOrigins),
        level: Math.min((heading[1] ?? '#').length, MAX_HEADING_LEVEL),
        type: 'heading',
      });
      index += 1;
      continue;
    }
    if (BLOCKQUOTE_PATTERN.test(line)) {
      const buffer: string[] = [];
      while (
        index < maxLines &&
        index < lines.length &&
        BLOCKQUOTE_PATTERN.test(lines[index] ?? '')
      ) {
        buffer.push(BLOCKQUOTE_PATTERN.exec(lines[index] ?? '')?.[1] ?? '');
        index += 1;
      }
      nodes.push({
        children: parseMarkdownInline(buffer.join(' '), allowedOrigins),
        type: 'blockquote',
      });
      continue;
    }
    const item = LIST_ITEM_PATTERN.exec(line);
    if (item) {
      const ordered = isOrderedMarker(item[1] ?? '');
      const items: MarkdownInline[][] = [];
      while (index < maxLines && index < lines.length) {
        const current = LIST_ITEM_PATTERN.exec(lines[index] ?? '');
        if (!current || isOrderedMarker(current[1] ?? '') !== ordered) {
          break;
        }
        items.push(parseMarkdownInline(current[2] ?? '', allowedOrigins));
        index += 1;
      }
      nodes.push({ items, ordered, type: 'list' });
      continue;
    }
    const buffer: string[] = [];
    while (index < maxLines && index < lines.length) {
      const current = lines[index] ?? '';
      if (
        current.trim().length === 0 ||
        fenceLanguageOf(current) !== null ||
        HEADING_PATTERN.test(current) ||
        BLOCKQUOTE_PATTERN.test(current) ||
        LIST_ITEM_PATTERN.test(current)
      ) {
        break;
      }
      buffer.push(current.trim());
      index += 1;
    }
    nodes.push({
      children: parseMarkdownInline(buffer.join(' '), allowedOrigins),
      type: 'paragraph',
    });
  }
  return nodes;
}
