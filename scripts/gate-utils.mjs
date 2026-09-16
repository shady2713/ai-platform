/**
 * 仓库级门禁的共享工具。
 *
 * <p>只收纳**被逐字或语义验证为等价**的实现：抽取前逐个比对过各副本，
 * 同名但语义不同的函数（如 javaSources 的返回结构、readBaseline 的 schema）
 * 不在此列，它们留在各自门禁中，避免把"看起来像"当成"是同一个"。
 *
 * <p>共享的目的是**单点修改**：修正一次，全部门禁同时生效。
 */
import { existsSync, readdirSync } from 'node:fs';
import { join, sep } from 'node:path';

export function normalizePath(value) {
  return value.split(sep).join('/');
}

export function findFilesBySuffix(root, suffix) {
  if (!existsSync(root)) {
    return [];
  }
  const result = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const target = join(root, entry.name);
    if (entry.isDirectory()) {
      result.push(...findFilesBySuffix(target, suffix));
    } else if (entry.isFile() && entry.name.endsWith(suffix)) {
      result.push(target);
    }
  }
  return result;
}

export function matchingParenthesis(source, openingIndex) {
  let depth = 0;
  for (let index = openingIndex; index < source.length; index += 1) {
    if (source[index] === '(') {
      depth += 1;
    } else if (source[index] === ')') {
      depth -= 1;
      if (depth === 0) {
        return index;
      }
    }
  }
  return -1;
}

export function parseYaml(text) {
  const lines = [];
  for (const raw of text.split(/\r?\n/)) {
    if (raw.includes('\t')) {
      throw new Error('YAML 缩进不允许使用 Tab');
    }
    const content = raw.trim();
    if (!content || content.startsWith('#')) {
      continue;
    }
    const indent = raw.length - raw.trimStart().length;
    lines.push({ indent, content: raw.slice(indent).trimEnd() });
  }

  let pos = 0;

  function peek() {
    return lines[pos];
  }

  function parseBlock(indent) {
    const line = peek();
    if (!line || line.indent < indent) {
      return null;
    }
    if (line.indent !== indent) {
      throw new Error(`YAML 缩进错误：${line.content}`);
    }
    return line.content === '-' || line.content.startsWith('- ')
      ? parseSeq(indent)
      : parseMap(indent);
  }

  function parseMap(indent) {
    const map = {};
    while (pos < lines.length) {
      const line = peek();
      if (line.indent < indent || line.content === '-' || line.content.startsWith('- ')) {
        break;
      }
      if (line.indent > indent) {
        throw new Error(`YAML 缩进错误：${line.content}`);
      }
      const match = /^([A-Za-z0-9_]+):(?:\s+(.*))?$/.exec(line.content);
      if (!match) {
        throw new Error(`无法解析的映射行：${line.content}`);
      }
      pos += 1;
      if (match[2] !== undefined && match[2] !== '') {
        map[match[1]] = parseScalar(match[2]);
      } else {
        const next = peek();
        map[match[1]] = next && next.indent > indent ? parseBlock(next.indent) : null;
      }
    }
    return map;
  }

  function parseSeq(indent) {
    const seq = [];
    while (pos < lines.length) {
      const line = peek();
      if (line.indent !== indent || !(line.content === '-' || line.content.startsWith('- '))) {
        break;
      }
      if (line.content === '-') {
        pos += 1;
        const next = peek();
        seq.push(next && next.indent > indent ? parseBlock(next.indent) : null);
        continue;
      }
      const rest = line.content.slice(2);
      if (/^[A-Za-z0-9_]+:(\s|$)/.test(rest)) {
        lines[pos] = { indent: indent + 2, content: rest };
        seq.push(parseMap(indent + 2));
      } else {
        pos += 1;
        seq.push(parseScalar(rest));
      }
    }
    return seq;
  }

  function parseScalar(raw) {
    if (raw.startsWith('[')) {
      if (!raw.endsWith(']')) {
        throw new Error(`flow 序列缺少闭合括号：${raw}`);
      }
      const inner = raw.slice(1, -1).trim();
      if (!inner) {
        return [];
      }
      return splitFlow(inner).map(parseScalar);
    }
    if (raw.startsWith("'")) {
      return parseSingleQuoted(raw);
    }
    if (raw.startsWith('"')) {
      return parseDoubleQuoted(raw);
    }
    const plain = raw.replace(/\s+#.*$/, '').trim();
    if (plain === 'null' || plain === '~') {
      return null;
    }
    if (plain === 'true') {
      return true;
    }
    if (plain === 'false') {
      return false;
    }
    if (/^-?\d+$/.test(plain)) {
      return Number.parseInt(plain, 10);
    }
    return plain;
  }

  function splitFlow(inner) {
    const parts = [];
    let current = '';
    let quote = null;
    for (let i = 0; i < inner.length; i += 1) {
      const ch = inner[i];
      if (quote) {
        current += ch;
        if (ch === quote) {
          if (quote === "'" && inner[i + 1] === "'") {
            current += inner[i + 1];
            i += 1;
          } else {
            quote = null;
          }
        }
        continue;
      }
      if (ch === "'" || ch === '"') {
        quote = ch;
        current += ch;
        continue;
      }
      if (ch === ',') {
        parts.push(current.trim());
        current = '';
        continue;
      }
      current += ch;
    }
    parts.push(current.trim());
    return parts;
  }

  function parseSingleQuoted(raw) {
    let value = '';
    let i = 1;
    for (; i < raw.length; i += 1) {
      if (raw[i] === "'") {
        if (raw[i + 1] === "'") {
          value += "'";
          i += 1;
          continue;
        }
        break;
      }
      value += raw[i];
    }
    if (i >= raw.length) {
      throw new Error(`单引号标量未闭合：${raw}`);
    }
    const rest = raw.slice(i + 1).trim();
    if (rest && !rest.startsWith('#')) {
      throw new Error(`单引号标量闭合后存在多余内容：${raw}`);
    }
    return value;
  }

  function parseDoubleQuoted(raw) {
    let value = '';
    let i = 1;
    for (; i < raw.length; i += 1) {
      const ch = raw[i];
      if (ch === '"') {
        break;
      }
      if (ch === '\\') {
        i += 1;
        const esc = raw[i];
        if (esc === 'n') value += '\n';
        else if (esc === 't') value += '\t';
        else if (esc === 'r') value += '\r';
        else value += esc;
        continue;
      }
      value += ch;
    }
    if (i >= raw.length) {
      throw new Error(`双引号标量未闭合：${raw}`);
    }
    const rest = raw.slice(i + 1).trim();
    if (rest && !rest.startsWith('#')) {
      throw new Error(`双引号标量闭合后存在多余内容：${raw}`);
    }
    return value;
  }

  return parseBlock(0);
}
