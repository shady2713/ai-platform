-- 仅封存仍持有交付物初始摘要的种子账号；已自行改密的账号不受影响。
UPDATE system_users SET password = '!bootstrap-required', status = 1, must_change_password = b'1'
WHERE id = 1 AND password = '$2a$10$kOh2wgKXDoFbyejaJt2GZea5v6K/4IapMkho832HQSXS5bmuBsUNm';
