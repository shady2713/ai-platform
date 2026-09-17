# infra 模块

`basic-framework-module-infra` 提供配置、文件存储、任务调度和运行日志等管理后台基础能力。

## 参数配置契约

- `visible=false` 的配置值视为敏感数据；详情、分页、导出和按 key 查询都不得把原始值返回浏览器。
- 响应脱敏集中在 `ConfigConvert`，新增响应形态必须复用该转换边界。

## 文件上传契约

- 后端直传和预签名上传共用文件名、路径、大小、MIME 与压缩包安全校验。
- 文件客户端、任务注册与文件类型识别失败只记录脱敏后的有界异常堆栈，不记录凭据、文件内容或异常正文。
- 新文件默认私有读取，并记录上传主体；只有请求显式携带 `publicRead=true` 才能匿名读取。私有文件只允许所有者或拥有 `infra:file:query` 的管理员经下载接口读取。
- 预签名上传仅支持私有 S3 存储，客户端只提交一次性凭据，不能决定存储配置、路径或访问地址。
- 私有文件不得写入公开对象存储配置；历史文件在 V37 迁移中显式标记为公开，避免升级改变既有访问语义。
- 完成接口校验存储端实际对象后才返回访问地址；已登记的超时或失败对象由 `fileDeletionRetryJob` 持久化重试清理。
- 预签名私有桶还必须配置 `.pending/` 生命周期清理，覆盖签名复用重建的暂存对象；业务上传拒绝使用该保留目录，部署步骤见 [Runbook](../../../docs/deployment.md#s3-预签名上传的暂存清理)。
- 最终对象由服务端上传已经校验的同一份字节，再删除临时对象；复用尚未过期的临时 PUT 签名无法替换最终内容。实现决策见 [ADR 0037](../../../docs/adr/0037-publish-validated-file-bytes.md)。
- 签发在事务内持有配置共享锁并写入待上传记录；完成请求先提交认领状态，再锁配置与文件行执行发布。发布失败后独立登记删除补偿，过期清理与发布通过文件行锁串行。
- 可调参数由 `basic-framework.file.presigned-upload` 所有，部署环境可使用 `FILE_PRESIGNED_UPLOAD_TTL` 和 `FILE_PRESIGNED_UPLOAD_MAX_SIZE`。
- 文件客户端由工厂统一持有；配置刷新先创建可用替代实例，存储类型切换、配置删除和应用关闭都会释放旧客户端资源。
- S3 刷新使用读写锁隔离在途请求，网络客户端和预签名器必须成对关闭，响应流必须在读取后关闭。
- S3 区域以显式配置为先；全球端点或无法推断区域的兼容端点使用默认区域，全球端点不参与区域字符串截取。

完整决策见 [ADR 0012](../../../docs/adr/0012-file-upload-metadata-boundary.md)、[ADR 0015](../../../docs/adr/0015-resilient-file-deletion.md) 和 [ADR 0018](../../../docs/adr/0018-file-access-visibility.md)。

## 文件薄契约与业务授权 SPI

跨模块只能通过 `FileCommonApi`（`com.basicframework.module.infra.api.file`）创建、读取与删除受控私有文件，
不得直接使用 `FileService`、Mapper 或 `FileDO`；契约类已登记到 `ModuleBoundaryArchitectureTest` 的显式允许清单。

- **业务绑定**：`createFile` 必须携带 `businessType` + `businessId`（`infra_file.business_type/business_id`，V47 迁移）；
  业务类型未注册授权实现时拒绝创建，避免产生永远不可读的文件。
- **授权归业务模块**：读取与删除由该业务类型的 `FileBusinessAccessProvider` 判定；
  **管理权限（`canManageFiles`）与所有者身份都不构成业务绑定文件的豁免**，`FileCommonApi` 传入的主体
  一律按 `canManageFiles=false` 构造。
- **fail-closed**：业务类型没有对应 Provider（或 Provider 未实现 `canDelete`）时读取/删除一律拒绝，
  并与"文件不存在"保持同一语义，避免借编号探测文件是否受管控。
- **删除语义**：管理端 `deleteFile/deleteFileList` 拒绝业务绑定文件（`FILE_BUSINESS_DELETE_REQUIRES_AUTHORIZATION`）；
  业务文件删除必须经 `FileCommonApi.deleteFile` 且 Provider 明确允许，随后仍走既有受控删除与重试流程。
- **Provider 唯一性**：每个业务类型只允许一个实现，空白类型或重复注册在启动期失败；
  零 Provider 是合法状态（业务实现落地前），此时任何业务绑定文件都不可读。

拒绝测试见 `FileBusinessAuthorizationTest`：未注册业务类型、管理权限冒充、未授权主体、管理端删除业务文件等用例
均要求被拒绝；无业务绑定的历史文件保持既有公开/所有者/管理员规则（回归由 `FileServiceImplTest` 覆盖）。
