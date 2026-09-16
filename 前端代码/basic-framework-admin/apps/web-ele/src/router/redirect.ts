const MAX_REDIRECT_LENGTH = 2048;

function containsControlCharacter(path: string): boolean {
  return [...path].some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint <= 31 || codePoint === 127;
  });
}

function isLocalRoutePath(path: string): boolean {
  return (
    path.startsWith('/') &&
    !path.startsWith('//') &&
    !path.includes('\\') &&
    !containsControlCharacter(path)
  );
}

/** 将查询参数中的跳转目标限制为当前管理后台内的绝对路由。 */
function normalizeLocalRedirect(value: unknown, fallback: string): string {
  const safeFallback = isLocalRoutePath(fallback) ? fallback : '/';
  if (typeof value !== 'string' || value.length > MAX_REDIRECT_LENGTH) {
    return safeFallback;
  }
  try {
    const decoded = decodeURIComponent(value);
    return isLocalRoutePath(decoded) ? decoded : safeFallback;
  } catch {
    return safeFallback;
  }
}

export { normalizeLocalRedirect };
