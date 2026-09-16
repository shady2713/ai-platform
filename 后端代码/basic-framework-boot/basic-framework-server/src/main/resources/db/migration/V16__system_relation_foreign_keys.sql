-- 权限与岗位关联表属于 system 模块内强一致关系；先清理不可见父记录对应的关联，再增加物理外键。

DELETE rm
FROM `system_role_menu` rm
LEFT JOIN `system_role` r ON r.`id` = rm.`role_id` AND r.`deleted` = b'0'
LEFT JOIN `system_menu` m ON m.`id` = rm.`menu_id` AND m.`deleted` = b'0'
WHERE r.`id` IS NULL OR m.`id` IS NULL;

DELETE ur
FROM `system_user_role` ur
LEFT JOIN `system_users` u ON u.`id` = ur.`user_id` AND u.`deleted` = b'0'
LEFT JOIN `system_role` r ON r.`id` = ur.`role_id` AND r.`deleted` = b'0'
WHERE u.`id` IS NULL OR r.`id` IS NULL;

DELETE up
FROM `system_user_post` up
LEFT JOIN `system_users` u ON u.`id` = up.`user_id` AND u.`deleted` = b'0'
LEFT JOIN `system_post` p ON p.`id` = up.`post_id` AND p.`deleted` = b'0'
WHERE u.`id` IS NULL OR p.`id` IS NULL;

ALTER TABLE `system_role_menu`
    ADD CONSTRAINT `fk_role_menu_role` FOREIGN KEY (`role_id`) REFERENCES `system_role` (`id`) ON DELETE CASCADE,
    ADD CONSTRAINT `fk_role_menu_menu` FOREIGN KEY (`menu_id`) REFERENCES `system_menu` (`id`) ON DELETE CASCADE;

ALTER TABLE `system_user_role`
    ADD CONSTRAINT `fk_user_role_user` FOREIGN KEY (`user_id`) REFERENCES `system_users` (`id`) ON DELETE CASCADE,
    ADD CONSTRAINT `fk_user_role_role` FOREIGN KEY (`role_id`) REFERENCES `system_role` (`id`) ON DELETE CASCADE;

ALTER TABLE `system_user_post`
    ADD CONSTRAINT `fk_user_post_user` FOREIGN KEY (`user_id`) REFERENCES `system_users` (`id`) ON DELETE CASCADE,
    ADD CONSTRAINT `fk_user_post_post` FOREIGN KEY (`post_id`) REFERENCES `system_post` (`id`) ON DELETE CASCADE;
