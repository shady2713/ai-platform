<script lang="ts" setup>
import type { UploadFile } from 'element-plus';

import { useVbenModal } from '@vben/common-ui';
import { downloadFileFromBlobPart } from '@vben/utils';

import { ElButton, ElUpload } from 'element-plus';

import { useVbenForm } from '#/adapter/form';
import { importUser, importUserTemplate } from '#/api/system/user';
import { $t } from '#/locales';
import { showAlertDialog, showSuccessMessage } from '#/utils/feedback';

import { useImportFormSchema } from '../data';

const emit = defineEmits(['success']);

interface ImportUserValues {
  file: File;
  updateSupport: boolean;
}

const [Form, formApi] = useVbenForm({
  commonConfig: {
    formItemClass: 'col-span-2',
    labelWidth: 120,
  },
  layout: 'horizontal',
  schema: useImportFormSchema(),
  showDefaultActions: false,
});

/** 转义 HTML，避免用户名中的特殊字符破坏弹窗结构 */
function escapeHtml(value: string) {
  return value.replaceAll(
    /[&<>"']/g,
    (c) =>
      ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;',
      })[c] as string,
  );
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    // 提交表单
    const data = await formApi.getValues<ImportUserValues>();
    try {
      const result = await importUser(data.file, data.updateSupport);
      const createCount = result?.createUsernames?.length ?? 0;
      const updateCount = result?.updateUsernames?.length ?? 0;
      const failureEntries = Object.entries(result?.failureUsernames ?? {});
      const failureCount = failureEntries.length;

      // 关闭弹窗并刷新列表
      await modalApi.close();
      emit('success');

      if (failureCount > 0) {
        // 有失败时使用 MessageBox 展示明细，避免被 Message 截断
        const failureItems = failureEntries
          .map(
            ([username, msg]) =>
              `<li>${escapeHtml(username)}：${escapeHtml(String(msg))}</li>`,
          )
          .join('');
        showAlertDialog(
          `<div>
            <p>新增 ${createCount} 个，更新 ${updateCount} 个，失败 ${failureCount} 个。失败明细：</p>
            <ul class="mt-2 max-h-[300px] overflow-y-auto pl-5">${failureItems}</ul>
          </div>`,
          '导入结果',
          {
            confirmButtonText: '确定',
            dangerouslyUseHTMLString: true,
            type: 'warning',
          },
        );
      } else {
        showSuccessMessage(
          `${$t('ui.actionMessage.operationSuccess')}：新增 ${createCount} 个，更新 ${updateCount} 个`,
        );
      }
    } finally {
      modalApi.unlock();
    }
  },
});

/** 文件改变时 */
function handleChange(file: UploadFile) {
  if (file.raw) {
    formApi.setFieldValue('file', file.raw as File);
  }
}

/** 下载模版 */
async function handleDownload() {
  const data = await importUserTemplate();
  downloadFileFromBlobPart({ fileName: '用户导入模板.xls', source: data });
}
</script>

<template>
  <Modal title="导入用户" class="w-1/3">
    <Form class="mx-4">
      <template #file>
        <div class="w-full">
          <ElUpload
            :limit="1"
            accept=".xls,.xlsx"
            :on-change="handleChange"
            :auto-upload="false"
          >
            <ElButton type="primary"> 选择 Excel 文件 </ElButton>
          </ElUpload>
        </div>
      </template>
    </Form>
    <template #prepend-footer>
      <div class="flex flex-auto items-center">
        <ElButton @click="handleDownload"> 下载导入模板 </ElButton>
      </div>
    </template>
  </Modal>
</template>
