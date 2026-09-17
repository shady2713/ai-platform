import { createJiti } from "../../../node_modules/.pnpm/jiti@2.6.1/node_modules/jiti/lib/jiti.mjs";

const jiti = createJiti(import.meta.url, {
  "interopDefault": true,
  "alias": {
    "@vben/turbo-run": "/home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin/scripts/turbo-run"
  },
  "transformOptions": {
    "babel": {
      "plugins": []
    }
  }
})

/** @type {import("/home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin/scripts/turbo-run/src/index.js")} */
const _module = await jiti.import("/home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin/scripts/turbo-run/src/index.ts");

export default _module?.default ?? _module;