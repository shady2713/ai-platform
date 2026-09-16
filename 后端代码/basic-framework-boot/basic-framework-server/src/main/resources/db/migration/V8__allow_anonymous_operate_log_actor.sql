-- 系统任务、短信找回密码等流程没有登录态，目标实体由 biz_id 记录，操作者 user_id 允许为空。
ALTER TABLE system_operate_log
    MODIFY COLUMN user_id bigint NULL COMMENT '用户编号；系统任务或匿名强校验流程为空';
