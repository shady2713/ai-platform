/**
 * 默认图片类型
 */
export const defaultImageAccepts = ['bmp', 'gif', 'jpeg', 'jpg', 'png', 'webp'];

/**
 * 与服务端 FileTypeUtils 白名单保持一致的默认文件类型。
 */
export const defaultFileAccepts = [
  ...defaultImageAccepts,
  'pdf',
  'doc',
  'docx',
  'xls',
  'xlsx',
  'ppt',
  'pptx',
  'txt',
  'zip',
];

/**
 * 判断文件是否为图片
 *
 * @param filename 文件名
 * @param accepts 支持的文件类型
 * @returns 是否为图片
 */
export function isImage(
  filename: null | string | undefined,
  accepts: string[] = defaultImageAccepts,
): boolean {
  if (!filename || accepts.length === 0) {
    return false;
  }
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  return accepts.includes(ext);
}

/**
 * 判断文件是否为指定类型
 *
 * @param file 文件
 * @param accepts 支持的文件类型
 * @returns 是否为指定类型
 */
export function checkFileType(file: File, accepts: string[]) {
  if (!accepts || accepts.length === 0) {
    return true;
  }
  const fileName = file.name.toLowerCase();
  const mimeType = file.type.toLowerCase();
  return accepts.some((rawAccept) => {
    const accept = rawAccept.trim().toLowerCase();
    if (!accept) return false;
    if (accept.endsWith('/*')) {
      return mimeType.startsWith(accept.slice(0, -1));
    }
    if (accept.includes('/')) {
      return mimeType === accept;
    }
    const extension = accept.startsWith('.') ? accept : `.${accept}`;
    return fileName.endsWith(extension);
  });
}

/**
 * 格式化文件大小
 *
 * @param bytes 文件大小（字节）
 * @returns 格式化后的文件大小字符串
 */
export function formatFileSize(bytes: number, digits = 2): string {
  if (bytes === 0) {
    return '0 B';
  }
  const k = 1024;
  const unitArr = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
  const index = Math.floor(Math.log(bytes) / Math.log(k));
  return `${Number.parseFloat((bytes / k ** index).toFixed(digits))} ${unitArr[index]}`;
}

/**
 * 获取文件类型样式类（Tailwind CSS 渐变色）
 *
 * @param filename 文件名
 * @returns Tailwind CSS 渐变类名
 */
export function getFileTypeClass(filename: null | string | undefined): string {
  if (!filename) {
    return 'from-gray-500 to-gray-700';
  }
  const ext = filename.split('.').pop()?.toLowerCase() || '';
  if (isImage(ext)) {
    return 'from-yellow-400 to-orange-500';
  }
  if (['pdf'].includes(ext)) {
    return 'from-red-500 to-red-700';
  }
  if (['doc', 'docx'].includes(ext)) {
    return 'from-blue-600 to-blue-800';
  }
  if (['xls', 'xlsx'].includes(ext)) {
    return 'from-green-600 to-green-800';
  }
  if (['ppt', 'pptx'].includes(ext)) {
    return 'from-orange-600 to-orange-800';
  }
  if (['aac', 'm4a', 'mp3', 'wav'].includes(ext)) {
    return 'from-purple-500 to-purple-700';
  }
  if (['avi', 'mov', 'mp4', 'wmv'].includes(ext)) {
    return 'from-red-500 to-red-700';
  }
  return 'from-gray-500 to-gray-700';
}
