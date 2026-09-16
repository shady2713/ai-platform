import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SystemNotifyMessageApi } from '#/api/system/notify/message';
import type { DescriptionItemSchema } from '#/components/description';

import { formatDateTime } from '@vben/utils';

import { getRangePickerDefaultProps } from '#/utils';

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'templateCode',
      label: '模板编码',
      component: 'Input',
      componentProps: {
        placeholder: '请输入模板编码',
        clearable: true,
      },
    },
    {
      fieldName: 'templateNickname',
      label: '发送人',
      component: 'Input',
      componentProps: {
        placeholder: '请输入发送人',
        clearable: true,
      },
    },
    {
      fieldName: 'readStatus',
      label: '是否已读',
      component: 'Select',
      componentProps: {
        options: [
          { label: '已读', value: true },
          { label: '未读', value: false },
        ],
        placeholder: '请选择',
        clearable: true,
      },
    },
    {
      fieldName: 'createTime',
      label: '创建时间',
      component: 'RangePicker',
      componentProps: {
        ...getRangePickerDefaultProps(),
        clearable: true,
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<SystemNotifyMessageApi.Message>['columns'] {
  return [
    {
      field: 'id',
      title: '编号',
      minWidth: 100,
    },
    {
      field: 'templateCode',
      title: '模板编码',
      minWidth: 130,
    },
    {
      field: 'templateNickname',
      title: '发送人',
      minWidth: 130,
    },
    {
      field: 'templateContent',
      title: '消息内容',
      minWidth: 300,
    },
    {
      field: 'readStatus',
      title: '是否已读',
      minWidth: 100,
      slots: {
        default: ({ row }) => {
          return row.readStatus ? '已读' : '未读';
        },
      },
    },
    {
      field: 'readTime',
      title: '阅读时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 120,
      fixed: 'right',
      slots: { default: 'actions' },
    },
  ];
}

/** 详情页的字段 */
export function useDetailSchema(): DescriptionItemSchema[] {
  return [
    { field: 'id', label: '编号' },
    { field: 'templateCode', label: '模板编码' },
    { field: 'templateNickname', label: '发送人' },
    { field: 'templateContent', label: '消息内容' },
    { field: 'templateType', label: '模板类型' },
    { field: 'templateParams', label: '模板参数' },
    { field: 'readStatus', label: '是否已读' },
    {
      field: 'readTime',
      label: '阅读时间',
      render: (val) =>
        formatDateTime(val as Date | string | undefined) as string,
    },
    {
      field: 'createTime',
      label: '创建时间',
      render: (val) =>
        formatDateTime(val as Date | string | undefined) as string,
    },
  ];
}
