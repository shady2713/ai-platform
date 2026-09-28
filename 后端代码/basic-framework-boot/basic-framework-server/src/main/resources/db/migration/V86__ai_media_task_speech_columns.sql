-- X04：媒体任务补两列语音受理参数，并把 output_format 对"无输出格式的操作"放开为空。
-- 说明：
--   1) voice：TTS 音色标识（受理时固定，执行期交给端口；为空表示端点默认音色）。
--      音色白名单由端点声明（X01 准入矩阵）与平台形状校验共同收窄，列宽 64 与语音契约一致；
--   2) language_hint：STT 语言提示（冻结语言的短标识，如 zh-CN；为空表示由端点自行识别）。
--      持久化的是**受理时的调用参数**，不是识别结果；
--   3) output_format 改为可空且无默认值：转写（STT）没有输出格式，原列默认 'png' 会让转写任务行
--      记出一个并不存在的输出格式（数据不实），并使同键重复提交在幂等比较里失败。
--      图片生成/编辑与 TTS 都在受理时显式写入该列，语义不变；
--   4) 三列都不参与唯一键：旧任务行（图片生成/编辑）与未提供该参数的请求保持原语义；
--   5) 转写全文不是本表的一列：识别结果按 X03 的产物语义落平台私有文件（ai_media_asset + infra_file），
--      任务行只存"受理参数与状态"这组事实。

ALTER TABLE `ai_media_task`
  MODIFY COLUMN `output_format` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求输出格式（图片生成/编辑与 TTS；转写为空）';

ALTER TABLE `ai_media_task`
  ADD COLUMN `voice` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'TTS 音色标识（受理时固定；为空表示端点默认音色）' AFTER `output_format`,
  ADD COLUMN `language_hint` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'STT 语言提示（冻结语言的短标识；为空表示由端点自行识别）' AFTER `voice`;
