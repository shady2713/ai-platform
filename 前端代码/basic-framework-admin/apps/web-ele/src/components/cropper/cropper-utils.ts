export function toCssDimension(value: number | string): string {
  return typeof value === 'number' ? `${value}px` : value;
}

export function isImageFile(file: Pick<File, 'type'>): boolean {
  return file.type.startsWith('image/');
}
