import type { Ref } from 'vue';

import type { ErrorMode } from '@vben/request';

import type { AxiosProgressEvent, InfraFileApi } from '#/api/infra/file';

import { computed, unref } from 'vue';

import { useAppConfig } from '@vben/hooks';
import { $t } from '@vben/locales';

import { createFile, getFilePresignedUrl, uploadFile } from '#/api/infra/file';
import { baseRequestClient } from '#/api/request';

/**
 * 上传类型
 */
enum UPLOAD_TYPE {
  // 客户端直接上传（只支持S3服务）
  CLIENT = 'client',
  // 客户端发送到后端上传
  SERVER = 'server',
}

interface UploadTypeOptions {
  acceptRef: Ref<string[]>;
  helpTextRef: Ref<string>;
  maxNumberRef: Ref<number>;
  maxSizeRef: Ref<number>;
}

/**
 * 上传类型钩子函数
 * @param options 上传限制的响应式配置
 * @returns 文件类型限制和帮助文本的计算属性
 */
export function useUploadType(options: UploadTypeOptions) {
  const { acceptRef, helpTextRef, maxNumberRef, maxSizeRef } = options;
  // 文件类型限制
  const getAccept = computed(() => {
    const accept = unref(acceptRef);
    if (accept && accept.length > 0) {
      return accept;
    }
    return [];
  });
  const getStringAccept = computed(() => {
    return unref(getAccept)
      .map((item) => {
        return item.indexOf('/') > 0 || item.startsWith('.')
          ? item
          : `.${item}`;
      })
      .join(',');
  });

  // 支持jpg、jpeg、png格式，不超过2M，最多可选择10张图片，。
  const getHelpText = computed(() => {
    const helpText = unref(helpTextRef);
    if (helpText) {
      return helpText;
    }
    const helpTexts: string[] = [];

    const accept = unref(acceptRef);
    if (accept.length > 0) {
      helpTexts.push($t('ui.upload.accept', [accept.join(',')]));
    }

    const maxSize = unref(maxSizeRef);
    if (maxSize) {
      helpTexts.push($t('ui.upload.maxSize', [maxSize]));
    }

    const maxNumber = unref(maxNumberRef);
    if (maxNumber && maxNumber !== Infinity) {
      helpTexts.push($t('ui.upload.maxNumber', [maxNumber]));
    }
    return helpTexts.join('，');
  });
  return { getAccept, getStringAccept, getHelpText };
}

/**
 * 上传钩子函数
 * @param directory 上传目录
 * @param errorMode 上传失败的反馈消费方式
 * @param publicRead 是否允许匿名读取；默认私有
 * @returns 上传 URL 和自定义上传方法
 */
export function useUpload(
  directory?: string,
  errorMode: ErrorMode = 'global',
  publicRead = false,
) {
  // 后端上传地址
  const uploadUrl = getUploadUrl();
  // 是否使用前端直连上传
  const isClientUpload =
    UPLOAD_TYPE.CLIENT === import.meta.env.VITE_UPLOAD_TYPE;
  // 重写ElUpload上传方法
  async function httpRequest(
    file: File,
    onUploadProgress?: AxiosProgressEvent,
  ) {
    // 模式一：前端上传
    if (isClientUpload) {
      // 1.1 生成文件名称
      const fileName = await generateFileName(file);
      // 1.2 获取文件预签名地址
      const presignedInfo = await getFilePresignedUrl(
        fileName,
        file.size,
        file.type || undefined,
        directory,
        publicRead,
      );
      // 1.3 上传文件
      await baseRequestClient.put(presignedInfo.uploadUrl, file, {
        headers: {
          'Content-Type': file.type,
        },
      });
      // 1.4 对象与元数据均写入成功后，才向上传组件报告成功。
      const url = await createFile0(presignedInfo);
      return { url };
    } else {
      // 模式二：后端上传
      return uploadFile(
        { file, directory, publicRead },
        onUploadProgress,
        errorMode,
      );
    }
  }

  return {
    uploadUrl,
    httpRequest,
  };
}

/**
 * 获得上传 URL
 */
export function getUploadUrl(): string {
  const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);
  return `${apiURL}/infra/file/upload`;
}

/**
 * 创建文件信息
 *
 * @param vo 文件预签名信息
 */
async function createFile0(
  vo: InfraFileApi.FilePresignedUrlRespVO,
): Promise<string> {
  return createFile({ uploadToken: vo.uploadToken });
}

/** 保留原始文件名；存储路径由服务端预签名接口隔离生成。 */
async function generateFileName(file: File) {
  return file.name;
}
