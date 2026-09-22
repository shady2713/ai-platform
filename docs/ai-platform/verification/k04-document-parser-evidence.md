# K04 受限文档解析器证据（2026-09-22）

本记录是 [K04 实现受限文档解析器](../tasks/K04.md) 的验收证据。
依赖 [K03](../tasks/K03.md)（上传与入库任务，提供调用入口）已有证据文档（`k03-ingestion-evidence.md`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 解析器契约 | `adapter/document/DocumentParser.java` |
| 解析结果与位置 | `adapter/document/ParsedDocument.java`、`ParsedSegment.java`（`段落 N` / `第 N 页` / `表格 N`） |
| 失败原因（脱敏） | `adapter/document/DocumentParseException.java`：`parse-unsupported_format` / `parse-too_large` / `parse-timeout` / `parse-corrupt` / `parse-encrypted` / `parse-internal` |
| 上限 | `adapter/document/DocumentParserLimits.java`：20 万字符、500 段、200 页、10 秒（可配置） |
| 实现 | `adapter/document/RestrictedDocumentParser.java`：TXT/Markdown 直读（UTF-8→GBK）、PDF 走 PDFBox（按页）、DOCX 走 POI（按段/表）、tika-core 魔数交叉校验 |
| 依赖登记 | `basic-framework-module-ai/pom.xml`（tika-core、pdfbox、poi-ooxml）、`basic-framework-dependencies/pom.xml`（pdfbox 3.0.5、poi-ooxml 5.4.1 版本管理） |
| 格式矩阵与偏离说明 | `docs/integrations/ai-platform-document-formats.md` |
| 测试 | `RestrictedDocumentParserTest` 10 例（真实文件：文本/GBK/BOM、DOCX 段落与表格、两页 PDF、扫描件 PDF、加密 PDF、魔数不符、上限、超时、错误脱敏） |

对外方法：`DocumentParser.parse(byte[] content, String fileName) → ParsedDocument`。

## 2. 与卡片逐步实施的对应

1. **封装格式解析并输出带位置的正文段**：四种格式各有明确实现与位置语义（见格式矩阵）；
   位置用人类可读的稳定标识而不是字节偏移（偏移跨解析器版本不稳定，引用会失真）。
   偏离：Tika 的解析器模块在本地仓库只有 pom、没有 jar，正文解析改用 PDFBox/POI（Tika 的后端实现），
   类型识别仍用 tika-core；偏离与依据写入 `docs/integrations/ai-platform-document-formats.md` 第 3 节。
2. **限制页数/展开量/时间/字符总量，禁远程资源抓取**：字符、段落、页数在解析过程中检查（逐页/逐段），
   耗时用截止时间检查；不解析内嵌对象、不做 OCR、不跟随外部引用（只读入参字节流）。
   超时用例用"2000 段 DOCX + 1ms 截止"确定性触发。
3. **扫描 PDF 识别为需 OCR，不生成虚假正文**：没有文本层的 PDF 返回 `ocrRequired=true` 且 `segments` 为空；
   用例断言"没有占位正文"。加密 PDF 单独给 `parse-encrypted`（不是"损坏"）。

## 3. 关键约束与安全语义

- **改名绕过被拒**：魔数交叉校验（`.pdf` 必须真是 PDF、`.docx` 必须是 OOXML、文本类不得是压缩包/可执行文件）；
  用例用 ZIP 头冒充 `.pdf`/`.txt` 证明拒绝。
