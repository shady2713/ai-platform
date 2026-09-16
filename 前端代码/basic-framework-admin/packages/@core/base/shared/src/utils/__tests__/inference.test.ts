import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  getFirstNonNullOrUndefined,
  isBoolean,
  isEmpty,
  isHttpUrl,
  isMacOs,
  isNumber,
  isObject,
  isUndefined,
  isWindow,
  isWindowsOs,
} from '../inference';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('isHttpUrl', () => {
  it("should return true when given 'http://example.com'", () => {
    expect(isHttpUrl('http://example.com')).toBe(true);
  });

  it("should return true when given 'https://example.com'", () => {
    expect(isHttpUrl('https://example.com')).toBe(true);
  });

  it("should return false when given 'ftp://example.com'", () => {
    expect(isHttpUrl('ftp://example.com')).toBe(false);
  });

  it("should return false when given 'example.com'", () => {
    expect(isHttpUrl('example.com')).toBe(false);
  });
});

describe('isUndefined', () => {
  it('isUndefined should return true for undefined values', () => {
    expect(isUndefined()).toBe(true);
  });

  it('isUndefined should return false for null values', () => {
    expect(isUndefined(null)).toBe(false);
  });

  it('isUndefined should return false for defined values', () => {
    expect(isUndefined(0)).toBe(false);
    expect(isUndefined('')).toBe(false);
    expect(isUndefined(false)).toBe(false);
  });

  it('isUndefined should return false for objects and arrays', () => {
    expect(isUndefined({})).toBe(false);
    expect(isUndefined([])).toBe(false);
  });
});

describe('isEmpty', () => {
  it('should return true for empty string', () => {
    expect(isEmpty('')).toBe(true);
  });

  it('should return true for empty array', () => {
    expect(isEmpty([])).toBe(true);
  });

  it('should return true for empty object', () => {
    expect(isEmpty({})).toBe(true);
  });

  it('should return false for non-empty string', () => {
    expect(isEmpty('hello')).toBe(false);
  });

  it('should return false for non-empty array', () => {
    expect(isEmpty([1, 2, 3])).toBe(false);
  });

  it('should return false for non-empty object', () => {
    expect(isEmpty({ a: 1 })).toBe(false);
  });

  it('should return true for null or undefined', () => {
    expect(isEmpty(null)).toBe(true);
    expect(isEmpty()).toBe(true);
  });

  it('should return false for number or boolean', () => {
    expect(isEmpty(0)).toBe(false);
    expect(isEmpty(true)).toBe(false);
  });
});

describe('isWindow', () => {
  it('should return true for the window object', () => {
    expect(isWindow(window)).toBe(true);
  });

  it('should return false for other objects', () => {
    expect(isWindow({})).toBe(false);
    expect(isWindow([])).toBe(false);
    expect(isWindow(null)).toBe(false);
  });
});

describe('platform inference', () => {
  it('detects macOS and Windows user agents', () => {
    const userAgent = vi.spyOn(window.navigator, 'userAgent', 'get');
    userAgent.mockReturnValue('Mozilla/5.0 (Macintosh; Intel Mac OS X)');
    expect(isMacOs()).toBe(true);
    expect(isWindowsOs()).toBe(false);

    userAgent.mockReturnValue('Mozilla/5.0 (Windows NT 10.0; Win64; x64)');
    expect(isMacOs()).toBe(false);
    expect(isWindowsOs()).toBe(true);
  });

  it('accepts only finite numbers', () => {
    expect(isNumber(0)).toBe(true);
    expect(isNumber(-1.5)).toBe(true);
    expect(isNumber(Number.NaN)).toBe(false);
    expect(isNumber(Number.POSITIVE_INFINITY)).toBe(false);
    expect(isNumber('1')).toBe(false);
  });
});

describe('isBoolean', () => {
  it('should return true for boolean values', () => {
    expect(isBoolean(true)).toBe(true);
    expect(isBoolean(false)).toBe(true);
  });

  it('should return false for non-boolean values', () => {
    expect(isBoolean(null)).toBe(false);
    expect(isBoolean(42)).toBe(false);
    expect(isBoolean('string')).toBe(false);
    expect(isBoolean({})).toBe(false);
    expect(isBoolean([])).toBe(false);
  });
});

describe('isObject', () => {
  it('should return true for objects', () => {
    expect(isObject({})).toBe(true);
    expect(isObject({ a: 1 })).toBe(true);
  });

  it('should return false for non-objects', () => {
    expect(isObject(null)).toBe(false);
    expect(isObject(42)).toBe(false);
    expect(isObject('string')).toBe(false);
    expect(isObject(true)).toBe(false);
    expect(isObject([1, 2, 3])).toBe(true);
    expect(isObject(new Date())).toBe(true);
    expect(isObject(/regex/)).toBe(true);
  });
});

describe('getFirstNonNullOrUndefined', () => {
  describe('getFirstNonNullOrUndefined', () => {
    it('should return the first non-null and non-undefined value for a number array', () => {
      expect(getFirstNonNullOrUndefined<number>(undefined, null, 0, 42)).toBe(
        0,
      );
      expect(getFirstNonNullOrUndefined<number>(null, undefined, 42, 123)).toBe(
        42,
      );
    });

    it('should return the first non-null and non-undefined value for a string array', () => {
      expect(
        getFirstNonNullOrUndefined<string>(undefined, null, '', 'hello'),
      ).toBe('');
      expect(
        getFirstNonNullOrUndefined<string>(null, undefined, 'test', 'world'),
      ).toBe('test');
    });

    it('should return undefined if all values are null or undefined', () => {
      expect(getFirstNonNullOrUndefined(undefined, null)).toBeUndefined();
      expect(getFirstNonNullOrUndefined(null)).toBeUndefined();
    });

    it('should work with a single value', () => {
      expect(getFirstNonNullOrUndefined(42)).toBe(42);
      expect(getFirstNonNullOrUndefined()).toBeUndefined();
      expect(getFirstNonNullOrUndefined(null)).toBeUndefined();
    });

    it('should handle mixed types correctly', () => {
      expect(
        getFirstNonNullOrUndefined<number | object | string>(
          undefined,
          null,
          'test',
          123,
          { key: 'value' },
        ),
      ).toBe('test');
      expect(
        getFirstNonNullOrUndefined<number | object | string>(
          null,
          undefined,
          [1, 2, 3],
          'string',
        ),
      ).toEqual([1, 2, 3]);
    });
  });
});
