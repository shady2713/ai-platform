-- O04：运行的数据分级。
-- 设计要点：
--   1) 受理时声明的数据分级必须随运行持久化：执行阶段的**外发策略**按它判定，
--      不能在执行时重新猜测（否则等于把"能发到哪个端点"交给执行期的默认值）；
--   2) 历史行的默认值取 L2_INTERNAL（平台默认等级），新受理的运行一律写入受理请求声明的等级。

ALTER TABLE `ai_run`
    ADD COLUMN `data_level` varchar(16) NOT NULL DEFAULT 'L2_INTERNAL' COMMENT '受理时声明的数据分级（L1_PUBLIC/L2_INTERNAL/L3_PERSONAL/L4_SECRET）' AFTER `input_digest`;
