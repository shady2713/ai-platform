/**
 * 跨系统分析范围选择（Y01）：Chat 侧的类型化模型与状态机。
 *
 * 为什么放在 Chat 包里：范围选择是**用户意图**进入平台分析链路的入口，但它不是权限本身——
 * 三条纪律必须在客户端就写死，否则界面会把"看起来有权限"变成"实际扩大范围"：
 *
 * 1. **不推断身份**：两侧 `externalUserId` 相同不代表同一个人；客户端只提交平台给出的系统标识，
 *    不认识任何身份映射，也不提供"按同名自动选目标系统"的入口；
 * 2. **不合成系统清单**：可选系统只能来自服务端发现结果；选到目录之外的系统属于编程错误，
 *    必须当场丢弃（fail closed），而不是"提交上去让服务端兜底"；
 * 3. **选择可核验**：选择请求携带服务端给出的目录指纹，结果携带选择指纹；核验失败（事实变化）
 *    只提示重新发现，绝不静默改用"当前仍可访问的系统"。
 *
 * 本文件不依赖浏览器 API：宿主注入 `AnalysisScopeApi` 端口（真实实现是带票据的请求），
 * 状态机因此可以在 happy-dom 下逐条验证加载 / 空 / 失败 / 失权 / 销毁五种状态。
 */

/** 分析范围模式（与服务端 `AiAnalysisScopeMode` 一一对应）。 */
export type AnalysisScopeMode = 'CROSS_SYSTEM' | 'CURRENT_SYSTEM';

/** 发现结果里的一个系统。 */
export interface AnalysisScopeSystem {
  /** 系统标识（接入应用 appCode，稳定不可修改） */
  appCode: string;
  /** 是否当前会话所在系统 */
  currentSystem: boolean;
  /** 联邦映射编号（当前系统为空） */
  federationId?: null | number;
  /** 系统访问指纹（选择与核验都绑定它） */
  systemFingerprint: string;
  /** 系统名称（仅展示） */
  systemName: string;
}

/** 发现结果（授权目录）：`denied` 为真时没有任何可访问系统。 */
export interface AnalysisScopeCatalog {
  catalogFingerprint: string;
  denied: boolean;
  entries: AnalysisScopeSystem[];
  /** 平台给模型看的目录（只含可访问系统） */
  modelCatalog: string;
}

/** 选择结果（可核验事实）。 */
export interface AnalysisScopeSelection {
  catalogFingerprint: string;
  mode: AnalysisScopeMode;
  selectionFingerprint: string;
  systems: AnalysisScopeSystem[];
  targetSystemCodes: string[];
}

/** 宿主注入的受控端口（服务端是唯一事实来源）。 */
export interface AnalysisScopeApi {
  /** 读取当前主体的授权目录（无权系统不会出现在结果里）。 */
  loadCatalog(): Promise<AnalysisScopeCatalog>;
  /** 固定范围选择（必须携带看到的目录指纹）。 */
  selectScope(input: {
    catalogFingerprint: string;
    mode: AnalysisScopeMode;
    targetSystemCodes: string[];
  }): Promise<AnalysisScopeSelection>;
  /** 再核验历史选择（事实变化时 reject）。 */
  verifyScope(): Promise<AnalysisScopeSelection>;
}

/** 状态机阶段：等待 / 加载中 / 就绪 / 失权（无任何可访问系统）/ 失败。 */
export type AnalysisScopePhase =
  | 'DENIED'
  | 'FAILED'
  | 'IDLE'
  | 'LOADING'
  | 'READY';

/** 只读快照。 */
export interface AnalysisScopeSnapshot {
  catalog: AnalysisScopeCatalog | null;
  errorKey: null | string;
  generation: number;
  phase: AnalysisScopePhase;
  selection: AnalysisScopeSelection | null;
}

/** 状态机。 */
export interface AnalysisScopeMachine {
  /** 销毁：之后任何晚到的响应都被丢弃（不写状态、不提示）。 */
  destroy(): void;
  /** 当前快照。 */
  getSnapshot(): AnalysisScopeSnapshot;
  /** 加载授权目录。 */
  load(): Promise<AnalysisScopeSnapshot>;
  /** 固定范围选择（返回是否被受理）。 */
  select(
    mode: AnalysisScopeMode,
    targetSystemCodes: string[],
  ): Promise<boolean>;
  /** 再核验当前选择（返回是否仍然有效）。 */
  verify(): Promise<boolean>;
}

/** 选择请求的规范化结果。 */
export type ScopeSelectionPlan =
  | { errorKey: string; ok: false }
  | {
      mode: AnalysisScopeMode;
      ok: true;
      targetSystemCodes: string[];
    };

/**
 * 把"用户勾选的系统"规范化成一次显式选择：
 *
 * - 只能选目录里的系统（未知标识直接拒绝：编程错误不应变成越权请求）；
 * - 跨系统必须包含当前系统，且至少两个系统（"跨系统"不是一个系统）；
 * - 单系统模式不接受目标清单（显式选择不允许顺带扩大范围）。
 */
