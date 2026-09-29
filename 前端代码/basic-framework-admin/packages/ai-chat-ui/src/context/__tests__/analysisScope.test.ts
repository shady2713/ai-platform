import type {
  AnalysisScopeApi,
  AnalysisScopeCatalog,
  AnalysisScopeSelection,
} from '../analysisScope';

import { describe, expect, it, vi } from 'vitest';

import {
  analysisScopeLabel,
  analysisScopeSummary,
  createAnalysisScopeMachine,
  modelCatalogSystemCodes,
  planScopeSelection,
} from '../analysisScope';

function system(
  appCode: string,
  currentSystem: boolean,
): AnalysisScopeCatalog['entries'][number] {
  return {
    appCode,
    currentSystem,
    federationId: currentSystem ? null : 11,
    systemFingerprint: `fp-${appCode}`,
    systemName: `${appCode} 系统`,
  };
}

function catalog(
  overrides: Partial<AnalysisScopeCatalog> = {},
): AnalysisScopeCatalog {
  return {
    catalogFingerprint: 'catalog-fp',
    denied: false,
    entries: [system('crm', true), system('erp', false)],
    modelCatalog: '[{"system":"crm"},{"system":"erp"}]',
    ...overrides,
  };
}

function selection(): AnalysisScopeSelection {
  return {
    catalogFingerprint: 'catalog-fp',
    mode: 'CROSS_SYSTEM',
    selectionFingerprint: 'selection-fp',
    systems: [system('crm', true), system('erp', false)],
    targetSystemCodes: ['crm', 'erp'],
  };
}

function api(overrides: Partial<AnalysisScopeApi> = {}): AnalysisScopeApi {
  return {
    loadCatalog: vi.fn(() => Promise.resolve(catalog())),
    selectScope: vi.fn(() => Promise.resolve(selection())),
    verifyScope: vi.fn(() => Promise.resolve(selection())),
    ...overrides,
  };
}

describe('analysis scope planning (Y01)', () => {
  it('never accepts a system that the catalog does not contain', () => {
    // 目录之外的标识属于编程错误：当场拒绝，不提交给服务端"兜底"
    expect(
      planScopeSelection(catalog(), {
        mode: 'CROSS_SYSTEM',
        targetSystemCodes: ['crm', 'hr'],
      }),
    ).toEqual({ errorKey: 'scope-unknown-system', ok: false });
  });

  it('requires the current system for cross-system analysis', () => {
    expect(
      planScopeSelection(catalog(), {
        mode: 'CROSS_SYSTEM',
        targetSystemCodes: ['erp'],
      }),
    ).toEqual({ errorKey: 'scope-cross-system-without-current', ok: false });
    expect(
      planScopeSelection(catalog(), {
        mode: 'CROSS_SYSTEM',
        targetSystemCodes: ['crm'],
      }),
    ).toEqual({ errorKey: 'scope-cross-system-needs-two', ok: false });
    expect(
      planScopeSelection(catalog(), {
        mode: 'CROSS_SYSTEM',
        targetSystemCodes: ['erp', 'crm', 'crm'],
      }),
    ).toEqual({
      mode: 'CROSS_SYSTEM',
      ok: true,
      targetSystemCodes: ['erp', 'crm'],
    });
  });

  it('keeps single-system selection free of target systems', () => {
    expect(
      planScopeSelection(catalog(), {
        mode: 'CURRENT_SYSTEM',
        targetSystemCodes: ['crm'],
      }),
    ).toEqual({ errorKey: 'scope-current-system-with-targets', ok: false });
    expect(planScopeSelection(catalog(), { mode: 'CURRENT_SYSTEM' })).toEqual({
      mode: 'CURRENT_SYSTEM',
      ok: true,
      targetSystemCodes: ['crm'],
    });
    expect(
      planScopeSelection(catalog({ denied: true, entries: [] }), {
        mode: 'CURRENT_SYSTEM',
      }),
    ).toEqual({ errorKey: 'scope-current-system-unavailable', ok: false });
  });

  it('parses only what the platform published in the model catalog', () => {
    expect(modelCatalogSystemCodes('[{"system":"crm"},{"system":42}]')).toEqual(
      ['crm'],
    );
    expect(modelCatalogSystemCodes('not json')).toEqual([]);
    expect(modelCatalogSystemCodes('[1,2]')).toEqual([]);
    expect(modelCatalogSystemCodes('')).toEqual([]);
    expect(modelCatalogSystemCodes('{"system":"crm"}')).toEqual([]);
  });

  it('labels modes and explains phases without leaking system existence', () => {
    expect(analysisScopeLabel('CROSS_SYSTEM')).toBe('跨系统分析');
    expect(analysisScopeLabel('CURRENT_SYSTEM')).toBe('仅当前系统');
    expect(
      analysisScopeSummary({
        catalog: null,
        errorKey: null,
        generation: 0,
        phase: 'IDLE',
        selection: null,
      }),
    ).toContain('尚未读取');
    expect(
      analysisScopeSummary({
        catalog: catalog({ denied: true, entries: [] }),
        errorKey: null,
        generation: 0,
        phase: 'DENIED',
        selection: null,
      }),
    ).toContain('没有任何可访问系统');
    expect(
      analysisScopeSummary({
        catalog: null,
        errorKey: 'scope-load-failed',
        generation: 0,
        phase: 'FAILED',
        selection: null,
      }),
    ).toContain('授权发现失败');
    expect(
      analysisScopeSummary({
        catalog: catalog(),
        errorKey: null,
        generation: 0,
        phase: 'LOADING',
        selection: null,
      }),
    ).toContain('正在读取');
    expect(
      analysisScopeSummary({
        catalog: catalog({ entries: [] }),
        errorKey: null,
        generation: 0,
        phase: 'READY',
        selection: null,
      }),
    ).toContain('没有任何可访问系统');
    expect(
      analysisScopeSummary({
        catalog: catalog(),
        errorKey: null,
        generation: 0,
        phase: 'READY',
        selection: null,
      }),
    ).toContain('可访问系统 2 个');
  });
});

