# 嵌入页部署（C05）

嵌入页由三部分组成：**构建产物**（自托管静态资产）、**启动壳**（服务端输出固定 HTML）、
**公开启动配置**（服务端按应用与已发布主题给出）。三者都不含凭据，AI 调用一律另需票据。

## 1. 构建与暂存

```sh
cd 前端代码/basic-framework-admin
pnpm -F @vben/ai-chat run build:embed            # = build + stage:embed
# 默认暂存到 apps/ai-chat/embed-assets；也可指定目录：
pnpm -F @vben/ai-chat exec node scripts/stage-embed-assets.mjs /opt/ai-platform/embed-assets
```

暂存脚本把 `dist/assets/*` 摊平成 `<目标目录>/assets/<文件名>`，并写出 `asset-manifest.json`：

```json
{
  "version": 1,
  "entryJs": "index-C97Z0qqd.js",
  "entryCss": "index-CJH5oiAp.css",
  "files": { "index-C97Z0qqd.js": "<sha256>", "vendor-vue-xxxx.js": "<sha256>" }
}
```

规则：

- 只暂存**内容哈希命名**的资产；`*.map`、隐藏文件与目录不进产物；
- **清单即白名单**：服务端只提供清单里列出的文件名，清单外路径一律 404（目录里的其他文件不会被暴露）；
- 单文件上限由 `basic-framework.ai.embed.max-asset-bytes`（默认 5 MiB）控制，超限按"产物未就位"处理。

暂存目录属于**构建输出**，不要提交进版本库。

## 2. 服务端配置

```yaml
basic-framework:
  ai:
    embed:
      assets-directory: /opt/ai-platform/embed-assets   # 上一步的暂存目录（相对路径按进程工作目录解析）
      max-asset-bytes: 5242880
```

未配置或目录里没有 `asset-manifest.json` 时，嵌入入口返回 **422 `AI_EMBED_ASSETS_NOT_STAGED`**
（明确失败，不输出加载不出脚本的空壳）。

## 3. 入口与响应头

| 路径 | 说明 |
|---|---|
| `GET /app-api/ai/v1/embed/{appCode}` | 启动壳 HTML（公开） |
| `GET /app-api/ai/v1/embed/{appCode}/bootstrap` | 公开启动配置：应用标识、协议版本、握手允许域、品牌名、已发布主题 token（公开） |
| `GET /app-api/ai/v1/embed/{appCode}/assets/{file}` | 自托管静态资产（只提供构建清单内的文件） |

响应头策略：

| 头 | 取值 | 说明 |
|---|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self'; …; frame-ancestors <应用配置的精确 Origin>` | **只允许配置域嵌套**；无 `unsafe-inline`、无 `unsafe-eval`；`frame-ancestors` 只认响应头，meta 标签无效 |
| `X-Frame-Options` | **仅嵌入路径不再输出** | 该路径由 CSP `frame-ancestors` 精确控制；其余路径（含 `/admin-api/**`）保持 `SAMEORIGIN` |
| `Cache-Control`（壳与启动配置） | `no-cache, must-revalidate` | 键 = 应用 + 应用配置版本 + 主题指纹；撤销允许域、停用应用或发布新主题后立即失效 |
| `ETag` | `"embed-<appCode>-<配置版本>-<主题指纹>"` | 支持 `If-None-Match` → 304（壳本身与主题无关，主题在启动配置里） |
| `Cache-Control`（资产） | `public, max-age=31536000, immutable` | 文件名带内容哈希，可长缓存 |
| `X-Content-Type-Options` | `nosniff` | 资产媒体类型由后缀显式映射，未列出的一律 `application/octet-stream` |

## 4. 反向代理注意事项

- 不要把 `/app-api/ai/v1/embed/**` 的 `X-Frame-Options` 统一补回（否则跨源嵌入会被浏览器拒绝）；
  需要额外的祖先限制时改应用配置的允许域，不要去改头。
- 壳与启动配置**不要**在代理层做长缓存：它们的缓存语义由 ETag + `no-cache` 表达。
- 资产路径可以长缓存，但要保证**同一文件名对应同一内容**（哈希命名已保证）。
- 不要为嵌入路径启用公共 CDN 回源与 `latest` 之类浮动路径（设计契约 7.3：静态脚本使用固定版本路径与内容哈希）。

## 5. 撤销与失效

| 变更 | 效果 |
|---|---|
| 修改应用的允许域 | 应用配置版本 +1 → 壳与启动配置的 ETag 变化，浏览器重新取；新允许域立即生效 |
| 停用应用 | 嵌入入口、启动配置与资产路径全部 404（与不存在同语义） |
| 清空/写坏允许域 | 422 `AI_EMBED_ORIGIN_INVALID`（fail-closed，不返回可被任意站点嵌套的壳） |
| 发布新主题修订（C04） | 主题指纹变化 → 缓存键变化，宿主下次取到新外观 |
| 产物未暂存 / 清单损坏 | 422 `AI_EMBED_ASSETS_NOT_STAGED` |

## 6. 尚未验证（由后续卡承接）

- **跨源浏览器验收**（AT-050/056/067：Cookie 禁用下仍可换票、真实 `frame-ancestors` 行为、禁公共 CDN 的网络断言）
  需要 Q06 建立的真实浏览器门禁与 G5 验收，本卡只交付响应头策略与 HTTP 层证据。
- **握手与实例隔离**（HELLO/READY/AUTH/INIT、instanceId 与 session generation）属 C06/C07；
  本卡的启动壳只负责"把公开配置给到页面"。
