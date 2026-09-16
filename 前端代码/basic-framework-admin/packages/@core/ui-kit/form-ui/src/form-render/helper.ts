import type { ZodTypeAny } from 'zod';

import type { FormSchemaRuleType } from '../types';

import { isFunction, isObject, isString } from '@vben-core/shared/utils';

interface EventObjectLike {
  stopPropagation: () => void;
  target: Record<string, unknown>;
}

function isZodType(value: unknown): value is ZodTypeAny {
  return isObject(value) && Reflect.has(value, '_def');
}

function nestedZodType(
  definition: unknown,
  property: 'innerType' | 'schema',
): undefined | ZodTypeAny {
  if (!isObject(definition) || !Reflect.has(definition, property)) {
    return undefined;
  }
  const value = definition[property];
  return isZodType(value) ? value : undefined;
}

/**
 * Get the lowest level Zod type.
 * This will unpack optionals, refinements, etc.
 */
export function getBaseRules(
  schema: FormSchemaRuleType | undefined,
): null | ZodTypeAny {
  if (!schema || isString(schema)) return null;

  const innerType = nestedZodType(schema._def, 'innerType');
  if (innerType) return getBaseRules(innerType);

  const nestedSchema = nestedZodType(schema._def, 'schema');
  if (nestedSchema) return getBaseRules(nestedSchema);

  return schema;
}

/**
 * Search for a "ZodDefault" in the Zod stack and return its value.
 */
export function getDefaultValueInZodStack(
  schema: FormSchemaRuleType | undefined,
): unknown {
  if (!schema || isString(schema)) {
    return;
  }

  const definition: unknown = schema._def;
  if (
    isObject(definition) &&
    definition.typeName === 'ZodDefault' &&
    isFunction(definition.defaultValue)
  ) {
    return definition.defaultValue();
  }

  const innerType = nestedZodType(definition, 'innerType');
  if (innerType) return getDefaultValueInZodStack(innerType);

  const nestedSchema = nestedZodType(definition, 'schema');
  if (nestedSchema) return getDefaultValueInZodStack(nestedSchema);

  return undefined;
}

export function isEventObjectLike(obj: unknown): obj is EventObjectLike {
  if (!obj || !isObject(obj)) {
    return false;
  }
  return isObject(obj.target) && isFunction(obj.stopPropagation);
}
