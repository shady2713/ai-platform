import type { InfraFileApi } from '#/api/infra/file';

import { openWindow } from '@vben/utils';

import { fetchFileContent, FILE_ACCESS_TYPE } from '#/api/infra/file';

/** 私有文件的读取授权依赖 Authorization 头，不能直接打开 URL */
export function isPrivateFile(row: InfraFileApi.File) {
  return row.accessType === FILE_ACCESS_TYPE.PRIVATE;
}

/** 经认证请求获取文件内容并转为同源 object URL */
export async function fetchFileObjectUrl(row: InfraFileApi.File) {
  const blob = await fetchFileContent(row.url);
  return URL.createObjectURL(blob);
}

/** 通过临时链接触发浏览器下载 */
export function downloadObjectUrl(objectUrl: string, filename: string) {
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = filename;
  link.click();
}

/**
 * 打开文件：公开文件直开原 URL；私有文件先取回内容。
 * 图片与 PDF 在新标签页预览，其余类型按后端 attachment 语义触发下载。
 */
export async function openFile(row: InfraFileApi.File) {
  if (!isPrivateFile(row)) {
    openWindow(row.url);
    return;
  }
  const objectUrl = await fetchFileObjectUrl(row);
  // svg 是主动内容（可携带脚本），不在新标签页直接渲染，统一下载
  const previewable =
    row.type !== 'image/svg+xml' &&
    (row.type?.includes('image') || row.type?.includes('pdf'));
  if (previewable) {
    // object URL 是本页自创建的同源 blob，可信；openWindow 的协议白名单仅放行 http/https，
    // 用于拦截外部不可信协议，blob: 预览必须直接使用 window.open。
    // noopener 语义下 window.open 恒返回 null，无法据此判断弹窗是否被拦截；
    // blob URL 条目绑定创建页 document：延迟回收保证预览导航完成，创建页卸载时由浏览器兜底回收
    window.open(objectUrl, '_blank', 'noopener');
    setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
    return;
  }
  downloadObjectUrl(objectUrl, row.name || row.path || 'file');
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}
