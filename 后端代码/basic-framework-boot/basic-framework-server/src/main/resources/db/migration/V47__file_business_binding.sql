-- 受控文件增加业务绑定：带业务绑定的文件读取授权由业务模块的 FileBusinessAccessProvider 判定，
-- 管理权限不得冒充业务授权；未注册业务类型的绑定文件一律拒绝读取（fail-closed）。

ALTER TABLE `infra_file`
    ADD COLUMN `business_type` varchar(64) DEFAULT NULL COMMENT '业务类型：非空表示受业务授权 SPI 管控' AFTER `owner_user_type`,
    ADD COLUMN `business_id` bigint DEFAULT NULL COMMENT '业务对象编号' AFTER `business_type`,
    ADD CONSTRAINT `ck_file_business_binding` CHECK ((`business_type` IS NULL) = (`business_id` IS NULL));

-- 业务对象反查其文件（引用删除与孤儿巡检）走该索引
CREATE INDEX `idx_business_binding` ON `infra_file` (`business_type`, `business_id`);
