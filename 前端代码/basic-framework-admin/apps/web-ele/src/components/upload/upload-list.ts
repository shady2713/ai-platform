import type { UploadFile } from 'element-plus';

import type {
  FileUploadProps,
  UploadApiResult,
  UploadModelItem,
  UploadModelValue,
} from './typing';

import { isString } from '@vben/utils';

import { UploadResultStatus } from './typing';
import { isSafeUploadUrl } from './upload-security';
import { resolveUploadUrl, resolveUploadValue } from './use-upload-core';

function modelItems(
  value: undefined | UploadModelValue,
  maxNumber: number,
): UploadModelItem[] {
  if (value === undefined || value === '') return [];
  if (Array.isArray(value)) return value;
  if (maxNumber > 1 && isString(value)) {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }
  return [value];
}

export function normalizeUploadFileList(
  value: undefined | UploadModelValue,
  maxNumber: number,
): UploadFile[] {
  return modelItems(value, maxNumber).map((item, index) => {
    const stringValue = String(item);
    const url = isString(item) && isSafeUploadUrl(item) ? item : undefined;
    return {
      name: url
        ? url.slice(Math.max(0, url.lastIndexOf('/') + 1))
        : stringValue,
      response: item,
      status: UploadResultStatus.SUCCESS,
      uid: -(index + 1),
      url,
    };
  });
}

function scalarUploadValue(file: UploadFile, resultField?: string) {
  const value = resolveUploadValue(file, resultField);
  if (
    !isString(value) &&
    !(typeof value === 'number' && Number.isFinite(value))
  ) {
    throw new Error('上传结果必须解析为字符串或数字');
  }
  return value;
}

export function getUploadModelValue(
  fileList: UploadFile[],
  props: Pick<
    FileUploadProps,
    'maxNumber' | 'modelValue' | 'resultField' | 'value'
  >,
): UploadModelValue {
  const list = fileList
    .filter((item) => item.status === UploadResultStatus.SUCCESS)
    .map((item) => scalarUploadValue(item, props.resultField));
  if (props.maxNumber === 1) return list[0] ?? '';
  const original = props.modelValue ?? props.value;
  return Array.isArray(original) ? list : list.join(',');
}

export function upsertUploadedFile(
  fileList: UploadFile[],
  file: File & { uid?: number },
  response: UploadApiResult,
  requireUrl: boolean,
) {
  const url = resolveUploadUrl(response);
  if (requireUrl && !url) {
    throw new Error('图片上传接口未返回安全的图片 URL');
  }
  const uploaded: UploadFile = {
    name: file.name,
    response,
    status: UploadResultStatus.SUCCESS,
    uid: file.uid ?? Date.now(),
    url,
  };
  const index = fileList.findIndex((item) => item.uid === file.uid);
  if (index === -1) fileList.push(uploaded);
  else fileList.splice(index, 1, uploaded);
}

export function removeUploadFile(fileList: UploadFile[], file: UploadFile) {
  removeUploadFileByUid(fileList, file.uid);
}

export function removeUploadFileByUid(
  fileList: UploadFile[],
  uid: number | undefined,
) {
  const index = fileList.findIndex((item) => item.uid === uid);
  if (index !== -1) fileList.splice(index, 1);
}