export function planScopeSelection(
  catalog: AnalysisScopeCatalog,
  modes: { mode: AnalysisScopeMode; targetSystemCodes?: string[] },
): ScopeSelectionPlan {
  const known = new Set((catalog.entries ?? []).map((entry) => entry.appCode));
  const requested = [...new Set(modes.targetSystemCodes)];
  const unknown = requested.filter((code) => !known.has(code));
  if (unknown.length > 0) {
    return { errorKey: 'scope-unknown-system', ok: false };
  }
  if (modes.mode === 'CURRENT_SYSTEM') {
    if (requested.length > 0) {
      return { errorKey: 'scope-current-system-with-targets', ok: false };
    }
    const current = (catalog.entries ?? []).find(
      (entry) => entry.currentSystem,
    );
    if (!current) {
      return { errorKey: 'scope-current-system-unavailable', ok: false };
    }
    return {
      mode: 'CURRENT_SYSTEM',
      ok: true,
      targetSystemCodes: [current.appCode],
    };
  }
  const current = (catalog.entries ?? []).find((entry) => entry.currentSystem);
  if (!current || !requested.includes(current.appCode)) {
    return { errorKey: 'scope-cross-system-without-current', ok: false };
  }
  if (requested.length < 2) {
    return { errorKey: 'scope-cross-system-needs-two', ok: false };
  }
  return { mode: 'CROSS_SYSTEM', ok: true, targetSystemCodes: requested };
}

/**
 * 解析平台给出的模型目录文本，只取出系统标识。
 *
 * 客户端不信任这段文本的形状：解析失败返回空数组（界面显示"目录不可用"），
 * 绝不把解析失败当成"没有系统"之外的含义，也不把未解析出的系统显示给用户。
 */
export function modelCatalogSystemCodes(modelCatalog: string): string[] {
  if (!modelCatalog) {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(modelCatalog);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .map((row) =>
        row && typeof row === 'object' && 'system' in row
          ? (row as { system?: unknown }).system
          : undefined,
      )
      .filter((code): code is string => typeof code === 'string');
  } catch {
    return [];
  }
}

/** 模式文案（界面只展示平台语义，不改写）。 */
export function analysisScopeLabel(mode: AnalysisScopeMode): string {
  return mode === 'CROSS_SYSTEM' ? '跨系统分析' : '仅当前系统';
}

/** 快照文案：把"为什么没有系统"与"失败了"分开，且都不暴露系统是否存在。 */
export function analysisScopeSummary(snapshot: AnalysisScopeSnapshot): string {
  switch (snapshot.phase) {
    case 'DENIED': {
      return '当前主体没有任何可访问系统：可能是未登记、已停用或没有任何有效授权。';
    }
    case 'FAILED': {
      return '授权发现失败，请稍后重试。';
    }
    case 'LOADING': {
      return '正在读取可访问系统…';
    }
    case 'READY': {
      const count = snapshot.catalog?.entries?.length ?? 0;
      return count === 0
        ? '当前主体没有任何可访问系统。'
        : `可访问系统 ${count} 个。`;
    }
    default: {
      return '尚未读取可访问系统。';
    }
  }
}

/**
 * 创建范围选择状态机。
 *
 * 代次（generation）语义与 C02 会话状态机一致：`destroy()` 之后代次 +1，
 * 晚到的加载/选择/核验响应一律丢弃——销毁后的组件不会因为一个慢响应而复活并显示旧权限。
 */
export function createAnalysisScopeMachine(
  api: AnalysisScopeApi,
): AnalysisScopeMachine {
  let snapshot: AnalysisScopeSnapshot = {
    catalog: null,
    errorKey: null,
    generation: 0,
    phase: 'IDLE',
    selection: null,
  };
  let destroyed = false;

  const isStale = (generation: number) =>
    destroyed || generation !== snapshot.generation;

  return {
    destroy() {
      destroyed = true;
      snapshot = { ...snapshot, generation: snapshot.generation + 1 };
    },
    getSnapshot() {
      return snapshot;
    },
    async load() {
      const generation = snapshot.generation;
      snapshot = { ...snapshot, errorKey: null, phase: 'LOADING' };
      try {
        const catalog = await api.loadCatalog();
        if (isStale(generation)) {
          return snapshot;
        }
        snapshot = {
          ...snapshot,
          catalog,
          errorKey: null,
          // 服务端说"没有任何可访问系统"就是失权，而不是空列表
          phase: catalog.denied ? 'DENIED' : 'READY',
          selection: null,
        };
      } catch {
        if (isStale(generation)) {
          return snapshot;
        }
        snapshot = {
          ...snapshot,
          errorKey: 'scope-load-failed',
          phase: 'FAILED',
        };
      }
      return snapshot;
    },
    async select(mode, targetSystemCodes) {
      const catalog = snapshot.catalog;
      if (!catalog || snapshot.phase !== 'READY') {
        return false;
      }
      const plan = planScopeSelection(catalog, { mode, targetSystemCodes });
      if (!plan.ok) {
        snapshot = { ...snapshot, errorKey: plan.errorKey };
        return false;
      }
      const generation = snapshot.generation;
      snapshot = { ...snapshot, errorKey: null, phase: 'LOADING' };
      try {
        const selection = await api.selectScope({
          catalogFingerprint: catalog.catalogFingerprint,
          mode: plan.mode,
          targetSystemCodes: plan.targetSystemCodes,
        });
        if (isStale(generation)) {
          return false;
        }
        snapshot = { ...snapshot, phase: 'READY', selection };
        return true;
      } catch {
        if (isStale(generation)) {
          return false;
        }
        // 选择被拒绝（越权、指纹过期）：不猜原因、不缩小范围，交给界面提示重新发现
        snapshot = {
          ...snapshot,
          errorKey: 'scope-select-failed',
          phase: 'READY',
        };
        return false;
      }
    },
    async verify() {
      if (!snapshot.selection) {
        return false;
      }
      const generation = snapshot.generation;
      try {
        const selection = await api.verifyScope();
        if (isStale(generation)) {
          return false;
        }
        snapshot = { ...snapshot, errorKey: null, selection };
        return true;
      } catch {
        if (isStale(generation)) {
          return false;
        }
        // 事实变化：选择作废，必须重新发现（不保留旧选择）
        snapshot = {
          ...snapshot,
          errorKey: 'scope-verify-failed',
          phase: 'READY',
          selection: null,
        };
        return false;
      }
    },
  };
}
