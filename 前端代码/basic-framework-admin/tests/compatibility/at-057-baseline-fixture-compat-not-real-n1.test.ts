/**
 * AT-057（Q08 前端切片）：N-1 协议兼容回归 —— **基线兼容夹具验证，不是真实 N-1 产物联调**。
 *
 * 口径（必须与文件同名注释一起保留）：
 *  - 首发（C10）没有历史版本 SDK 产物：`packages/ai-embed-sdk/dist/` 只有当前版本一个产物，
 *    因此"真实旧 SDK 连新后端"在本环境**无法执行**（属未验证项）；
 *  - 首发阶段"N-1 兼容"的等价物是：冻结首发候选的协议行为，每次升级重放并证明新实现仍接受旧消息；
 *  - 本用例独立复核 `packages/ai-embed-sdk/fixtures/n-1/baseline.json` 的语义，并补强三件事：
 *    1. **字段只增不减**：冻结握手的每条消息、以及 ERROR/DESTROY 最小集，当前契约必须逐键接受
 *       （可选字段可以缺省；必填字段缺一不可，未知字段仍必须拒绝）；
 *    2. **版本协商规则不变**：同版本 / 接收方高一个小版本（N-1）接受，发送方更高、主版本变化、
 *       越界的旧版本、非法格式一律拒绝，且运行时行为与纯函数一致；
 *    3. **产物 digest 漂移必须登记**：夹具记录的冻结 digest 与当前产物不一致时，
 *       必须在 `tests/compatibility/fixtures/at-057/artifact-digest-ledger.json` 里写明原因。
 *
 * 断言对象是只读引用：`packages/ai-embed-sdk`（基线夹具与产物）与 `packages/ai-contracts`（冻结契约）。
 */
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it, vi } from 'vitest';

import {
  BRIDGE_MESSAGE_TYPES,
  BRIDGE_PROTOCOL_VERSION,
  isCompatibleProtocolVersion,
  parseBridgeMessage,
} from '../../packages/ai-contracts/src/index';
import { createHostBridge } from '../../packages/ai-embed-sdk/src/bridge/host-bridge';
import { frontendRoot, readJsonFile } from './support/workspace';

interface BaselineFixture {
  artifact: string;
  artifactSha256: string;
  frozenAt: string;
  handshake: Record<string, unknown>[];
  messageTypes: string[];
  note: string;
  protocolVersion: string;
  sdkVersion: string;
}

interface DigestLedger {
  entries: { digest: string; reason: string; recordedBy: string }[];
}

const FRONTEND_ROOT = frontendRoot();
const SDK_DIR = join(FRONTEND_ROOT, 'packages/ai-embed-sdk');

const baseline = readJsonFile<BaselineFixture>(
  join(SDK_DIR, 'fixtures/n-1/baseline.json'),
);

/**
 * 冻结时点（C06/C10）的 13 类消息白名单：**独立于夹具与当前实现**重抄一份，
 * 用来同时检查"夹具没有被悄悄改小"和"当前契约没有缩水"。
 */
const FROZEN_MESSAGE_TYPES = [
  'AUTH',
  'CLOSE',
  'CONTEXT_UPDATE',
  'DESTROY',
  'ERROR',
  'HELLO',
  'INIT',
  'NAVIGATE_REQUEST',
  'OPEN',
  'READY',
  'REPORT_CREATED',
  'THEME_UPDATE',
  'TOKEN_REQUIRED',
];

/**
 * baseline.json 只逐条保存了 HELLO/READY/AUTH/INIT 的握手样例；
 * ERROR/DESTROY（以及续票用的 TOKEN_REQUIRED）按**冻结契约的最小形状**补齐：
 * 必填字段取自 `packages/ai-contracts/src/bridge.ts` 的 strict schema，不新增字段语义。
 */
const FROZEN_MINIMAL_SAMPLES: Record<string, Record<string, unknown>> = {
  ERROR: {
    errorCode: 'PROTOCOL_VERSION_UNSUPPORTED',
    instanceId: 'inst-1',
    message: '桥协议版本不受支持',
    protocolVersion: '1.0',
    type: 'ERROR',
  },
  DESTROY: {
    instanceId: 'inst-1',
    protocolVersion: '1.0',
    type: 'DESTROY',
  },
  TOKEN_REQUIRED: {
    instanceId: 'inst-1',
    protocolVersion: '1.0',
    reason: 'EXPIRED',
    type: 'TOKEN_REQUIRED',
  },
};

