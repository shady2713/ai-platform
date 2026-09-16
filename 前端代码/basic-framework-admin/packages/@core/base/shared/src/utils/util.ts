export function bindMethods<T extends object>(instance: T): void {
  const prototype = Object.getPrototypeOf(instance);
  const propertyNames = Object.getOwnPropertyNames(prototype);

  propertyNames.forEach((propertyName) => {
    const descriptor = Object.getOwnPropertyDescriptor(prototype, propertyName);
    const propertyValue = descriptor?.value;

    if (
      typeof propertyValue === 'function' &&
      propertyName !== 'constructor' &&
      descriptor
    ) {
      instance[propertyName as keyof T] = propertyValue.bind(instance);
    }
  });
}

/**
 * Reads an own property from an object or array using a dot-separated path.
 * Prototype-chain traversal is deliberately rejected.
 */
export function getNestedValue(obj: unknown, path: string): unknown {
  if (path.length === 0) {
    throw new Error('Path must be a non-empty string');
  }

  let current: unknown = obj;
  for (const key of path.split('.')) {
    if (current === null || typeof current !== 'object') {
      return undefined;
    }
    if (!Object.hasOwn(current, key)) {
      return undefined;
    }
    current = (current as Record<string, unknown>)[key];
  }
  return current;
}
