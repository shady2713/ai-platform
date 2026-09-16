import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SystemSmsTemplateApi {
  /** 短信模板 */
  export interface Template {
    id: number;
    type: number;
    status: number;
    code: string;
    name: string;
    content: string;
    params: string[];
    remark: string;
    apiTemplateId: string;
    channelId: number;
    channelCode: string;
    createTime?: string;
  }
}

/** 查询短信模板列表 */
export function getSmsTemplatePage(params: PageParam) {
  return requestClient.get<PageResult<SystemSmsTemplateApi.Template>>(
    '/system/sms-template/page',
    { params },
  );
}

/** 查询短信模板详情 */
export function getSmsTemplate(id: number) {
  return requestClient.get<SystemSmsTemplateApi.Template>(
    `/system/sms-template/get?id=${id}`,
  );
}

/** 新增短信模板 */
export function createSmsTemplate(data: SystemSmsTemplateApi.Template) {
  return requestClient.post('/system/sms-template/create', data);
}

/** 修改短信模板 */
export function updateSmsTemplate(data: SystemSmsTemplateApi.Template) {
  return requestClient.put('/system/sms-template/update', data);
}

/** 删除短信模板 */
export function deleteSmsTemplate(id: number) {
  return requestClient.delete(`/system/sms-template/delete?id=${id}`);
}