function sha256File(path: string): string {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

/**
 * 逐键比对：冻结消息的每个键都必须在当前解析结果里原样存在（只增不减）。
 *
 * `overrides` 只用于协议版本：接收方已升到 1.1 时，它发出的报文带的是**自己的**版本 1.1
 * （协商结果不影响字段集合），此时按接收方版本断言而不是按冻结报文断言。
 */
function expectFieldsPreserved(
  frozen: Record<string, unknown>,
  parsed: Record<string, unknown>,
  overrides: Record<string, unknown> = {},
): void {
  for (const [key, value] of Object.entries(frozen)) {
    expect(parsed, `字段 ${key}`).toHaveProperty(key);
    expect(parsed[key], `字段 ${key} 的值`).toStrictEqual(
      key in overrides ? overrides[key] : value,
    );
  }
}

/** 建一个可注入传输的宿主桥实例（与应用侧同一实现，不改任何产品代码）。 */
function createBridge(options: { protocolVersion?: string } = {}) {
  const posted: Record<string, unknown>[] = [];
  const frame = { name: 'q08-baseline-frame' };
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
      post: (message) => posted.push(message as Record<string, unknown>),
      source: () => frame,
    },
    ...(options.protocolVersion === undefined
      ? {}
      : { protocolVersion: options.protocolVersion }),
  });
  return { bridge, frame, posted };
}

