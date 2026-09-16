import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  useCrudActions,
  useCrudDeleteActions,
  useCrudItemActions,
} from './use-crud-actions';

const {
  closeLoadingMock,
  loadingServiceMock,
  showConfirmDialogMock,
  showSuccessMessageMock,
} = vi.hoisted(() => ({
  closeLoadingMock: vi.fn(),
  loadingServiceMock: vi.fn(() => ({ close: closeLoadingMock })),
  showConfirmDialogMock: vi.fn(),
  showSuccessMessageMock: vi.fn(),
}));

vi.mock('element-plus', () => ({
  ElLoading: {
    service: loadingServiceMock,
  },
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: showConfirmDialogMock,
  showSuccessMessage: showSuccessMessageMock,
}));

describe('useCrudDeleteActions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    showConfirmDialogMock.mockResolvedValue('confirm');
  });

  it('preserves selection and closes loading when batch deletion fails', async () => {
    const failure = new Error('conflict');
    const batchDeleteApi = vi.fn().mockRejectedValue(failure);
    const refresh = vi.fn();
    const actions = useCrudDeleteActions({
      batchDeleteApi,
      deleteApi: vi.fn(),
      getRowKey: (row: { id: number }) => row.id,
      refresh,
    });
    actions.checkedIds.value = [1, 2];

    await expect(actions.handleDeleteBatch()).rejects.toBe(failure);

    expect(batchDeleteApi).toHaveBeenCalledWith([1, 2]);
    expect(actions.checkedIds.value).toEqual([1, 2]);
    expect(refresh).not.toHaveBeenCalled();
    expect(showSuccessMessageMock).not.toHaveBeenCalled();
    expect(closeLoadingMock).toHaveBeenCalledOnce();
  });

  it('does not start deletion when the precondition rejects the row', async () => {
    const beforeDelete = vi.fn().mockResolvedValue(false);
    const deleteApi = vi.fn();
    const refresh = vi.fn();
    const actions = useCrudDeleteActions({
      batchDeleteApi: vi.fn(),
      beforeDelete,
      deleteApi,
      getRowKey: (row: { id: string }) => row.id,
      refresh,
    });

    await actions.handleDelete({ id: 'protected' });

    expect(beforeDelete).toHaveBeenCalledWith({ id: 'protected' });
    expect(deleteApi).not.toHaveBeenCalled();
    expect(loadingServiceMock).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });

  it('deletes a named row and refreshes after success', async () => {
    const deleteApi = vi.fn().mockResolvedValue(undefined);
    const refresh = vi.fn();
    const actions = useCrudDeleteActions({
      batchDeleteApi: vi.fn(),
      deleteApi,
      getDeleteName: (row: { id: number; name: string }) => row.name,
      getRowKey: (row) => row.id,
      refresh,
    });

    await actions.handleDelete({ id: 7, name: '管理员' });

    expect(deleteApi).toHaveBeenCalledWith(7);
    expect(showSuccessMessageMock).toHaveBeenCalledWith(
      'ui.actionMessage.deleteSuccess',
    );
    expect(refresh).toHaveBeenCalledOnce();
    expect(closeLoadingMock).toHaveBeenCalledOnce();
  });

  it('closes row loading and preserves state when deletion fails', async () => {
    const failure = new Error('network');
    const refresh = vi.fn();
    const actions = useCrudDeleteActions({
      batchDeleteApi: vi.fn(),
      deleteApi: vi.fn().mockRejectedValue(failure),
      getRowKey: (row: { id: number }) => row.id,
      refresh,
    });

    await expect(actions.handleDelete({ id: 1 })).rejects.toBe(failure);

    expect(showSuccessMessageMock).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(closeLoadingMock).toHaveBeenCalledOnce();
  });

  it('returns without side effects when batch confirmation is cancelled', async () => {
    const batchDeleteApi = vi.fn();
    showConfirmDialogMock.mockRejectedValue(new Error('cancelled'));
    const actions = useCrudDeleteActions({
      batchDeleteApi,
      deleteApi: vi.fn(),
      getRowKey: (row: { id: number }) => row.id,
      refresh: vi.fn(),
    });
    actions.checkedIds.value = [1];

    await actions.handleDeleteBatch();

    expect(batchDeleteApi).not.toHaveBeenCalled();
    expect(actions.checkedIds.value).toEqual([1]);
    expect(loadingServiceMock).not.toHaveBeenCalled();
  });

  it('clears selection and refreshes after batch deletion succeeds', async () => {
    const batchDeleteApi = vi.fn().mockResolvedValue(undefined);
    const refresh = vi.fn();
    const actions = useCrudDeleteActions({
      batchDeleteApi,
      deleteApi: vi.fn(),
      getRowKey: (row: { id: number }) => row.id,
      refresh,
    });
    actions.handleRowCheckboxChange({ records: [{ id: 2 }, { id: 5 }] });

    await actions.handleDeleteBatch();

    expect(batchDeleteApi).toHaveBeenCalledWith([2, 5]);
    expect(actions.checkedIds.value).toEqual([]);
    expect(showSuccessMessageMock).toHaveBeenCalledWith(
      'ui.actionMessage.deleteSuccess',
    );
    expect(refresh).toHaveBeenCalledOnce();
    expect(closeLoadingMock).toHaveBeenCalledOnce();
  });

  it('exposes grid selection handlers while allowing explicit overrides', () => {
    const actions = useCrudDeleteActions({
      batchDeleteApi: vi.fn(),
      deleteApi: vi.fn(),
      getRowKey: (row: { key: string }) => row.key,
      refresh: vi.fn(),
    });
    actions.handleRowCheckboxChange({ records: [{ key: 'a' }, { key: 'b' }] });
    const override = vi.fn();
    const events = actions.getGridEvents({ checkboxChange: override });

    expect(actions.checkedIds.value).toEqual(['a', 'b']);
    expect(events.checkboxAll).toBe(actions.handleRowCheckboxChange);
    expect(events.checkboxChange).toBe(override);
    events.proxyQuery();
    expect(actions.checkedIds.value).toEqual([]);
  });
});

describe('cRUD item actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('opens the modal with create and edit data', () => {
    const open = vi.fn();
    const modalApi = {
      open,
      setData: vi.fn(),
    };
    modalApi.setData.mockReturnValue(modalApi);
    const actions = useCrudItemActions({
      createData: () => ({ id: 0, name: 'new' }),
      deleteApi: vi.fn(),
      getRowKey: (row: { id: number; name: string }) => row.id,
      modalApi,
      refresh: vi.fn(),
    });
    const existing = { id: 9, name: 'existing' };

    actions.handleCreate();
    actions.handleEdit(existing);

    expect(modalApi.setData).toHaveBeenNthCalledWith(1, {
      id: 0,
      name: 'new',
    });
    expect(modalApi.setData).toHaveBeenNthCalledWith(2, existing);
    expect(open).toHaveBeenCalledTimes(2);
  });

  it('uses null create data and the default id accessor in combined actions', async () => {
    const open = vi.fn();
    const modalApi = { open, setData: vi.fn() };
    modalApi.setData.mockReturnValue(modalApi);
    const deleteApi = vi.fn().mockResolvedValue(undefined);
    const actions = useCrudActions({
      batchDeleteApi: vi.fn(),
      deleteApi,
      modalApi,
      refresh: vi.fn(),
    });

    actions.handleCreate();
    await actions.handleDelete({ id: 13 });

    expect(modalApi.setData).toHaveBeenCalledWith(null);
    expect(open).toHaveBeenCalledOnce();
    expect(deleteApi).toHaveBeenCalledWith(13);
  });
});