describe('analysis scope machine (Y01)', () => {
  it('loads the catalog and marks denial as DENIED, not as an empty list', async () => {
    const machine = createAnalysisScopeMachine(
      api({
        loadCatalog: vi.fn(() =>
          Promise.resolve(catalog({ denied: true, entries: [] })),
        ),
      }),
    );

    await machine.load();

    expect(machine.getSnapshot().phase).toBe('DENIED');
    expect(machine.getSnapshot().catalog?.entries).toEqual([]);
    expect(machine.getSnapshot().selection).toBeNull();
  });

  it('loads, selects explicitly with the observed fingerprint and verifies', async () => {
    const port = api();
    const machine = createAnalysisScopeMachine(port);
    await machine.load();

    await expect(machine.select('CROSS_SYSTEM', ['crm', 'erp'])).resolves.toBe(
      true,
    );

    expect(port.selectScope).toHaveBeenCalledWith({
      catalogFingerprint: 'catalog-fp',
      mode: 'CROSS_SYSTEM',
      targetSystemCodes: ['crm', 'erp'],
    });
    expect(machine.getSnapshot().selection?.selectionFingerprint).toBe(
      'selection-fp',
    );
    await expect(machine.verify()).resolves.toBe(true);
    expect(port.verifyScope).toHaveBeenCalledTimes(1);
  });

  it('refuses plans that break the explicit-selection rules before calling the server', async () => {
    const port = api();
    const machine = createAnalysisScopeMachine(port);
    await machine.load();

    await expect(machine.select('CROSS_SYSTEM', ['hr'])).resolves.toBe(false);
    expect(port.selectScope).not.toHaveBeenCalled();
    expect(machine.getSnapshot().errorKey).toBe('scope-unknown-system');
    // 未加载 / 失权时不能选择
    const deniedMachine = createAnalysisScopeMachine(
      api({
        loadCatalog: vi.fn(() =>
          Promise.resolve(catalog({ denied: true, entries: [] })),
        ),
      }),
    );
    await deniedMachine.load();
    await expect(deniedMachine.select('CURRENT_SYSTEM', [])).resolves.toBe(
      false,
    );
  });

  it('keeps failures honest: load failure, stale selection and revoked facts', async () => {
    const failing = createAnalysisScopeMachine(
      api({ loadCatalog: vi.fn(() => Promise.reject(new Error('network'))) }),
    );
    await failing.load();
    expect(failing.getSnapshot().phase).toBe('FAILED');
    expect(failing.getSnapshot().errorKey).toBe('scope-load-failed');

    const machine = createAnalysisScopeMachine(
      api({ selectScope: vi.fn(() => Promise.reject(new Error('409'))) }),
    );
    await machine.load();
    await expect(machine.select('CURRENT_SYSTEM', [])).resolves.toBe(false);
    expect(machine.getSnapshot().errorKey).toBe('scope-select-failed');
    expect(machine.getSnapshot().selection).toBeNull();

    const verifying = createAnalysisScopeMachine(
      api({ verifyScope: vi.fn(() => Promise.reject(new Error('409'))) }),
    );
    await verifying.load();
    await verifying.select('CURRENT_SYSTEM', []);
    await expect(verifying.verify()).resolves.toBe(false);
    // 核验失败必须丢弃选择：不得保留"曾经可访问"的结论
    expect(verifying.getSnapshot().selection).toBeNull();
    expect(verifying.getSnapshot().errorKey).toBe('scope-verify-failed');
    await expect(verifying.verify()).resolves.toBe(false);
  });

  it('drops late responses after destroy', async () => {
    let release: (value: AnalysisScopeCatalog) => void = () => {};
    const pending = new Promise<AnalysisScopeCatalog>((resolve) => {
      release = resolve;
    });
    const machine = createAnalysisScopeMachine(
      api({ loadCatalog: vi.fn(() => pending) }),
    );

    const loading = machine.load();
    machine.destroy();
    release(catalog());
    await loading;

    // 销毁后不写状态、不显示权限：快照保持"未读取"
    expect(machine.getSnapshot().phase).toBe('LOADING');
    expect(machine.getSnapshot().catalog).toBeNull();
    expect(machine.getSnapshot().generation).toBe(1);
  });

  it('drops late selection responses after destroy', async () => {
    let release: (value: AnalysisScopeSelection) => void = () => {};
    const pending = new Promise<AnalysisScopeSelection>((resolve) => {
      release = resolve;
    });
    const machine = createAnalysisScopeMachine(
      api({ selectScope: vi.fn(() => pending) }),
    );
    await machine.load();

    const selecting = machine.select('CURRENT_SYSTEM', []);
    machine.destroy();
    release(selection());

    await expect(selecting).resolves.toBe(false);
    expect(machine.getSnapshot().selection).toBeNull();
  });
});
