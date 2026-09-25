import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import process from 'node:process';

import {
  BRIDGE_MESSAGE_TYPES,
  BRIDGE_PROTOCOL_VERSION,
  parseBridgeMessage,
} from '@vben/ai-contracts';

import { describe, expect, it, vi } from 'vitest';

import { createHostBridge } from '../bridge/host-bridge';

/**
 * N-1 基线兼容（C10 / FR-34）。
 *
 * <p>首次发布没有真实的历史 SDK 产物，因此按 FR-34 的口径**冻结首发候选的协议行为**：
 * 夹具记录首发版本的协议版本、消息类型白名单与一段完整握手序列。升级 SDK 或协议时，
 * 必须重放本夹具并证明新实现仍然接受这些消息——这是"N-1 兼容"在首发阶段的等价物。
 */
interface Baseline {
  artifact: string;
  artifactSha256: string;
  frozenAt: string;
  handshake: unknown[];
  messageTypes: string[];
  protocolVersion: string;
  sdkVersion: string;
}

/** 从工作目录向上找到夹具（与 ai-contracts 的跨语言夹具测试同一口径）。 */
function findBaseline(): string {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = join(
      dir,
      'packages/ai-embed-sdk/fixtures/n-1/baseline.json',
    );
    if (existsSync(candidate)) {
      return candidate;
    }
    dir = dirname(dir);
  }
  throw new Error(
    '未找到 N-1 基线夹具：packages/ai-embed-sdk/fixtures/n-1/baseline.json',
  );
}

const baseline = JSON.parse(readFileSync(findBaseline(), 'utf8')) as Baseline;

describe('n-1 基线兼容夹具（C10）', () => {
  it('基线夹具本身完整（冻结时记录了版本、哈希与握手序列）', () => {
    expect(baseline.sdkVersion).toBe('5.6.0');
    expect(baseline.protocolVersion).toBe('1.0');
    expect(baseline.artifact).toMatch(/^ai-embed-sdk-\d+\.\d+\.\d+\.js$/u);
    expect(baseline.artifactSha256).toMatch(/^[0-9a-f]{64}$/u);
    expect(baseline.handshake.length).toBeGreaterThanOrEqual(4);
  });

  it('当前协议仍接受基线的全部消息类型（白名单不缩水）', () => {
    for (const type of baseline.messageTypes) {
      expect(BRIDGE_MESSAGE_TYPES).toContain(type);
    }
    // 协议主版本不变：主版本变化必须走升级流程并重新冻结基线
    expect(BRIDGE_PROTOCOL_VERSION).toBe(baseline.protocolVersion);
  });

  it('重放基线握手序列：当前实现仍然解析每条消息并走到 INITIALIZED', async () => {
    const sent: unknown[] = [];
    const frame = { name: 'baseline-frame' };
    const bridge = createHostBridge({
      allowedOrigins: ['https://crm.example.com'],
      appCode: 'crm-portal',
      getAccessToken: async () => ({
        expiresAt: '2026-09-25T10:00:00Z',
        token: 'aitkt_once_abcdefg',
      }),
      instanceId: 'inst-1',
      serviceId: 'svc_1',
      transport: {
        destroy: () => undefined,
        post: (message) => sent.push(message),
        source: () => frame,
      },
    });
    bridge.start();

    // 基线里的 HELLO 是宿主发出的：逐条比对当前实现发出的形状
    const [hello, ready] = baseline.handshake as [
      Record<string, unknown>,
      Record<string, unknown>,
    ];
    expect(sent[0]).toMatchObject({
      appCode: hello.appCode,
      instanceId: hello.instanceId,
      protocolVersion: hello.protocolVersion,
      type: 'HELLO',
    });
    for (const message of baseline.handshake) {
      // 每条基线消息都必须能被当前契约解析（N-1 消息不会被新实现拒绝）
      expect(() => parseBridgeMessage(message)).not.toThrow();
    }

    expect(
      bridge.receive({
        data: ready,
        origin: 'https://crm.example.com',
        source: frame,
      }),
    ).toBe(true);
    await vi.waitFor(() => expect(bridge.state()).toBe('INITIALIZED'));
    expect(sent.map((message) => (message as { type: string }).type)).toContain(
      'AUTH',
    );
  });
});
