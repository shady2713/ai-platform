import type { PageParam } from '@vben/request';

/** 通用导出参数，继承分页参数 */
export type ExportParams = PageParam;

/** 通用树节点数据结构 */
export interface TreeNodeData {
  id: number;
  label?: string;
  name?: string;
  children?: TreeNodeData[];
}

/** 数据库表查询参数 */
export interface DatabaseTableQueryParams {
  name?: string;
  comment?: string;
}
