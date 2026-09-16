# @vben/hooks

提供应用共用的组合式函数，并重导出 `@vben-core/composables`。共享业务 hooks 放在本包，单个应用专用的 hooks 留在应用内。

## 用法

### 添加依赖

```bash
# 进入目标应用目录，例如 apps/xxxx-app
# cd apps/xxxx-app
pnpm add @vben/hooks
```

### 使用

```ts
import { useNamespace } from '@vben/hooks';
```
