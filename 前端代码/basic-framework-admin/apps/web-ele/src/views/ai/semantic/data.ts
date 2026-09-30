import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { AiSemanticApi } from '#/api/ai/semantic';

import { z } from '@vben/common-ui';

/** 主数据映射权限码（与 V93 迁移的 system_menu 种子 4118/4119 一一对应） */
export const AI_SEMANTIC_PERMISSIONS = {
  manage: 'ai:semantic:manage',
  query: 'ai:semantic:query',
} as const;

/** 对象类型选项（与服务端 AiMasterObjectType 词表一一对应，未知取值无法提交） */
export const OBJECT_TYPE_OPTIONS: Array<{
  label: string;
  value: AiSemanticApi.MasterObjectType;
}> = [
  { label: '客户', value: 'CUSTOMER' },
  { label: '供应商', value: 'SUPPLIER' },
  { label: '产品/物料', value: 'PRODUCT' },
  { label: '员工', value: 'EMPLOYEE' },
  { label: '组织/法人', value: 'ORGANIZATION' },
  { label: '其它', value: 'OTHER' },
];

/** 匹配方式选项：两种方式都必须给出源键，没有"按名称自动匹配" */
export const MATCH_METHOD_OPTIONS: Array<{
  label: string;
  value: AiSemanticApi.MatchMethod;
}> = [
  { label: '人工登记', value: 'MANUAL' },
  { label: '可信主数据导入', value: 'TRUSTED_FEED' },
];

/** 对象状态说明（停用即阻断判定） */
export const OBJECT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '停用（判定阻断）',
};

/** 映射版本状态说明 */
export const REVISION_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿（可编辑，不可用于判定）',
  PUBLISHED: '已发布（不可变，判定依据）',
};

/** 条目问题说明（发现如实展示问题，判定一律阻断） */
export const PROBLEM_LABELS: Record<string, string> = {
  CONFLICT: '冲突（一对多/多对一，必须人工处理）',
  EXPIRED: '有效期不覆盖判定时刻',
  NONE: '可判定',
};

/** 不按同名合并的前端提示（与后端"展示名不参与判定"一致） */
export const NO_NAME_INFERENCE_HINT =
  '平台只按显式登记的（来源系统, 实体类型, 源键）关联：展示名只用于人工核对，同名不会合并，未登记的源键反查返回"未登记"。';

/** 版本固定提示（旧报表按受理时的版本解释） */
export const VERSION_PIN_HINT =
  '判定必须显式给出对象标识、版本号与判定时刻：已发布版本不可修改，旧报表/旧产物按受理时的版本与冻结指纹解释，发布新版本不改旧结果。';

/** 冲突与过期阻断提示 */
export const BLOCKING_HINT =
  '一对多（同对象同系统多条源键时间窗重叠）与多对一（同源键属于多个对象）在发布与判定两侧都会阻断；有效期不覆盖判定时刻同样阻断。平台不会替你挑一个。';

/** 目录发现提示（与 Y01 授权发现同构） */
export const CATALOG_HINT =
  '目录只列出当前主体可访问系统里的映射事实：无权系统不出现，主体无任何可访问系统时返回与"未登记"同形的空目录；送进模型的目录不含源键值。';

