/**
 * SSE 帧解析（C01）：把字节流解析成帧，**多字节安全**且**跨块截断安全**。
 *
 * <p>为什么不用 `EventSource`：平台开放 API 需要 Bearer 票据（`EventSource` 不能带自定义头），
 * 且断线后要按 `afterSeq` 重连而不是重执行；因此用 fetch + 本解析器。
 *
 * <p>解析口径（与 `docs/integrations/open-api/ai-open-api.json` 的示例一致）：
 * <ul>
 *   <li>帧以空行结束（`\n\n` 或 `\r\n\r\n`），未结束的部分留在缓冲区等下一块（事件截断不会丢）；</li>
 *   <li>多行 `data:` 用换行拼接；`id:`/`event:` 原样保留；</li>
 *   <li>注释行（`:` 开头，例如心跳 `: heartbeat`）只记入 `comments`，**不是事件**；</li>
 *   <li>解码用 `TextDecoder({stream:true})`：UTF-8 字符被切在两个字节块之间也不会乱码。</li>
 * </ul>
 */

/** 一帧 SSE：data（多行拼接）+ 可选 id/event + 注释行。 */
export interface SseFrame {
  comments: string[];
  data: string;
  event?: string;
  id?: string;
}

/** 解析器：`push` 一段文本，返回本次能确定的帧（不完整帧留在内部缓冲）。 */
export interface SseFrameParser {
  /** 流结束时调用：把缓冲区里"没有以空行结束"的最后一帧也交出来（服务端正常收尾）。 */
  flush(): SseFrame[];
  push(chunk: string): SseFrame[];
}

function toFrame(lines: string[]): null | SseFrame {
  const data: string[] = [];
  const comments: string[] = [];
  let event: string | undefined;
  let id: string | undefined;
  for (const line of lines) {
    if (line.startsWith(':')) {
      comments.push(line.slice(1).trim());
      continue;
    }
    const separator = line.indexOf(':');
    const field = separator === -1 ? line : line.slice(0, separator);
    // 规范：冒号后的一个空格是分隔符的一部分
    let value = separator === -1 ? '' : line.slice(separator + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    switch (field) {
      case 'data': {
        data.push(value);

        break;
      }
      case 'event': {
        event = value;

        break;
      }
      case 'id': {
        id = value;

        break;
      }
      // No default
    }
  }
  if (
    data.length === 0 &&
    comments.length === 0 &&
    event === undefined &&
    id === undefined
  ) {
    return null;
  }
  return {
    comments,
    data: data.join('\n'),
    ...(event === undefined ? {} : { event }),
    ...(id === undefined ? {} : { id }),
  };
}

/** 文本帧解析器（不完整帧留在缓冲）。 */
export function createSseFrameParser(): SseFrameParser {
  let buffer = '';

  function drain(final: boolean): SseFrame[] {
    const frames: SseFrame[] = [];
    let separatorIndex = buffer.search(/\r?\n\r?\n/);
    while (separatorIndex !== -1) {
      const match =
        /\r?\n\r?\n/.exec(buffer.slice(separatorIndex))?.[0] ?? '\n\n';
      const raw = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + match.length);
      const frame = toFrame(raw.split(/\r?\n/));
      if (frame) {
        frames.push(frame);
      }
      separatorIndex = buffer.search(/\r?\n\r?\n/);
    }
    if (final && buffer.trim().length > 0) {
      const frame = toFrame(buffer.split(/\r?\n/));
      buffer = '';
      if (frame) {
        frames.push(frame);
      }
    }
    return frames;
  }

  return {
    flush: () => drain(true),
    push: (chunk: string) => {
      buffer += chunk;
      return drain(false);
    },
  };
}

/** 字节流 → 帧的解析器（多字节安全）。 */
export function createSseByteParser(): {
  flush: () => SseFrame[];
  push: (bytes: Uint8Array) => SseFrame[];
} {
  const decoder = new TextDecoder('utf-8');
  const parser = createSseFrameParser();
  return {
    flush: () => [...parser.push(decoder.decode()), ...parser.flush()],
    push: (bytes: Uint8Array) =>
      parser.push(decoder.decode(bytes, { stream: true })),
  };
}
