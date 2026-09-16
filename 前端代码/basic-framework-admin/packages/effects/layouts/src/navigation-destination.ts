const MAX_NAVIGATION_DESTINATION_LENGTH = 2048;

type NavigationDestination =
  | { kind: 'external'; url: string }
  | { kind: 'internal'; path: string };

function containsControlCharacter(value: string): boolean {
  return [...value].some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint <= 31 || codePoint === 127;
  });
}

/** 将动态导航目标限制为站内绝对路径或无凭据 HTTPS 外链。 */
function resolveNavigationDestination(
  value: string,
): NavigationDestination | undefined {
  if (
    value.length === 0 ||
    value.length > MAX_NAVIGATION_DESTINATION_LENGTH ||
    value !== value.trim() ||
    containsControlCharacter(value)
  ) {
    return undefined;
  }
  if (
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !value.includes('\\')
  ) {
    return { kind: 'internal', path: value };
  }
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password) {
      return undefined;
    }
    return { kind: 'external', url: url.href };
  } catch {
    return undefined;
  }
}

export { resolveNavigationDestination };
export type { NavigationDestination };
