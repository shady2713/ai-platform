import { describe, expect, it } from 'vitest';

import {
  AiOpenApiError,
  createSeqTracker,
  parseCommonResult,
} from '../protocol';
import { createSseByteParser, createSseFrameParser } from '../sse';

describe('commonResult 信封', () => {
  it('成功返回 data；业务失败/HTTP 失败/缺 data 都抛稳定错误', () => {
    expect(
      parseCommonResult<{ id: number }>(200, { code: 0, data: { id: 7 } }),
    ).toEqual({ id: 7 });

    try {
      parseCommonResult(200, { code: 1_003_004_001, msg: '运行不存在' });
    } catch (error) {
      expect((error as AiOpenApiError).code).toBe('1003004001');
      expect((error as AiOpenApiError).status).toBe(200);
      expect((error as AiOpenApiError).message).toBe('运行不存在');
    }

    try {
      parseCommonResult(401, { code: 1_003_003_001, msg: '未认证' });
    } catch (error) {
      expect((error as AiOpenApiError).status).toBe(401);
      expect((error as AiOpenApiError).code).toBe('1003003001');
    }

    try {
      parseCommonResult(502, '<html>bad gateway</html>');
    } catch (error) {
      expect((error as AiOpenApiError).code).toBe('HTTP_502');
    }

    try {
      parseCommonResult(200, { code: 0 });
    } catch (error) {
      expect((error as AiOpenApiError).code).toBe('EMPTY_DATA');
    }

    try {
      parseCommonResult(200, { data: { id: 1 } });
    } catch (error) {
      expect((error as AiOpenApiError).code).toBe('MALFORMED_RESPONSE');
    }
  });
});

describe('seq 去重', () => {
  it('只接受严格递增；重复与乱序丢弃，lastSeq 用于重连', () => {
    const tracker = createSeqTracker(5);
    expect(tracker.lastSeq()).toBe(5);
    expect(tracker.accept(6)).toBe(true);
    expect(tracker.accept(6)).toBe(false);
    expect(tracker.accept(4)).toBe(false);
    expect(tracker.accept(Number.NaN)).toBe(false);
    expect(tracker.accept(9)).toBe(true);
    expect(tracker.lastSeq()).toBe(9);
  });
});

describe('sSE 帧解析', () => {
  it('解析 data/event/id 与注释心跳；跨块截断不丢帧', () => {
    const parser = createSseFrameParser();
    const frames = parser.push(
      'id: 1\nevent: run\ndata: {"seq":1}\n\n: heartbeat\n\n',
    );
    expect(frames).toHaveLength(2);
    expect(frames[0]).toMatchObject({
      data: '{"seq":1}',
      event: 'run',
      id: '1',
    });
    expect(frames[1]?.comments).toEqual(['heartbeat']);
    expect(frames[1]?.data).toBe('');

    const truncated = createSseFrameParser();
    expect(truncated.push('data: {"seq":')).toHaveLength(0);
    const completed = truncated.push('2}\n\n');
    expect(completed).toHaveLength(1);
    expect(completed[0]?.data).toBe('{"seq":2}');
  });

  it('多行 data 拼接、CRLF 分隔、flush 交出未收尾的最后一帧', () => {
    const parser = createSseFrameParser();
    const frames = parser.push('data: line1\r\ndata: line2\r\n\r\n');
    expect(frames[0]?.data).toBe('line1\nline2');

    const tail = createSseFrameParser();
    expect(tail.push('data: last')).toHaveLength(0);
    expect(tail.flush()[0]?.data).toBe('last');
  });

  it('字节解析器多字节安全（UTF-8 字符被切在两块之间也不乱码）', () => {
    const parser = createSseByteParser();
    const payload = new TextEncoder().encode('data: {"title":"华东"}\n\n');
    const split = payload.length - 3;
    const first = parser.push(payload.slice(0, split));
    const second = parser.push(payload.slice(split));
    const frames = [...first, ...second];
    expect(frames).toHaveLength(1);
    expect(JSON.parse(frames[0]?.data ?? '{}')).toEqual({ title: '华东' });
  });
});