/** 判定/发布使用的时间格式：后端 LocalDateTime（秒精度，无时区后缀） */
export function toIsoSeconds(value: Date): string {
  const pad = (input: number) => String(input).padStart(2, '0');
  return (
    `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}` +
    `T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
  );
}

/** 当前时刻（判定时刻必须显式提交，不做"服务端取现在"的省略） */
export function nowIsoSeconds(): string {
  return toIsoSeconds(new Date());
}

/** 有效期展示：为空表示长期有效（与后端 null 语义一致） */
export function formatWindow(
  validFrom?: null | string,
  validTo?: null | string,
): string {
  const from = validFrom ? validFrom.replace('T', ' ') : '未设置';
  const to = validTo ? validTo.replace('T', ' ') : '长期有效';
  return `${from} ~ ${to}`;
}

/** 问题标签类型（页面用颜色区分"可判定/阻断"，不隐藏问题） */
export function problemTagType(
  problem: string,
): 'danger' | 'success' | 'warning' {
  if (problem === 'CONFLICT') {
    return 'danger';
  }
  if (problem === 'EXPIRED') {
    return 'warning';
  }
  return 'success';
}

/** 反查结论文案（未登记也是有效结论：未映射即不关联） */
export function reverseConclusion(row: AiSemanticApi.MappingReverse): string {
  if (!row.mapped) {
    return '未登记（未映射即不关联）';
  }
  return `${row.objectName ?? ''}（${row.objectCode ?? ''}）`;
}

/** 对象列表查询表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { clearable: true, placeholder: '请输入对象标识或名称' },
      fieldName: 'keyword',
      label: '关键字',
    },
    {
      component: 'Select',
      componentProps: {
        clearable: true,
        options: OBJECT_TYPE_OPTIONS,
        placeholder: '请选择对象类型',
      },
      fieldName: 'objectType',
      label: '对象类型',
    },
    {
      component: 'Select',
      componentProps: {
        clearable: true,
        options: [
          { label: '启用', value: 'ACTIVE' },
          { label: '停用', value: 'DISABLED' },
        ],
        placeholder: '请选择状态',
      },
      fieldName: 'status',
      label: '状态',
    },
  ];
}

/** 对象列表列 */
export function useGridColumns(): VxeTableGridOptions['columns'] {
  return [
    { field: 'objectCode', minWidth: 180, title: '对象标识' },
    { field: 'objectName', minWidth: 180, title: '对象名称' },
    {
      field: 'objectType',
      minWidth: 120,
      title: '对象类型',
      formatter: ({ cellValue }) =>
        OBJECT_TYPE_OPTIONS.find((option) => option.value === cellValue)
          ?.label ?? String(cellValue ?? ''),
    },
    {
      field: 'currentRevision',
      minWidth: 140,
      title: '当前映射版本',
      formatter: ({ cellValue }) =>
        Number(cellValue) > 0 ? `v${cellValue}` : '尚无已发布版本',
    },
    {
      field: 'status',
      minWidth: 140,
      title: '状态',
      formatter: ({ cellValue }) =>
        OBJECT_STATUS_LABELS[String(cellValue)] ?? '',
    },
    {
      field: 'version',
      title: '乐观锁版本',
      width: 110,
    },
  ];
}

/** 对象新建/修改表单 */
export function useObjectFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'Input',
      componentProps: { maxlength: 64, placeholder: '如 md_cloud_customer' },
      fieldName: 'objectCode',
      label: '对象标识',
      rules: z
        .string()
        .regex(
          /^[A-Z][\w-]{2,63}$/i,
          '对象标识需字母开头，仅含字母数字与 _-，长度 3..64',
        ),
    },
    {
      component: 'Input',
      componentProps: { maxlength: 128 },
      fieldName: 'objectName',
      label: '对象名称',
      rules: z
        .string()
        .min(1, '对象名称必填')
        .max(128, '对象名称不能超过 128 字'),
    },
    {
      component: 'Select',
      componentProps: { options: OBJECT_TYPE_OPTIONS },
      defaultValue: 'CUSTOMER',
      fieldName: 'objectType',
      label: '对象类型',
    },
    {
      component: 'Input',
      componentProps: { maxlength: 512 },
      fieldName: 'description',
      label: '说明',
    },
  ];
}

/** 新建映射版本草稿表单（版本有效期是判定条件） */
export function useRevisionFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'DatePicker',
      componentProps: {
        showTime: true,
        type: 'datetime',
        valueFormat: 'YYYY-MM-DDTHH:mm:ss',
      },
      fieldName: 'validFrom',
      label: '版本生效起点',
      rules: z.string().min(1, '版本生效起点必填'),
    },
    {
      component: 'DatePicker',
      componentProps: {
        showTime: true,
        type: 'datetime',
        valueFormat: 'YYYY-MM-DDTHH:mm:ss',
      },
      fieldName: 'validTo',
      label: '版本失效终点（留空=长期有效）',
    },
  ];
}

/** 登记源键映射表单（源键与匹配方式必填；展示名只是展示） */
export function useEntryFormSchema(): VbenFormSchema[] {
  return [
    {
      component: 'InputNumber',
      componentProps: { min: 1 },
      fieldName: 'applicationId',
      label: '来源系统编号',
      rules: z.number().int().positive('来源系统编号必须为正整数'),
    },
    {
      component: 'Input',
      componentProps: { maxlength: 32, placeholder: '如 customer' },
      fieldName: 'entityType',
      label: '实体类型',
      rules: z
        .string()
        .regex(/^[a-z][a-z0-9_]{0,31}$/, '实体类型为小写标识符（如 customer）'),
    },
    {
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '来源系统里的业务主键' },
      fieldName: 'sourceKey',
      label: '源键',
      rules: z
        .string()
        .min(1, '源键必填（平台不按名称推断）')
        .max(128, '源键不能超过 128 字符'),
    },
    {
      component: 'Input',
      componentProps: { maxlength: 128, placeholder: '仅用于人工核对' },
      fieldName: 'sourceName',
      label: '展示名',
    },
    {
      component: 'Select',
      componentProps: { options: MATCH_METHOD_OPTIONS },
      defaultValue: 'MANUAL',
      fieldName: 'matchMethod',
      label: '匹配方式',
    },
    {
      component: 'DatePicker',
      componentProps: {
        showTime: true,
        type: 'datetime',
        valueFormat: 'YYYY-MM-DDTHH:mm:ss',
      },
      fieldName: 'validFrom',
      label: '源键生效起点',
      rules: z.string().min(1, '源键生效起点必填'),
    },
    {
      component: 'DatePicker',
      componentProps: {
        showTime: true,
        type: 'datetime',
        valueFormat: 'YYYY-MM-DDTHH:mm:ss',
      },
      fieldName: 'validTo',
      label: '源键失效终点（留空=长期有效）',
    },
  ];
}
