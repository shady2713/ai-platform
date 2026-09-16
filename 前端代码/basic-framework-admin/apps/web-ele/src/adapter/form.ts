import type {
  FormRuleHandler,
  VbenFormSchema as FormSchema,
  VbenFormProps,
} from '@vben/common-ui';

import type { ComponentType } from './component';

import { setupVbenForm, useVbenForm as useForm, z } from '@vben/common-ui';
import { $t } from '@vben/locales';

import {
  buildLoginPasswordSchema,
  buildOptionalEmailSchema,
  buildOptionalMobileSchema,
  buildOptionalPercentSchema,
  buildOptionalRemarkSchema,
  buildRequiredEmailSchema,
  buildRequiredMobileSchema,
  buildRequiredNicknameSchema,
  buildRequiredPasswordSchema,
  buildRequiredPercentSchema,
  buildRequiredQuantitySchema,
  buildRequiredUsernameSchema,
  isEmailValue,
  isMobileValue,
  isNicknameValue,
  isPasswordValue,
  isPercentValue,
  isQuantityValue,
  isUsernameValue,
} from './field-rules';

function isEmpty(value: unknown) {
  return value === undefined || value === null || String(value).length === 0;
}

const defineRules: Record<string, FormRuleHandler> = {
  required: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return $t('ui.formRules.required', [ctx.label ?? '']);
    }
    return true;
  },
  selectRequired: (value, _params, ctx) => {
    if (value === undefined || value === null) {
      return $t('ui.formRules.selectRequired', [ctx.label ?? '']);
    }
    return true;
  },
  username: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return true;
    }
    return (
      isUsernameValue(String(value)) ||
      `${ctx.label ?? ''}必须为 4-30 位字母或数字`
    );
  },
  usernameRequired: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return $t('ui.formRules.required', [ctx.label ?? '']);
    }
    return (
      isUsernameValue(String(value)) ||
      `${ctx.label ?? ''}必须为 4-30 位字母或数字`
    );
  },
  password: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return true;
    }
    return (
      isPasswordValue(String(value)) ||
      `${ctx.label ?? ''}至少 15 个字符，且 UTF-8 编码不能超过 72 字节`
    );
  },
  passwordRequired: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return $t('ui.formRules.required', [ctx.label ?? '']);
    }
    return (
      isPasswordValue(String(value)) ||
      `${ctx.label ?? ''}至少 15 个字符，且 UTF-8 编码不能超过 72 字节`
    );
  },
  mobile: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return true;
    }
    return (
      isMobileValue(String(value)) ||
      $t('ui.formRules.mobile', [ctx.label ?? ''])
    );
  },
  mobileRequired: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return $t('ui.formRules.required', [ctx.label ?? '']);
    }
    return (
      isMobileValue(String(value)) ||
      $t('ui.formRules.mobile', [ctx.label ?? ''])
    );
  },
  email: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return true;
    }
    return isEmailValue(String(value)) || `${ctx.label ?? ''}格式不正确`;
  },
  emailRequired: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return $t('ui.formRules.required', [ctx.label ?? '']);
    }
    return isEmailValue(String(value)) || `${ctx.label ?? ''}格式不正确`;
  },
  percent: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return true;
    }
    return (
      isPercentValue(String(value)) ||
      `${ctx.label ?? ''}必须在 0-100 之间，最多保留两位小数`
    );
  },
  quantity: (value, _params, ctx) => {
    if (isEmpty(value)) {
      return true;
    }
    return isQuantityValue(Number(value)) || `${ctx.label ?? ''}必须为非负整数`;
  },
};

async function initSetupVbenForm() {
  setupVbenForm<ComponentType>({
    config: {
      modelPropNameMap: {
        Upload: 'fileList',
        CheckboxGroup: 'model-value',
      },
    },
    defineRules,
  });
}

const useVbenForm = useForm<ComponentType>;

export {
  buildLoginPasswordSchema,
  buildOptionalEmailSchema,
  buildOptionalMobileSchema,
  buildOptionalPercentSchema,
  buildOptionalRemarkSchema,
  buildRequiredEmailSchema,
  buildRequiredMobileSchema,
  buildRequiredNicknameSchema,
  buildRequiredPasswordSchema,
  buildRequiredPercentSchema,
  buildRequiredQuantitySchema,
  buildRequiredUsernameSchema,
  initSetupVbenForm,
  isEmailValue,
  isMobileValue,
  isNicknameValue,
  isPasswordValue,
  isPercentValue,
  isQuantityValue,
  isUsernameValue,
  useVbenForm,
  z,
};

export type VbenFormSchema = FormSchema<ComponentType>;
export type { VbenFormProps };
