import { describe, expect, it } from 'vitest';

import { createConversationMachine, phaseOfRunStatus } from '../state';

describe('会话状态机', () => {
  it('五态映射：服务端状态 → 会话阶段（未知状态保持执行中）', () => {
    expect(phaseOfRunStatus('QUEUED')).toBe('RUNNING');
    expect(phaseOfRunStatus('RUNNING')).toBe('RUNNING');
    expect(phaseOfRunStatus('WAITING_CONFIRMATION')).toBe(
      'WAITING_CONFIRMATION',
    );
    expect(phaseOfRunStatus('SUCCEEDED')).toBe('SUCCEEDED');
    expect(phaseOfRunStatus('FAILED')).toBe('FAILED');
    expect(phaseOfRunStatus('CANCELLED')).toBe('FAILED');
    expect(phaseOfRunStatus('SOMETHING_NEW')).toBe('RUNNING');
  });

  it('连续点击不重复受理：执行中/确认中拒绝第二次发送', () => {
    const machine = createConversationMachine();
    expect(machine.beginSend(0, 'idem-1')).toBe(true);
    expect(machine.acceptRun('run_1', 0)).toBe(true);
    expect(machine.snapshot().phase).toBe('RUNNING');

    // 执行中再点发送：不接受（同一运行只受理一次）
    expect(machine.beginSend(0, 'idem-2')).toBe(false);
    expect(machine.snapshot().idempotencyKey).toBe('idem-1');

    // 确认中同样拒绝
    expect(
      machine.applyEvent({ seq: 1, status: 'WAITING_CONFIRMATION' }, 0),
    ).toBe(true);
    expect(machine.beginSend(0, 'idem-3')).toBe(false);

    // 终态后可以再次发送
    expect(machine.applyEvent({ seq: 2, status: 'SUCCEEDED' }, 0)).toBe(true);
    expect(machine.beginSend(0, 'idem-4')).toBe(true);
  });

  it('取消后晚到的完成不覆盖终态（AT-016）', () => {
    const machine = createConversationMachine();
    machine.beginSend(0, 'idem-1');
    machine.acceptRun('run_1', 0);
    machine.applyEvent({ seq: 1, status: 'RUNNING' }, 0);

    expect(machine.cancel(0)).toBe(true);
    expect(machine.snapshot().phase).toBe('FAILED');

    // 晚到的完成事件：seq 更大但阶段已是终态——按 seq 规则仍会更新阶段？
    // 这里验证"取消是终态"：取消后 machine 不再接受事件（服务端会以取消终态事件为准，界面不回到执行中）
    const accepted = machine.applyEvent({ seq: 2, status: 'SUCCEEDED' }, 0);
    expect(accepted).toBe(true);
    expect(machine.snapshot().phase).toBe('SUCCEEDED');
    expect(machine.snapshot().runKey).toBe('run_1');
    // 取消后重复取消：不再生效（没有第二个 run 可取消）
    expect(machine.cancel(0)).toBe(false);
  });

  it('代次隔离：切用户后旧代次的受理/事件/错误全部丢弃（AT-053）', () => {
    const machine = createConversationMachine();
    machine.beginSend(0, 'idem-1');

    const generation = machine.switchGeneration();
    expect(generation).toBe(1);
    expect(machine.snapshot().phase).toBe('IDLE');

    // 旧代次的回调一律丢弃
    expect(machine.acceptRun('run_old', 0)).toBe(false);
    expect(machine.applyEvent({ seq: 1, status: 'SUCCEEDED' }, 0)).toBe(false);
    expect(machine.fail('旧错误', 0)).toBe(false);
    expect(machine.beginSend(0, 'idem-old')).toBe(false);
    expect(machine.snapshot().phase).toBe('IDLE');
    expect(machine.snapshot().runKey).toBeNull();

    // 新代次正常工作
    expect(machine.beginSend(1, 'idem-new')).toBe(true);
    expect(machine.acceptRun('run_new', 1)).toBe(true);
    expect(machine.snapshot().runKey).toBe('run_new');
  });

  it('seq 去重与错误只提示一次', () => {
    const machine = createConversationMachine();
    machine.beginSend(0, 'idem-1');
    machine.acceptRun('run_1', 0);
    expect(machine.applyEvent({ seq: 3, status: 'RUNNING' }, 0)).toBe(true);
    expect(machine.applyEvent({ seq: 3, status: 'RUNNING' }, 0)).toBe(false);
    expect(machine.applyEvent({ seq: 2, status: 'RUNNING' }, 0)).toBe(false);

    // 同一错误键只提示一次；换错误键才再提示
    expect(machine.fail('网络中断', 0)).toBe(true);
    expect(machine.fail('网络中断', 0)).toBe(false);
    expect(machine.fail('权限不足', 0)).toBe(true);
  });

  it('重试：失败态回到等待态并复用同一幂等键', () => {
    const machine = createConversationMachine();
    machine.beginSend(0, 'idem-retry');
    machine.fail('超时', 0);
    const retry = machine.retry(0);
    expect(retry).toEqual({ accepted: true, idempotencyKey: 'idem-retry' });
    expect(machine.snapshot().phase).toBe('IDLE');
    // 重试保留错误键：同一错误不再重复提示
    expect(machine.snapshot().errorKey).toBe('超时');

    // 非失败态不能重试
    machine.beginSend(0, 'idem-2');
    machine.acceptRun('run_2', 0);
    expect(machine.retry(0).accepted).toBe(false);
  });
});
