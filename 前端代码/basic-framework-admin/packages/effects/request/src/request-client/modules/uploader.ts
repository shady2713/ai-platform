import type { RequestClient } from '../request-client';
import type { RequestClientConfig } from '../types';

import { isUndefined } from '@vben/utils';

function appendValue(formData: FormData, key: string, value: unknown) {
  if (isUndefined(value)) {
    return;
  }
  if (
    value === null ||
    value instanceof Blob ||
    ['boolean', 'number', 'string'].includes(typeof value)
  ) {
    formData.append(key, value instanceof Blob ? value : String(value));
    return;
  }
  throw new TypeError(`Unsupported upload field value for "${key}"`);
}

class FileUploader {
  private client: Pick<RequestClient, 'post'>;

  constructor(client: Pick<RequestClient, 'post'>) {
    this.client = client;
  }

  public async upload<T = unknown, D extends object = { file: Blob | File }>(
    url: string,
    data: D & { file: Blob | File },
    config?: RequestClientConfig<FormData>,
  ): Promise<T> {
    const formData = new FormData();

    Object.entries(data).forEach(([key, value]) => {
      if (Array.isArray(value)) {
        value.forEach((item, index) => {
          appendValue(formData, `${key}[${index}]`, item);
        });
      } else {
        appendValue(formData, key, value);
      }
    });

    const finalConfig: RequestClientConfig<FormData> = {
      ...config,
      headers: {
        'Content-Type': 'multipart/form-data',
        ...config?.headers,
      },
    };

    return this.client.post<T, FormData>(url, formData, finalConfig);
  }
}

export { FileUploader };
