-- 回执只允许按渠道和供应商流水号唯一定位，避免使用客户端可猜测的日志编号更新状态。

ALTER TABLE `system_sms_log`
    ADD UNIQUE KEY `uk_channel_api_serial_no` (`channel_code`, `api_serial_no`);
