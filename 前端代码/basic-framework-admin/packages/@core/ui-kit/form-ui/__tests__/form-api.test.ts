import type { FormActions, FormSchema } from '../src/types';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FormApi } from '../src/form-api';

function formActions(value: Record<string, unknown>): FormActions {
  return value as unknown as FormActions;
}

describe('formApi', () => {
  let formApi: FormApi;

  beforeEach(() => {
    formApi = new FormApi();
  });

  it('should initialize with default state', () => {
    expect(formApi.state).toEqual(
      expect.objectContaining({
        actionWrapperClass: '',
        collapsed: false,
        collapsedRows: 1,
        commonConfig: {},
        handleReset: undefined,
        handleSubmit: undefined,
        layout: 'horizontal',
        resetButtonOptions: {},
        schema: [],
        showCollapseButton: false,
        showDefaultActions: true,
        submitButtonOptions: {},
        wrapperClass: 'grid-cols-1',
      }),
    );
    expect(formApi.isMounted).toBe(false);
  });

  it('should mount form actions', async () => {
    const actions = formActions({
      meta: {},
      resetForm: vi.fn(),
      setFieldValue: vi.fn(),
      setValues: vi.fn(),
      submitForm: vi.fn(),
      validate: vi.fn(),
      values: { name: 'test' },
    });

    await formApi.mount(actions, new Map());
    expect(formApi.isMounted).toBe(true);
    expect(formApi.form).toEqual(actions);
  });

  it('should get values from form', async () => {
    const actions = formActions({
      meta: {},
      values: { name: 'test' },
    });

    await formApi.mount(actions, new Map());
    const values = await formApi.getValues();
    expect(values).toEqual({ name: 'test' });
  });

  it('should convert configured array fields to delimited strings', async () => {
    formApi = new FormApi({ arrayToStringFields: ['roles', ';'] });
    await formApi.mount(
      formActions({ meta: {}, values: { roles: ['admin', 'auditor'] } }),
      new Map(),
    );

    await expect(formApi.getValues()).resolves.toEqual({
      roles: 'admin;auditor',
    });
  });

  it('should convert configured delimited strings to arrays', async () => {
    formApi = new FormApi({ arrayToStringFields: ['roles', '|'] });
    await formApi.mount(
      formActions({ meta: {}, values: { roles: 'admin|auditor' } }),
      new Map(),
    );

    await expect(formApi.getValues()).resolves.toEqual({
      roles: ['admin', 'auditor'],
    });
  });

  it('should map range values to API fields', async () => {
    formApi = new FormApi({
      fieldMappingTime: [
        ['createdAt', ['createdAtStart', 'createdAtEnd'], null],
      ],
    });
    await formApi.mount(
      formActions({
        meta: {},
        values: { createdAt: ['2026-08-01', '2026-08-31'] },
      }),
      new Map(),
    );

    await expect(formApi.getValues()).resolves.toEqual({
      createdAtEnd: '2026-08-31',
      createdAtStart: '2026-08-01',
    });
  });

  it('should reject malformed range values with the field name', () => {
    formApi = new FormApi({
      fieldMappingTime: [
        ['createdAt', ['createdAtStart', 'createdAtEnd'], null],
      ],
    });

    expect(() =>
      formApi.mount(
        formActions({ meta: {}, values: { createdAt: '2026-08-01' } }),
        new Map(),
      ),
    ).toThrowError(
      'Range field "createdAt" must contain a start and end value',
    );
  });

  it('should reject unsupported date values before formatting', () => {
    formApi = new FormApi({
      fieldMappingTime: [
        ['createdAt', ['createdAtStart', 'createdAtEnd'], 'YYYY-MM-DD'],
      ],
    });

    expect(() =>
      formApi.mount(
        formActions({ meta: {}, values: { createdAt: [{}, new Date()] } }),
        new Map(),
      ),
    ).toThrowError('Range field "createdAt" contains an invalid date value');
  });

  it('should preserve null as no latest submission', () => {
    formApi.setLatestSubmissionValues({ name: 'test' });
    expect(formApi.getLatestSubmissionValues()).toEqual({ name: 'test' });

    formApi.setLatestSubmissionValues(null);
    expect(formApi.getLatestSubmissionValues()).toEqual({});
  });

  it('should set field value', async () => {
    const setFieldValueMock = vi.fn();
    const actions = formActions({
      meta: {},
      setFieldValue: setFieldValueMock,
      values: { name: 'test' },
    });

    await formApi.mount(actions, new Map());
    await formApi.setFieldValue('name', 'new value');
    expect(setFieldValueMock).toHaveBeenCalledWith(
      'name',
      'new value',
      undefined,
    );
  });

  it('should reset form', async () => {
    const resetFormMock = vi.fn();
    const actions = formActions({
      meta: {},
      resetForm: resetFormMock,
      values: { name: 'test' },
    });

    await formApi.mount(actions, new Map());
    await formApi.resetForm();
    expect(resetFormMock).toHaveBeenCalled();
  });

  it('should clear every field validation error', async () => {
    const setFieldError = vi.fn();
    await formApi.mount(
      formActions({
        errors: { value: { email: 'invalid', username: 'required' } },
        meta: {},
        setFieldError,
        values: {},
      }),
      new Map(),
    );

    await formApi.resetValidate();

    expect(setFieldError).toHaveBeenCalledTimes(2);
    expect(setFieldError).toHaveBeenCalledWith('email', undefined);
    expect(setFieldError).toHaveBeenCalledWith('username', undefined);
  });

  it('should call handleSubmit on submit', async () => {
    const handleSubmitMock = vi.fn();
    const actions = formActions({
      meta: {},
      submitForm: vi.fn().mockResolvedValue(true),
      values: { name: 'test' },
    });

    const state = {
      handleSubmit: handleSubmitMock,
    };

    formApi.setState(state);
    await formApi.mount(actions, new Map());

    const result = await formApi.submitForm();
    expect(actions.submitForm).toHaveBeenCalled();
    expect(handleSubmitMock).toHaveBeenCalledWith({ name: 'test' });
    expect(result).toEqual({ name: 'test' });
  });

  it('should unmount form and reset state', () => {
    formApi.unmount();
    expect(formApi.isMounted).toBe(false);
  });

  it('should validate form', async () => {
    const validateMock = vi.fn().mockResolvedValue(true);
    const actions = formActions({
      meta: {},
      validate: validateMock,
    });

    await formApi.mount(actions, new Map());
    const isValid = await formApi.validate();
    expect(validateMock).toHaveBeenCalled();
    expect(isValid).toBe(true);
  });
});