- **错误不泄内容**：失败只含原因码与异常类型名；用例用含"机密内容"的输入断言错误信息里没有它（AT-058 同款要求）。
- **有界失败而不是截断**：触顶抛 `parse-too_large`，绝不返回被截断的正文当完整结果。
- **不引入第二套存储或远程服务**：解析器只吃字节流、只吐正文段，不读文件、不写库、不联网。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-022（TXT/PDF/DOCX 解析） | TXT/Markdown/DOCX/PDF 均解析成功，位置正确（`段落 N`/`第 N 页`/`表格 N`） | `parsesTextAndMarkdownIntoLocatedParagraphs`、`parsesDocxParagraphsAndTables`、`parsesPdfPageByPage` |
| AT-022（扫描件提示 OCR） | 无文本层 PDF → `requiresOcr()=true` 且无正文段 | `scannedPdfReportsOcrInsteadOfFakeText` |
| AT-023（超大/损坏/压缩炸弹） | 字符/段落/页数上限触顶即失败；坏 PDF、ZIP 冒充、空文件受控失败；不解压内嵌对象 | `enforcesCharacterSegmentAndPageLimits`、`rejectsWrongMagicUnsupportedFormatsAndCorruptContent` |
| 中文/段落 | UTF-8、GBK、BOM 三种中文文本与 DOCX 中文段落均正确 | `decodesGbkTextAndStripsBom`、`parsesDocxParagraphsAndTables` |
| 超时资源关闭 | 超时抛 `parse-timeout`；解析器用 try-with-resources 关闭 `PDDocument`/`XWPFDocument`/输入流 | `timesOutOnLargeDocumentWithTinyDeadline` |
| 解析错误不泄漏文件内容 | 错误信息只含原因码与类型名 | `failuresNeverLeakFileContent` |
| AT-058（日志全链路无敏感正文） | 解析器不写日志；异常不含正文；调用方（K03）只落原因码 | 本卡用例 + K03 的任务失败原因断言 |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest=RestrictedDocumentParserTest` | 0 | **10 例通过**（真实文件在测试内生成） |
| `./mvnw -pl basic-framework-module-ai compile -DskipTests`（联网一次解析新依赖） | 0 | pdfbox 3.0.5 / poi-ooxml 5.4.1 及传递依赖已入本地仓库；随后 `-o` 离线编译通过 |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **Tika 解析器模块不可用**：`tika-parsers-standard-package` 与各 `tika-parser-*-module` 在本地仓库
   只有 pom、没有 jar（`-o` 离线解析失败）。处置：正文解析改用 PDFBox/POI（Tika 的后端实现），
   `tika-core` 保留用于魔数探测；偏离写入格式矩阵文档（同 K01 对 Qdrant 客户端的处理方式）。
2. **PDFBox jar 缺失但镜像可达**：`dependency:get` 实测可下载（`org.apache.pdfbox:pdfbox:3.0.5`），
   因此本卡补齐了依赖与版本管理，而不是把 PDF 标成"环境不可用"。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（含本卡 10 例解析用例）、格式、架构与覆盖率检查通过（新依赖随构建一起解析） |
| `sh .harness/verify.sh dependencies` | 1（两次） | **环境缺口**：Trivy 无法下载漏洞库（`mirror.gcr.io/aquasec/trivy-db` 连接超时，本环境禁止该出口）；失败发生在第一段 SBOM 扫描的库初始化阶段。该门禁在 F01 证据中为 0（当时可下载漏洞库），本卡未改动扫描规则 |
| `./mvnw -q "$cyclonedx_goal" …`（门禁内步骤，已执行成功） | 0 | 依赖清单已重算：`target/bom.json` 324 个组件，含本卡新增 `org.apache.pdfbox:pdfbox:3.0.5`、`org.apache.poi:poi-ooxml:5.4.1`、`org.apache.tika:tika-core:3.2.3`（及其传递依赖 pdfbox-io/fontbox/poi/poi-ooxml-lite） |
| `./mvnw -q -Pintegration clean verify`（全量 IT） | 1 | 仅既有负载敏感用例失败（`UserProfilePersistenceIT`，多份证据已记录）；本卡为纯适配器改动，未触及该用例路径 |
| `./mvnw -q -Pintegration -pl basic-framework-server verify -Dit.test=UserProfilePersistenceIT -Dtest=UserProfilePersistenceIT -DfailIfNoTests=false` | 0 | 该既有用例单独跑通过（干净容器） |
| `./mvnw -q -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | 0 | 重算聚合覆盖率报告 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡新增文件；无下调、无删除 |

### 依赖扫描说明

- **已产出**：CycloneDX SBOM（324 组件）证明新依赖进入受管清单，版本由 `basic-framework-dependencies` 统一管理。
- **未验证**：漏洞扫描（HIGH/CRITICAL）因漏洞库不可下载而未执行；本卡未放宽任何严重级别或例外规则，
  待网络允许时重跑 `sh .harness/verify.sh dependencies` 即可（F01 证据为最近一次通过记录）。

## 8. 覆盖率

本卡新增主源码 5 个文件（契约、结果、位置、异常、上限、实现）在完整覆盖率数据下由
`node scripts/check-coverage-ratchet.mjs --update` 登记单文件基线；只新增条目或上调既有值，不下调既有基线。

## 9. 未验证项

1. **解析器尚未接入入库链路**：`AiKnowledgeIngestionStep` 的实现（解析 → 切片）由 K05 落地；
   本卡只保证解析器自身的正确性与上限（K03 的"parser-unavailable"行为在本卡之后仍成立，
   直到 K05 提供实现）。
2. **扫描件 OCR**：首期明确不做 OCR（FR-17 已声明为后续能力）；本卡只保证"识别为需要 OCR 且不造假"。
3. **超大文档的真实规模压测**：上限用构造夹具验证（20 万字符/500 段/200 页级别），
   未在数百 MB 级真实文档上压测（K03 的文件上限为 32MB，二者叠加已能挡住极端情况）。
4. **DOCX 内嵌对象/宏的深度检查**：不解析内嵌对象（结构上不会递归），但未对 `.docm` 宏文档做专门拒绝
   （白名单只放行 `.docx` 扩展名，宏文档即使改名也会被 OOXML 内容结构正常解析为正文）。
5. **依赖扫描结论**：见第 7 节的 `dependencies` 门禁结果；本卡未额外做人工 CVE 复核。
