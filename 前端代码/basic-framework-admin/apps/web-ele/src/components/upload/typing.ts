import type { AxiosResponse } from '@vben/request';

import type { AxiosProgressEvent } from '#/api/infra/file';

/** 上传结果状态枚举 */
export enum UploadResultStatus {
  DONE = 'done',
  ERROR = 'error',
  SUCCESS = 'success',
  UPLOADING = 'uploading',
}

/** 文件上传列表的显示类型 */
export type UploadListType = 'picture' | 'picture-card' | 'text';

export type UploadModelItem = number | string;
export type UploadModelValue = UploadModelItem | UploadModelItem[];

export interface UploadApiPayload {
  data?: string;
  url?: string;
  [key: string]: unknown;
}

export type UploadApiResult =
  | AxiosResponse<UploadApiPayload>
  | string
  | UploadApiPayload;

/** 文件上传组件属性 */
export interface FileUploadProps {
  accept?: string[]; // 根据后缀，或者其他
  api?: (
    file: File,
    onUploadProgress?: AxiosProgressEvent,
  ) => Promise<UploadApiResult>;
  directory?: string; // 上传的目录
  disabled?: boolean;
  drag?: boolean; // 是否支持拖拽上传
  helpText?: string;
  listType?: UploadListType;
  maxNumber?: number; // 最大数量的文件，Infinity 不限制
  modelValue?: UploadModelValue; // v-model 支持
  maxSize?: number; // 文件最大多少 MB
  multiple?: boolean; // 是否支持多选
  /** 是否允许匿名读取。默认 false；公开展示时由业务组件显式设为 true。 */
  publicRead?: boolean;
  /** 上传响应内的结果路径（例如 file.id）；路径不存在时上传失败。 */
  resultField?: string;
  showDescription?: boolean; // 是否显示下面的描述
  value?: UploadModelValue;
}
