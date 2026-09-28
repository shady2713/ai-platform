-- X06：受控业务写工具与结果核对。
-- 设计要点（FR-25、AT-019/020/021）：
--   1) 写工具的动作必须**登记业务幂等键**（值取自冻结的参数）与**登记的核对查询**，两者在动作创建时
--      冻结进本表（`idempotency_key`/`idempotency_param`/`verify_source_ref`），执行前再与当前已发布
--      版本比对——绑定变了就必须重新确认（与"确认后改参数"同一等级的拒绝）；
--   2) `uk_ai_tool_action_business` 保证"同一工具 + 同一业务幂等键"最多只有一条**可能已产生副作用**的动作：
--      PENDING/CONFIRMED/EXECUTING/UNKNOWN/EXECUTED 占住这个键，重复提交只能复用同一条动作
--      （同一意图绝不产生第二次副作用）；已确认无副作用的终态（CANCELLED/EXPIRED/FAILED）不占键，
--      允许业务在明确失败后重新发起——"过期确认"因此既不执行旧动作，也不永久堵死该业务键；
--      （已过期但状态仍是 PENDING/CONFIRMED 的动作，由服务层在重新发起时惰性落成 EXPIRED 再释放键：
--       函数索引表达式必须是确定性表达式，不能引用 NOW()）
--   3) 执行结果未定（超时、连接中断、上游 5xx 或响应不可用）落 `UNKNOWN`，并保留"确认已被消费"的
--      事实（`attempt_epoch` + 中间态 `EXECUTING`）：只能经显式核对收敛，禁止自动重放写请求；
--   4) 核对证据只登记稳定结论（PROGRAM/MANUAL + APPLIED/NOT_APPLIED + 登记操作键/条目数/人工说明），
--      列宽 200 且不含上游正文、参数正文与凭据；动作行本身从不回显参数正文（`arguments_json` 只入不出）。

ALTER TABLE `ai_tool_action`
    ADD COLUMN `tool_type` varchar(16) NOT NULL DEFAULT 'READ' COMMENT '工具类型快照（READ/WRITE；写动作只经确认入口执行）' AFTER `policy`,
    ADD COLUMN `idempotency_param` varchar(64) DEFAULT NULL COMMENT '业务幂等键参数名（写动作必填，来自版本声明）' AFTER `arguments_json`,
    ADD COLUMN `idempotency_key` varchar(128) DEFAULT NULL COMMENT '业务幂等键值（写动作必填，取自冻结参数；不参与回显）' AFTER `idempotency_param`,
    ADD COLUMN `verify_source_ref` varchar(128) DEFAULT NULL COMMENT '登记的核对查询 operationKey（写动作必填，同连接器已发布操作）' AFTER `idempotency_key`,
    ADD COLUMN `verify_param` varchar(64) DEFAULT NULL COMMENT '核对查询接收业务键的参数名（写动作必填，来自同一份声明）' AFTER `verify_source_ref`,
    ADD COLUMN `attempt_epoch` int NOT NULL DEFAULT 0 COMMENT '执行尝试代数（CAS 消费确认一次；崩溃后由核对解决）' AFTER `executed_at`,
    ADD COLUMN `verified_at` datetime DEFAULT NULL COMMENT '核对时间' AFTER `result_code`,
    ADD COLUMN `verified_by` varchar(16) DEFAULT NULL COMMENT '核对方式（PROGRAM 程序核对/MANUAL 人工核对）' AFTER `verified_at`,
    ADD COLUMN `verify_result` varchar(16) DEFAULT NULL COMMENT '核对结论（APPLIED 已生效/NOT_APPLIED 未生效）' AFTER `verified_by`,
    ADD COLUMN `verify_evidence` varchar(200) DEFAULT NULL COMMENT '核对证据（登记操作键与条目数，或人工说明；不含上游正文与凭据）' AFTER `verify_result`,
    ADD UNIQUE KEY `uk_ai_tool_action_business`
        ((if((`deleted` = b'1')
             or (`status` in ('CANCELLED', 'EXPIRED', 'FAILED'))
             or (`idempotency_key` is null), NULL, concat(`tool_id`, ':', `idempotency_key`))));
