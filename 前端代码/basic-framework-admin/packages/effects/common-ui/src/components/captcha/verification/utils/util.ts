import type { CaptchaPoint } from '@vben/types';

interface ElementSize {
  height: string;
  width: string;
}

function resolveSize(value: string, parentSize: number): string {
  if (!value.endsWith('%')) return value;
  const percentage = Number.parseFloat(value);
  return Number.isFinite(percentage)
    ? `${(percentage / 100) * parentSize}px`
    : '0px';
}

export function resetSize(
  rootElement: HTMLElement | null,
  barSize: ElementSize,
  imgSize: ElementSize,
) {
  const parentWidth =
    rootElement?.parentElement?.offsetWidth || window.innerWidth;
  const parentHeight =
    rootElement?.parentElement?.offsetHeight || window.innerHeight;

  return {
    barHeight: resolveSize(barSize.height, parentHeight),
    barWidth: resolveSize(barSize.width, parentWidth),
    imgHeight: resolveSize(imgSize.height, parentHeight),
    imgWidth: resolveSize(imgSize.width, parentWidth),
  };
}

export function scaleCaptchaPoints(
  points: CaptchaPoint[],
  imgSize: Pick<ReturnType<typeof resetSize>, 'imgHeight' | 'imgWidth'>,
): CaptchaPoint[] | undefined {
  const width = Number.parseFloat(imgSize.imgWidth);
  const height = Number.parseFloat(imgSize.imgHeight);
  if (
    !Number.isFinite(width) ||
    !Number.isFinite(height) ||
    width <= 0 ||
    height <= 0
  ) {
    return undefined;
  }
  if (
    points.some(
      ({ x, y }) =>
        !Number.isFinite(x) ||
        !Number.isFinite(y) ||
        x < 0 ||
        y < 0 ||
        x > width ||
        y > height,
    )
  ) {
    return undefined;
  }
  return points.map(({ x, y }) => ({
    x: Math.round((310 * x) / width),
    y: Math.round((155 * y) / height),
  }));
}

export const _code_chars = [
  1,
  2,
  3,
  4,
  5,
  6,
  7,
  8,
  9,
  'a',
  'b',
  'c',
  'd',
  'e',
  'f',
  'g',
  'h',
  'i',
  'j',
  'k',
  'l',
  'm',
  'n',
  'o',
  'p',
  'q',
  'r',
  's',
  't',
  'u',
  'v',
  'w',
  'x',
  'y',
  'z',
  'A',
  'B',
  'C',
  'D',
  'E',
  'F',
  'G',
  'H',
  'I',
  'J',
  'K',
  'L',
  'M',
  'N',
  'O',
  'P',
  'Q',
  'R',
  'S',
  'T',
  'U',
  'V',
  'W',
  'X',
  'Y',
  'Z',
];
export const _code_color1 = ['#fffff0', '#f0ffff', '#f0fff0', '#fff0f0'];
export const _code_color2 = [
  '#FF0033',
  '#006699',
  '#993366',
  '#FF9900',
  '#66CC66',
  '#FF33CC',
];
