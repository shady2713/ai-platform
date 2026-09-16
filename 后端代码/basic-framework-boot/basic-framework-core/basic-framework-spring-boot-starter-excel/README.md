# basic-framework-spring-boot-starter-excel

Excel 导入导出、字典转换与表格校验能力接缝，底层使用 FastExcel。

## 提供的能力

- `ExcelUtils` 统一 HTTP 导出和文件读写入口。
- `DictFormat`、`DictConvert` 与字典校验器统一字典值/标签转换。
- `ExcelColumnSelect`、`SelectSheetWriteHandler` 提供受控下拉选项。
- `MoneyConvert`、`JsonConvert` 提供稳定的领域值转换。

## 使用约束

- 业务模块通过本 starter 的注解、转换器和工具入口操作表格，不直接散落 FastExcel 配置。
- 字典数据只通过 `DictDataCommonApi` 契约读取；不得依赖 system 模块内部实现。
- 字典缓存刷新周期由 `basic-framework.dict.cache-refresh-after-write` 管理，默认 1 分钟且必须大于 0。
- 导出字段、文件名和响应头必须使用可信元数据，用户输入进入表格前必须完成类型和长度校验。
- 字典缓存只用于减少同一次导出链路的重复读取；字典变更后必须调用统一清理入口。

## 验证

后端测试覆盖真实文件读写、下载响应、转换、字典校验、列宽与下拉选项边界；模块 JaCoCo 行覆盖率门槛为 93.0%。新增转换器必须同时增加正常值、空值和非法值测试。