describe('updateSchema', () => {
  let instance: FormApi;

  beforeEach(() => {
    instance = new FormApi();
    instance.state = {
      schema: [
        { component: 'text', fieldName: 'name' },
        { component: 'number', fieldName: 'age', label: 'Age' },
      ],
    };
  });

  it('should update the schema correctly when fieldName matches', () => {
    const newSchema = [
      { component: 'text', fieldName: 'name' },
      { component: 'number', fieldName: 'age', label: 'Age' },
    ];

    instance.updateSchema(newSchema);

    expect(instance.state?.schema?.[0]?.component).toBe('text');
    expect(instance.state?.schema?.[1]?.label).toBe('Age');
  });

  it('should log an error if fieldName is missing in some items', () => {
    const newSchema: Partial<FormSchema>[] = [
      { component: 'textarea', fieldName: 'name' },
      { component: 'number' },
    ];

    const consoleErrorSpy = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});

    instance.updateSchema(newSchema);

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      'All items in the schema array must have a valid `fieldName` property to be updated',
    );
  });

  it('should not update schema if fieldName does not match', () => {
    const newSchema = [{ component: 'textarea', fieldName: 'unknown' }];

    instance.updateSchema(newSchema);

    expect(instance.state?.schema?.[0]?.component).toBe('text');
    expect(instance.state?.schema?.[1]?.component).toBe('number');
  });

  it('should not update schema if updatedMap is empty', () => {
    const newSchema: Partial<FormSchema>[] = [{ component: 'textarea' }];

    instance.updateSchema(newSchema);

    expect(instance.state?.schema?.[0]?.component).toBe('text');
    expect(instance.state?.schema?.[1]?.component).toBe('number');
  });
});
