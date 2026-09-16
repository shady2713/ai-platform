import type Cropper from 'cropperjs';

import { flushPromises, mount } from '@vue/test-utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CropperImage from './cropper.vue';

const cropperMocks = vi.hoisted(() => ({
  canvas: undefined as HTMLCanvasElement | undefined,
  destroy: vi.fn(),
  getData: vi.fn(() => ({ height: 40, width: 40 })),
  options: undefined as Cropper.Options<HTMLImageElement> | undefined,
}));

vi.mock('cropperjs', () => ({
  default: class CropperStub {
    constructor(
      _element: HTMLImageElement,
      options?: Cropper.Options<HTMLImageElement>,
    ) {
      cropperMocks.options = options;
    }

    destroy() {
      cropperMocks.destroy();
    }

    getCroppedCanvas() {
      return cropperMocks.canvas;
    }

    getData() {
      return cropperMocks.getData();
    }
  },
}));

class FileReaderStub {
  static mode: 'error' | 'invalid' | 'success' = 'success';

  error: DOMException | null = null;
  result: ArrayBuffer | null | string = null;

  private readonly listeners = new Map<
    string,
    EventListenerOrEventListenerObject
  >();

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    this.listeners.set(type, listener);
  }

  readAsDataURL() {
    if (FileReaderStub.mode === 'error') {
      this.error = new DOMException('read failed');
      this.dispatch('error');
      return;
    }
    this.result =
      FileReaderStub.mode === 'invalid'
        ? new ArrayBuffer(0)
        : 'data:image/png;base64,YQ==';
    this.dispatch('loadend');
  }

  private dispatch(type: string) {
    const listener = this.listeners.get(type);
    if (!listener) return;
    const event = { target: this } as unknown as Event;
    if (typeof listener === 'function') listener(event);
    else listener.handleEvent(event);
  }
}

function createCanvas(blob: Blob | null = new Blob(['image'])) {
  return {
    height: 40,
    toBlob: vi.fn((callback: BlobCallback) => callback(blob)),
    width: 40,
  } as unknown as HTMLCanvasElement;
}

function requireOptions() {
  expect(cropperMocks.options).toBeDefined();
  return cropperMocks.options as Cropper.Options<HTMLImageElement>;
}

beforeEach(() => {
  vi.clearAllMocks();
  FileReaderStub.mode = 'success';
  cropperMocks.canvas = createCanvas();
  cropperMocks.options = undefined;
  vi.stubGlobal('FileReader', FileReaderStub);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('cropperImage', () => {
  it('preserves CSS units, forwards lifecycle callbacks, and emits previews', async () => {
    const onReady = vi.fn();
    const onCrop = vi.fn();
    const onZoom = vi.fn();
    const onCropMove = vi.fn();
    const wrapper = mount(CropperImage, {
      attrs: { class: 'consumer-class' },
      props: {
        height: '50%',
        options: {
          crop: onCrop,
          cropmove: onCropMove,
          ready: onReady,
          zoom: onZoom,
        },
        src: 'avatar.png',
      },
    });

    expect(wrapper.attributes('style')).toContain('height: 50%');
    expect(wrapper.classes()).toContain('consumer-class');

    const options = requireOptions();
    options.ready?.({} as Cropper.ReadyEvent<HTMLImageElement>);
    options.crop?.({} as Cropper.CropEvent<HTMLImageElement>);
    options.zoom?.({} as Cropper.ZoomEvent<HTMLImageElement>);
    options.cropmove?.({} as Cropper.CropMoveEvent<HTMLImageElement>);
    await flushPromises();

    expect(onReady).toHaveBeenCalledOnce();
    expect(onCrop).toHaveBeenCalledOnce();
    expect(onZoom).toHaveBeenCalledOnce();
    expect(onCropMove).toHaveBeenCalledOnce();
    expect(wrapper.emitted('ready')).toHaveLength(1);
    expect(wrapper.emitted('cropend')?.[0]?.[0]).toMatchObject({
      imgBase64: 'data:image/png;base64,YQ==',
      imgInfo: { height: 40, width: 40 },
    });

    wrapper.unmount();
    expect(cropperMocks.destroy).toHaveBeenCalledOnce();
  });

  it('does not create previews when real-time preview is disabled', () => {
    const wrapper = mount(CropperImage, {
      props: { realTimePreview: false, src: 'avatar.png' },
    });

    requireOptions().ready?.({} as Cropper.ReadyEvent<HTMLImageElement>);

    expect(wrapper.emitted('ready')).toHaveLength(1);
    expect(cropperMocks.getData).not.toHaveBeenCalled();
  });

  it.each([
    ['empty canvas blob', 'empty'] as const,
    ['invalid file-reader result', 'invalid'] as const,
    ['file-reader error', 'error'] as const,
  ])('reports %s instead of failing silently', (_label, mode) => {
    if (mode === 'empty') cropperMocks.canvas = createCanvas(null);
    else FileReaderStub.mode = mode;
    const wrapper = mount(CropperImage, { props: { src: 'avatar.png' } });

    requireOptions().ready?.({} as Cropper.ReadyEvent<HTMLImageElement>);

    expect(wrapper.emitted('cropend')).toBeUndefined();
    expect(wrapper.emitted('cropendError')).toHaveLength(1);
  });

  it('reports canvas exceptions and missing circular canvas contexts', () => {
    cropperMocks.getData.mockImplementationOnce(() => {
      throw new Error('tainted canvas');
    });
    const plain = mount(CropperImage, { props: { src: 'avatar.png' } });
    requireOptions().ready?.({} as Cropper.ReadyEvent<HTMLImageElement>);
    expect(plain.emitted('cropendError')).toHaveLength(1);
    plain.unmount();

    const getContext = vi
      .spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockReturnValue(null);
    const circled = mount(CropperImage, {
      props: { circled: true, src: 'avatar.png' },
    });
    requireOptions().ready?.({} as Cropper.ReadyEvent<HTMLImageElement>);
    expect(circled.emitted('cropendError')).toHaveLength(1);
    getContext.mockRestore();
  });

  it('composites circular crops onto a new canvas', () => {
    const context = {
      arc: vi.fn(),
      beginPath: vi.fn(),
      drawImage: vi.fn(),
      fill: vi.fn(),
      globalCompositeOperation: '',
      imageSmoothingEnabled: false,
    };
    const getContext = vi
      .spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockReturnValue(context as unknown as CanvasRenderingContext2D);
    const toBlob = vi
      .spyOn(HTMLCanvasElement.prototype, 'toBlob')
      .mockImplementation((callback) => callback(new Blob(['rounded'])));
    const wrapper = mount(CropperImage, {
      props: { circled: true, src: 'avatar.png' },
    });

    requireOptions().ready?.({} as Cropper.ReadyEvent<HTMLImageElement>);

    expect(context.drawImage).toHaveBeenCalledWith(
      cropperMocks.canvas,
      0,
      0,
      40,
      40,
    );
    expect(context.globalCompositeOperation).toBe('destination-in');
    expect(context.arc).toHaveBeenCalledWith(20, 20, 20, 0, 2 * Math.PI, true);
    expect(wrapper.emitted('cropend')).toHaveLength(1);
    getContext.mockRestore();
    toBlob.mockRestore();
  });
});
