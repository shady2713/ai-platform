-- X01：多模态能力词汇扩展，能力集合列加宽。
-- 说明：
--   1) capability 词汇新增 6 个媒体能力（IMAGE_UNDERSTANDING/IMAGE_OCR/IMAGE_GENERATION/IMAGE_EDIT/
--      SPEECH_TO_TEXT/TEXT_TO_SPEECH），全部取值拼接后最长 145 字符，超过既有 varchar(128)；
--      本迁移只加宽列，不改列语义、不加列、不加表；
--   2) 声明仍然是逗号分隔的能力名列表（与 ModelCapability 一致），可由旧客户端按未知值降级：新增值
--      不改变既有值的含义，前端未升级时页面只展示已认识的能力；
--   3) 三个列一起加宽：端点配置版本、服务草稿、服务发布版本，避免"能声明媒体能力但服务草稿存不下"；
--   4) 历史迁移（V48/V56）不改写；快照 `数据库文件/basic_framework.sql` 同步到 V84。

ALTER TABLE `ai_model_endpoint_revision`
    MODIFY COLUMN `capabilities` varchar(255) NOT NULL COMMENT '能力集合（逗号分隔：TEXT,...,TEXT_TO_SPEECH）';

ALTER TABLE `ai_service`
    MODIFY COLUMN `required_capabilities` varchar(255) NOT NULL COMMENT '所需能力（逗号分隔：TEXT,...,TEXT_TO_SPEECH）';

ALTER TABLE `ai_service_release`
    MODIFY COLUMN `required_capabilities` varchar(255) NOT NULL COMMENT '发布时固定的能力集合';
