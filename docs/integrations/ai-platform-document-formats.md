# 知识文档格式矩阵与受限解析（K04）

本文件是 [K04 实现受限文档解析器](../ai-platform/tasks/K04.md) 的交付物之一：记录**支持哪些格式、
怎么解析、位置怎么表达、上限是多少、失败怎么表达**，以及相对卡片原文的依赖偏离。

## 1. 格式矩阵

| 扩展名 | 解析实现 | 位置标识 | 扫描件/特殊情形 | 上限 |
|---|---|---|---|---|
| `.txt` | 直接解码（UTF-8 → GBK 回退，去 BOM） | `段落 N`（空行分段） | 两种编码都解不出 → `parse-corrupt` | 字符/段落 |
| `.md` / `.markdown` | 同上（Markdown 不渲染，原文保留） | `段落 N` | 同上 | 字符/段落 |
| `.pdf` | PDFBox（按页抽取文本层） | `第 N 页` | 无文本层 → **需要 OCR**（不生成占位正文）；加密 → `parse-encrypted` | 字符/段落/页数 |
| `.docx` | POI（段落 + 表格） | `段落 N` / `表格 N` | 加密/损坏 → `parse-corrupt` | 字符/段落 |

上限默认值（`DocumentParserLimits.DEFAULTS`）：20 万字符、500 段、200 页、10 秒。
触顶一律**失败**（稳定原因码 `parse-too_large` / `parse-timeout`），不返回被截断的正文当完整结果。

## 2. 四条硬约束

1. **白名单格式 + 魔数交叉校验**：扩展名决定解析路径；用 tika-core 的魔数探测交叉校验
   （`.pdf` 必须是 PDF、`.docx` 必须是 OOXML 容器、文本类不得是压缩包/可执行文件）。
   改名绕过（`payload.zip` → `payload.pdf`）在进入解析器之前就被拒绝。
2. **不抓取远程资源**：解析只读入参字节流；PDFBox/POI 不会为外部引用发起请求；
   不解析内嵌文档（不递归打开 OOXML 内嵌对象）、不做 OCR、不跟随注释/附件。
3. **有界**：字符总量、段落数、页数在解析过程中检查（PDF 逐页、DOCX 逐段），耗时用截止时间检查；
   触顶立刻中止。单个超长页面无法被页间检查打断的情形由入库任务的租约上限兜底（见 K03）。
4. **错误不泄内容**：失败只给稳定原因码（`parse-<reason>`）与异常**类型名**，
   不带文件正文、不带上游报文——解析器报错常带原文片段，这是最容易顺手泄漏的地方。

## 3. 依赖偏离（相对卡片原文的"封装 Tika"）

卡片原文建议"封装 Tika/格式解析"。实测结论：本环境的本地仓库里
`tika-parser-*-module`（含 `tika-parsers-standard-package`）**只有 pom、没有 jar**，
`tika-core` 有 jar 但只提供类型探测与 SAX 基础设施，不能解析正文。因此：

| 用途 | 采用的实现 | 依据 |
|---|---|---|
| 类型（魔数）探测 | `org.apache.tika:tika-core` 3.2.3（依赖台账已管理） | 只需核心能力，jar 可用 |
| PDF 正文 | `org.apache.pdfbox:pdfbox` 3.0.5（本卡登记进 `basic-framework-dependencies`） | 解析器模块不可用；PDFBox 是 Tika 的 PDF 后端，行为等价且可离线构建 |
| DOCX 正文 | `org.apache.poi:poi-ooxml` 5.4.1（本卡登记） | 同上（Tika 的 Office 后端） |

偏离影响：无功能缺口（三种格式都真实解析并有真实文件夹具用例）；
后续若要回到 Tika 解析器模块，只需替换 `RestrictedDocumentParser` 的内部实现，对外契约不变。

## 4. 真实文件夹具与依赖扫描

- **夹具**：`RestrictedDocumentParserTest` 在测试内用 PDFBox/POI **生成真实文件**
  （两页文本 PDF、只有图形的"扫描件"PDF、加密 PDF、含段落与表格的 DOCX、UTF-8/GBK 文本），
  断言位置、内容、上限与失败原因；不在仓库提交二进制样本（生成库与解析库同源）。
- **依赖扫描**：新增依赖随 `sh .harness/verify.sh dependencies`（SBOM + Trivy，HIGH/CRITICAL 门禁）一起扫描，
  结果记在 K04 证据文档；本文件只登记依赖选择与偏离依据。

## 5. 与上下游的接口

- 上游：K03 的入库任务在"解析"步骤调用本解析器（`AiKnowledgeIngestionStep` 端口），
  解析结论写入文档版本的解析提示（`parse_note`：例如"扫描件需要 OCR"）与失败原因码。
- 下游：K05 用 `ParsedSegment.locationRef` 生成切片的来源位置（引用可核验，AT-026）。
- 解析器**不接触**权限判定与文件读取：字节流由调用方按 A07 的业务 ACL 读取后传入。