describe('兼容基线（AT-057）：基线夹具验证，非真实 N-1 产物；首发无历史产物', () => {
  it('夹具语义自洽：首发基线口径、版本与白名单都没有被悄悄改小', () => {
    expect(baseline.note).toContain('首发基线');
    expect(baseline.sdkVersion).toBe(
      readJsonFile<{ version: string }>(join(SDK_DIR, 'package.json')).version,
    );
    expect(baseline.protocolVersion).toBe('1.0');
    expect(baseline.artifact).toBe(`ai-embed-sdk-${baseline.sdkVersion}.js`);
    // 独立复核：夹具白名单与冻结时点的 13 类逐项相等（顺序无关）
    expect([...baseline.messageTypes].toSorted()).toStrictEqual(
      [...FROZEN_MESSAGE_TYPES].toSorted(),
    );
    // 当前契约的字段白名单只增不减
    for (const type of FROZEN_MESSAGE_TYPES) {
      expect(BRIDGE_MESSAGE_TYPES).toContain(type);
    }
  });

  it('产物证据：只有当前版本一个产物（证明没有真实 N-1 产物可联调），digest 漂移已登记', () => {
    const distDir = join(SDK_DIR, 'dist');
    const artifacts = readdirSync(distDir)
      .filter((name) => /^ai-embed-sdk-\d+\.\d+\.\d+\.js$/u.test(name))
      .toSorted();
    // 若本断言变红：工作区里出现了历史版本产物，说明"首发基线夹具验证"的口径
    // 需要升级为"真实 N-1 双产物联调"（这正是 tripwire 的目的，不要直接放宽）。
    expect(artifacts).toStrictEqual([baseline.artifact]);

    const currentDigest = sha256File(join(distDir, baseline.artifact));
    const ledger = readJsonFile<DigestLedger>(
      join(import.meta.dirname, 'fixtures/at-057/artifact-digest-ledger.json'),
    );
    const recorded = ledger.entries.map((entry) => entry.digest);
    expect(recorded).toContain(baseline.artifactSha256);
    expect(recorded).toContain(currentDigest);
    for (const entry of ledger.entries) {
      // 任何漂移都必须写明原因与记录人（不写原因即失败）
      expect(entry.reason.length, `${entry.digest} 的原因`).toBeGreaterThan(20);
      expect(entry.recordedBy.length).toBeGreaterThan(10);
    }
  });

  it('冻结握手的每条消息仍被当前契约逐键接受（字段只增不减）', () => {
    expect(baseline.handshake.length).toBeGreaterThanOrEqual(4);
    for (const message of baseline.handshake) {
      const parsed = parseBridgeMessage(message) as unknown as Record<
        string,
        unknown
      >;
      expectFieldsPreserved(message, parsed);
      expect(parsed.protocolVersion).toBe(baseline.protocolVersion);
    }
  });

  it('错误与销毁类最小集（ERROR/DESTROY/TOKEN_REQUIRED）被接受；必填字段缺失或未知字段仍被拒绝', () => {
    for (const [type, sample] of Object.entries(FROZEN_MINIMAL_SAMPLES)) {
      const parsed = parseBridgeMessage(sample) as unknown as Record<
        string,
        unknown
      >;
      expect(parsed.type, type).toBe(type);
      expectFieldsPreserved(sample, parsed);
    }

    // 只增不减的另一面：必填字段不能被"新版本"删除
    const removals: [string, Record<string, unknown>][] = [
      ['HELLO 缺 appCode', { ...baseline.handshake[0], appCode: undefined }],
      ['AUTH 缺 token', { ...baseline.handshake[2], token: undefined }],
      [
        'ERROR 缺 errorCode',
        { ...FROZEN_MINIMAL_SAMPLES.ERROR, errorCode: undefined },
      ],
      [
        'DESTROY 缺 instanceId',
        { ...FROZEN_MINIMAL_SAMPLES.DESTROY, instanceId: undefined },
      ],
    ];
    for (const [label, sample] of removals) {
      expect(() => parseBridgeMessage(sample), label).toThrow();
    }
    // 未知字段仍然拒绝（严格键；不许"尽力解析"）
    expect(() =>
      parseBridgeMessage({ ...baseline.handshake[0], extra: true }),
    ).toThrow();
    // 可选字段缺省仍被接受（N-1 消息字段更少是允许的方向）
    expect(() =>
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        type: 'READY',
      }),
    ).not.toThrow();
    expect(() =>
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        type: 'INIT',
      }),
    ).not.toThrow();
  });

  it('版本协商规则不变：同版本/接收方高一个小版本接受，其余拒绝', () => {
    const cases: [string, string, boolean][] = [
      ['1.0', '1.0', true],
      // 接收方（第二个参数）比发送方高一个小版本：接受 N-1 发送方
      ['1.0', '1.1', true],
      // 发送方更高、主版本变化、低于接收方一个小版本以上、格式非法：一律拒绝
      ['1.1', '1.0', false],
      ['2.0', '1.0', false],
      ['1.0', '2.0', false],
      ['0.9', '1.0', false],
      ['1.0', '3.5', false],
      ['x.y', '1.0', false],
      ['1', '1.0', false],
    ];
    for (const [received, supported, expected] of cases) {
      expect(
        isCompatibleProtocolVersion(received, supported),
        `${received} → ${supported}`,
      ).toBe(expected);
    }
    expect(BRIDGE_PROTOCOL_VERSION).toBe(baseline.protocolVersion);
  });

  it('运行时与协商规则一致：接收方 1.1 接受基线 1.0 握手；基线 1.0 拒绝 2.0 且回 ERROR', async () => {
    // 方向一：接收方已升到 1.1，旧（N-1）发送方 1.0 的 READY 必须被接受并走完 AUTH/INIT
    const upgraded = createBridge({ protocolVersion: '1.1' });
    upgraded.bridge.start();
    expect(upgraded.posted[0]).toMatchObject({
      appCode: 'crm-portal',
      instanceId: 'inst-1',
      protocolVersion: '1.1',
      type: 'HELLO',
    });
    const ready = baseline.handshake[1] as Record<string, unknown>;
    expect(
      upgraded.bridge.receive({
        data: ready,
        origin: 'https://crm.example.com',
        source: upgraded.frame,
      }),
    ).toBe(true);
    await vi.waitFor(() => expect(upgraded.bridge.state()).toBe('INITIALIZED'));
    const upgradedTypes = upgraded.posted.map((message) => message.type);
    expect(upgradedTypes).toContain('AUTH');
    expect(upgradedTypes).toContain('INIT');
    // 逐键复核 AUTH：冻结 AUTH 的键在当前实现里原样保留
    const frozenAuth = baseline.handshake[2] as Record<string, unknown>;
    const currentAuth = upgraded.posted.find(
      (message) => message.type === 'AUTH',
    );
    if (!currentAuth) {
      throw new Error('当前实现没有发出 AUTH');
    }
    expectFieldsPreserved(frozenAuth, currentAuth, { protocolVersion: '1.1' });

    // 方向二：接收方仍是 1.0 时，主版本不同的消息被拒绝、回稳定错误码、且不发 AUTH
    const current = createBridge();
    current.bridge.start();
    expect(
      current.bridge.receive({
        data: { ...ready, protocolVersion: '2.0' },
        origin: 'https://crm.example.com',
        source: current.frame,
      }),
    ).toBe(false);
    expect(current.bridge.state()).toBe('WAITING_READY');
    expect(current.posted.map((message) => message.type)).not.toContain('AUTH');
    expect(
      current.posted.find((message) => message.type === 'ERROR'),
    ).toMatchObject({
      errorCode: 'PROTOCOL_VERSION_UNSUPPORTED',
      type: 'ERROR',
    });
  });
});
