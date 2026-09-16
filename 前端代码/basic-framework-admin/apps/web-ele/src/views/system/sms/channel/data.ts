import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { z } from '#/adapter/form';

/** 新增/修改的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      fieldName: 'id',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      component: 'Select',
      fieldName: 'code',
      label: '短信渠道',
      componentProps: {
        options: getDictOptions(DICT_TYPE.SYSTEM_SMS_CHANNEL_CODE, 'string'),
        placeholder: '请选择短信渠道',
      },
      rules: z
        .string()
        .min(1, '请输入短信签名')
        .max(12, '短信签名最多 12 个字符'),
    },
    {
      component: 'Input',
      fieldName: 'signature',
      label: '短信签名',
      componentProps: {
        placeholder: '请输入短信签名',
      },
      rules: 'required',
    },
    {
      fieldName: 'status',
      label: '开启状态',
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
      },
      rules: z.number().default(CommonStatusEnum.ENABLE),
    },
    {
      component: 'VbenInputPassword',
      fieldName: 'apiKey',
      label: 'API Key',
      componentProps: {
        placeholder: '创建时必填；修改时留空则保留原账号',
      },
      dependencies: {
        triggerFields: ['id'],
        rules: (values) =>
          values.id
            ? z.string().max(128, 'API Key 最多 128 个字符').optional()
            : z
                .string()
                .min(1, '请输入 API Key')
                .max(128, 'API Key 最多 128 个字符'),
      },
    },
    {
      component: 'VbenInputPassword',
      fieldName: 'apiSecret',
      label: 'API Secret',
      componentProps: {
        placeholder: '创建时必填；修改时留空则保留原密钥',
      },
      dependencies: {
        triggerFields: ['id'],
        rules: (values) =>
          values.id
            ? z.string().max(256, 'API Secret 最多 256 个字符').optional()
            : z
                .string()
                .min(1, '请输入 API Secret')
                .max(256, 'API Secret 最多 256 个字符'),
      },
    },
    {
      component: 'Input',
      fieldName: 'callbackUrl',
      label: '回调地址',
      componentProps: {
        placeholder: '请输入回调地址',
      },
      rules: z
        .string()
        .max(255, '回调地址最多 255 个字符')
        .url('回调地址格式不正确')
        .refine((value) => /^https?:\/\//.test(value), '仅支持 HTTP 或 HTTPS')
        .optional()
        .or(z.literal('')),
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'Textarea',
      componentProps: {
        placeholder: '请输入备注',
      },
      rules: z.string().max(255, '备注最多 255 个字符').optional(),
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'signature',
      label: '短信签名',
      component: 'Input',
      componentProps: {
        placeholder: '请输入短信签名',
        clearable: true,
      },
    },
    {
      fieldName: 'code',
      label: '短信渠道',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.SYSTEM_SMS_CHANNEL_CODE, 'string'),
        placeholder: '请选择短信渠道',
        clearable: true,
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
        clearable: true,
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 100,
    },
    {
      field: 'signature',
      title: '短信签名',
      minWidth: 120,
    },
    {
      field: 'code',
      title: '渠道编码',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.SYSTEM_SMS_CHANNEL_CODE },
      },
    },
    {
      field: 'status',
      title: '开启状态',
      minWidth: 100,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.COMMON_STATUS },
      },
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 200,
    },
    {
      field: 'callbackUrl',
      title: '回调地址',
      minWidth: 200,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 160,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}
