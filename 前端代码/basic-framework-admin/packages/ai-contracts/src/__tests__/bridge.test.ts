import { describe, expect, it } from 'vitest';

import {
  BRIDGE_MESSAGE_TYPES,
  BRIDGE_PROTOCOL_VERSION,
  isCompatibleProtocolVersion,
  parseBridgeMessage,
} from '../bridge';

const HELLO = {
  appCode: 'crm-portal',
  instanceId: 'inst-1',
  protocolVersion: '1.0',
  type: 'HELLO',
};

describe('桥协议契约（C06）', () => {
  it('接受白名单内的消息并按判别联合解析', () => {
    expect(parseBridgeMessage(HELLO).type).toBe('HELLO');
    expect(
      parseBridgeMessage({
        expiresAt: '2026-09-25T10:00:00Z',
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        token: 'aitkt_once_abcdefg',
        type: 'AUTH',
      }).type,
    ).toBe('AUTH');
    expect(
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        type: 'READY',
      }).type,
    ).toBe('READY');
    expect(
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        serviceId: 'svc_1',
        type: 'INIT',
      }).type,
    ).toBe('INIT');
    expect(
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        type: 'DESTROY',
      }).type,
    ).toBe('DESTROY');
    expect(
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        reason: 'EXPIRED',
        type: 'TOKEN_REQUIRED',
      }).type,
    ).toBe('TOKEN_REQUIRED');
  });

  it('拒绝未知类型、多余字段与非法实例标识/版本', () => {
    expect(() =>
      parseBridgeMessage({ ...HELLO, type: 'EXECUTE_SCRIPT' }),
    ).toThrow();
    expect(() => parseBridgeMessage({ ...HELLO, html: '<b>x</b>' })).toThrow();
    expect(() =>
      parseBridgeMessage({ ...HELLO, instanceId: 'bad id!' }),
    ).toThrow();
    expect(() =>
      parseBridgeMessage({ ...HELLO, protocolVersion: 'v1' }),
    ).toThrow();
    expect(() => parseBridgeMessage({ ...HELLO, appCode: '' })).toThrow();
    expect(() =>
      parseBridgeMessage({ ...HELLO, protocolVersion: '1.0.0' }),
    ).toThrow();
    expect(() => parseBridgeMessage('HELLO')).toThrow();
    expect(() => parseBridgeMessage(null)).toThrow();
  });

  it('aUTH 只接受非空票据与过期时间，且不接受额外字段', () => {
    const base = {
      expiresAt: '2026-09-25T10:00:00Z',
      instanceId: 'inst-1',
      protocolVersion: '1.0',
      token: 'aitkt_once_abcdefg',
      type: 'AUTH',
    };
    expect(() => parseBridgeMessage({ ...base, token: 'short' })).toThrow();
    expect(() =>
      parseBridgeMessage({ ...base, token: 'x'.repeat(5000) }),
    ).toThrow();
    expect(() =>
      parseBridgeMessage({ ...base, url: 'https://evil.example.com' }),
    ).toThrow();
  });

  it('iNIT 的主题必须符合冻结主题契约', () => {
    expect(() =>
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        theme: { primaryColor: 'red', radius: 6, fontFamily: 'system-ui' },
        type: 'INIT',
      }),
    ).toThrow();
    expect(
      parseBridgeMessage({
        instanceId: 'inst-1',
        protocolVersion: '1.0',
        theme: { primaryColor: '#1677ff', radius: 6, fontFamily: 'system-ui' },
        type: 'INIT',
      }).type,
    ).toBe('INIT');
  });

  it('白名单覆盖设计契约 7.2 的全部消息类型', () => {
    expect([...BRIDGE_MESSAGE_TYPES].toSorted()).toStrictEqual([
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
    ]);
    expect(BRIDGE_PROTOCOL_VERSION).toBe('1.0');
  });

  it('版本协商：同版本与 N-1 兼容，主版本不同或差距过大都不兼容', () => {
    expect(isCompatibleProtocolVersion('1.0', '1.0')).toBe(true);
    expect(isCompatibleProtocolVersion('1.0', '1.1')).toBe(true);
    expect(isCompatibleProtocolVersion('1.2', '1.0')).toBe(false);
    expect(isCompatibleProtocolVersion('2.0', '1.9')).toBe(false);
    expect(isCompatibleProtocolVersion('nope', '1.0')).toBe(false);
  });
});
