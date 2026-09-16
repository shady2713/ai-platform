function parseFiniteNumber(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : undefined;
  }
  if (typeof value !== 'string' || value.trim().length === 0) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * Formats a finite numeric value with a fixed number of decimal places.
 * Invalid values render as an empty string; an invalid digit contract throws.
 */
export function formatDecimal(value: unknown, digits: number = 2): string {
  if (!Number.isInteger(digits) || digits < 0 || digits > 20) {
    throw new RangeError('digits must be an integer between 0 and 20');
  }
  const parsed = parseFiniteNumber(value);
  return parsed === undefined ? '' : parsed.toFixed(digits);
}

/** Converts an amount in minor units (for example cents) to major units. */
export function minorUnitsToMajorUnits(value: unknown): number | undefined {
  const parsed = parseFiniteNumber(value);
  return parsed === undefined ? undefined : parsed / 100;
}
