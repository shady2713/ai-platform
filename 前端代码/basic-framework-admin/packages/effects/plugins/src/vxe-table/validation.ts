import type { Ref } from 'vue';

type ValidationState = Readonly<Pick<Ref<boolean>, 'value'>>;
type ValidationRule<T extends object> =
  | 'required'
  | ((row: Readonly<T>) => boolean);

function hasRequiredValue(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'string') return value.trim().length > 0;
  if (typeof value === 'number') return Number.isFinite(value);
  if (Array.isArray(value)) return value.length > 0;
  return true;
}

/** Creates a VXE cell class callback that activates only during validation. */
function createValidationClassName<T extends object>(
  isValidating: undefined | ValidationState,
  fieldName: keyof T & string,
  validationRule: ValidationRule<T>,
) {
  return ({ row }: { row: T }): string => {
    if (!isValidating?.value) return '';
    const isValid =
      validationRule === 'required'
        ? hasRequiredValue(row[fieldName])
        : validationRule(row);
    return isValid ? '' : 'required-field-error';
  };
}

function createRequiredValidation<T extends object>(
  isValidating: undefined | ValidationState,
  fieldName: keyof T & string,
) {
  return createValidationClassName(isValidating, fieldName, 'required');
}

function createCustomValidation<T extends object>(
  isValidating: undefined | ValidationState,
  validationFn: (row: Readonly<T>) => boolean,
) {
  return ({ row }: { row: T }): string => {
    if (!isValidating?.value) return '';
    return validationFn(row) ? '' : 'required-field-error';
  };
}

export {
  createCustomValidation,
  createRequiredValidation,
  createValidationClassName,
};
