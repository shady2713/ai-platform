import type { Ref } from 'vue';

import { ref } from 'vue';

import { ElLoading } from 'element-plus';

import { $t } from '#/locales';
import { showConfirmDialog, showSuccessMessage } from '#/utils/feedback';

type RowKey = number | string;

type RowWithId<Id extends RowKey = number> = {
  id?: Id;
};

type ModalApi<Row> = {
  open: () => unknown;
  setData: (data: null | Row) => ModalApi<Row>;
};

type DeleteOptions<Row, Id extends RowKey> = {
  beforeDelete?: (row: Row) => boolean | Promise<boolean>;
  deleteApi: (id: Id) => Promise<unknown>;
  getDeleteName?: (row: Row) => string;
  getRowKey: (row: Row) => Id;
  refresh: () => void;
};

type UseCrudItemActionsOptions<Row, Id extends RowKey> = DeleteOptions<
  Row,
  Id
> & {
  createData?: () => null | Row;
  modalApi: ModalApi<Row>;
};

type UseCrudDeleteActionsOptions<Row, Id extends RowKey> = DeleteOptions<
  Row,
  Id
> & {
  batchDeleteApi: (ids: Id[]) => Promise<unknown>;
};

type UseCrudActionsOptions<
  Row extends RowWithId<Id>,
  Id extends RowKey = number,
> = Omit<UseCrudDeleteActionsOptions<Row, Id>, 'getRowKey'> & {
  createData?: () => null | Row;
  getRowKey?: (row: Row) => Id;
  modalApi: ModalApi<Row>;
};

async function deleteRow<Row, Id extends RowKey>({
  beforeDelete,
  deleteApi,
  getDeleteName,
  getRowKey,
  refresh,
  row,
}: DeleteOptions<Row, Id> & { row: Row }) {
  if (beforeDelete && !(await beforeDelete(row))) {
    return;
  }

  const name = getDeleteName?.(row) || '';
  const loadingInstance = ElLoading.service({
    text: $t('ui.actionMessage.deleting', [name]),
  });
  try {
    await deleteApi(getRowKey(row));
    const message = name
      ? $t('ui.actionMessage.deleteSuccess', [name])
      : $t('ui.actionMessage.deleteSuccess');
    showSuccessMessage(message);
    refresh();
  } finally {
    loadingInstance.close();
  }
}

export function useCrudDeleteActions<Row, Id extends RowKey = number>(
  options: UseCrudDeleteActionsOptions<Row, Id>,
) {
  const { batchDeleteApi, getRowKey, refresh } = options;
  const checkedIds = ref<Id[]>([]) as Ref<Id[]>;

  async function handleDelete(row: Row) {
    await deleteRow({ ...options, row });
  }

  async function handleDeleteBatch() {
    try {
      await showConfirmDialog($t('ui.actionMessage.deleteBatchConfirm'), {
        confirmButtonText: $t('common.confirm'),
        cancelButtonText: $t('common.cancel'),
        type: 'warning',
      });
    } catch {
      // 用户取消（点击取消 / 关闭对话框 / ESC），按预期直接结束
      return;
    }
    const loadingInstance = ElLoading.service({
      text: $t('ui.actionMessage.deletingBatch'),
    });
    try {
      await batchDeleteApi(checkedIds.value);
      checkedIds.value = [];
      showSuccessMessage($t('ui.actionMessage.deleteSuccess', ['']).trim());
      refresh();
    } finally {
      loadingInstance.close();
    }
  }

  function handleRowCheckboxChange({ records }: { records: Row[] }) {
    checkedIds.value = records.map((item) => getRowKey(item));
  }

  function clearCheckedIds() {
    checkedIds.value = [];
  }

  function getGridEvents(extraEvents: Record<string, unknown> = {}) {
    return {
      checkboxAll: handleRowCheckboxChange,
      checkboxChange: handleRowCheckboxChange,
      proxyQuery: clearCheckedIds,
      ...extraEvents,
    };
  }

  return {
    checkedIds,
    clearCheckedIds,
    getGridEvents,
    handleDelete,
    handleDeleteBatch,
    handleRowCheckboxChange,
  };
}

export function useCrudItemActions<Row, Id extends RowKey = number>(
  options: UseCrudItemActionsOptions<Row, Id>,
) {
  const { createData, modalApi } = options;

  async function handleDelete(row: Row) {
    await deleteRow({ ...options, row });
  }

  function handleCreate() {
    modalApi.setData(createData?.() || null).open();
  }

  function handleEdit(row: Row) {
    modalApi.setData(row).open();
  }

  return {
    handleCreate,
    handleDelete,
    handleEdit,
  };
}

export function useCrudActions<
  Row extends RowWithId<Id>,
  Id extends RowKey = number,
>({
  batchDeleteApi,
  beforeDelete,
  createData,
  deleteApi,
  getDeleteName,
  getRowKey,
  modalApi,
  refresh,
}: UseCrudActionsOptions<Row, Id>) {
  const deleteActions = useCrudDeleteActions<Row, Id>({
    batchDeleteApi,
    beforeDelete,
    deleteApi,
    getDeleteName,
    getRowKey: getRowKey || ((row) => row.id as Id),
    refresh,
  });

  function handleCreate() {
    modalApi.setData(createData?.() || null).open();
  }

  function handleEdit(row: Row) {
    modalApi.setData(row).open();
  }

  return {
    ...deleteActions,
    handleCreate,
    handleEdit,
  };
}
