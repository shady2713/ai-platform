function arraysEqual<T>(left: readonly T[], right: readonly T[]): boolean {
  if (left.length !== right.length) return false;
  const counter = new Map<T, number>();
  for (const value of left) {
    counter.set(value, (counter.get(value) ?? 0) + 1);
  }
  for (const value of right) {
    const count = counter.get(value);
    if (!count) return false;
    counter.set(value, count - 1);
  }
  return true;
}

type DiffResult<T> = T extends readonly unknown[]
  ? T
  : T extends object
    ? { [K in keyof T]?: DiffResult<T[K]> }
    : T;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function findDifferences(left: unknown, right: unknown): unknown {
  if (Array.isArray(left) && Array.isArray(right)) {
    return arraysEqual(left, right) ? undefined : right;
  }

  if (isRecord(left) && isRecord(right)) {
    const entries: [string, unknown][] = [];
    const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
    for (const key of keys) {
      const valueDiff = findDifferences(left[key], right[key]);
      if (valueDiff !== undefined) entries.push([key, valueDiff]);
    }
    return entries.length > 0 ? Object.fromEntries(entries) : undefined;
  }

  return Object.is(left, right) ? undefined : right;
}

function diff<TTarget extends object>(
  source: object,
  target: TTarget,
): DiffResult<TTarget> | undefined {
  return findDifferences(source, target) as DiffResult<TTarget> | undefined;
}

export { arraysEqual, diff };
