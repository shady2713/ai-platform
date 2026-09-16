CREATE USER IF NOT EXISTS 'framework_app'@'%' IDENTIFIED BY 'integration-only-db';
CREATE USER IF NOT EXISTS 'framework_migrator'@'%' IDENTIFIED BY 'integration-only-flyway';

GRANT SELECT, INSERT, UPDATE, DELETE ON basic_framework.* TO 'framework_app'@'%';
GRANT ALL PRIVILEGES ON basic_framework.* TO 'framework_migrator'@'%';
GRANT SELECT ON performance_schema.user_variables_by_thread TO 'framework_migrator'@'%';
