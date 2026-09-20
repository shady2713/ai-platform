-- MySQL dump 10.13  Distrib 8.4.8, for Linux (x86_64)
--
-- Host: localhost    Database: basic_framework
-- ------------------------------------------------------
-- Server version	8.4.8

-- Snapshot note: aligned with the authoritative Flyway migration chain through V65.
-- Only the 33 soft-delete tables retain a deleted column; hard-delete and
-- append-retention tables use physical deletion according to docs/data-lifecycle.md.
-- Runtime schema source of truth: 后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `QRTZ_BLOB_TRIGGERS`
--

DROP TABLE IF EXISTS `QRTZ_BLOB_TRIGGERS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_BLOB_TRIGGERS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `TRIGGER_NAME` varchar(190) NOT NULL,
  `TRIGGER_GROUP` varchar(190) NOT NULL,
  `BLOB_DATA` blob,
  PRIMARY KEY (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`),
  CONSTRAINT `FK_QRTZ_BLOB_TRIGGERS_TRIGGERS` FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`) REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_BLOB_TRIGGERS`
--

LOCK TABLES `QRTZ_BLOB_TRIGGERS` WRITE;
/*!40000 ALTER TABLE `QRTZ_BLOB_TRIGGERS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_BLOB_TRIGGERS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_CALENDARS`
--

DROP TABLE IF EXISTS `QRTZ_CALENDARS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_CALENDARS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `CALENDAR_NAME` varchar(190) NOT NULL,
  `CALENDAR` blob NOT NULL,
  PRIMARY KEY (`SCHED_NAME`,`CALENDAR_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_CALENDARS`
--

LOCK TABLES `QRTZ_CALENDARS` WRITE;
/*!40000 ALTER TABLE `QRTZ_CALENDARS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_CALENDARS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_CRON_TRIGGERS`
--

DROP TABLE IF EXISTS `QRTZ_CRON_TRIGGERS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_CRON_TRIGGERS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `TRIGGER_NAME` varchar(190) NOT NULL,
  `TRIGGER_GROUP` varchar(190) NOT NULL,
  `CRON_EXPRESSION` varchar(120) NOT NULL,
  `TIME_ZONE_ID` varchar(80) DEFAULT NULL,
  PRIMARY KEY (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`),
  CONSTRAINT `FK_QRTZ_CRON_TRIGGERS_TRIGGERS` FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`) REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_CRON_TRIGGERS`
--

LOCK TABLES `QRTZ_CRON_TRIGGERS` WRITE;
/*!40000 ALTER TABLE `QRTZ_CRON_TRIGGERS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_CRON_TRIGGERS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_FIRED_TRIGGERS`
--

DROP TABLE IF EXISTS `QRTZ_FIRED_TRIGGERS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_FIRED_TRIGGERS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `ENTRY_ID` varchar(95) NOT NULL,
  `TRIGGER_NAME` varchar(190) NOT NULL,
  `TRIGGER_GROUP` varchar(190) NOT NULL,
  `INSTANCE_NAME` varchar(190) NOT NULL,
  `FIRED_TIME` bigint NOT NULL,
  `SCHED_TIME` bigint NOT NULL,
  `PRIORITY` int NOT NULL,
  `STATE` varchar(16) NOT NULL,
  `JOB_NAME` varchar(190) DEFAULT NULL,
  `JOB_GROUP` varchar(190) DEFAULT NULL,
  `IS_NONCONCURRENT` varchar(1) DEFAULT NULL,
  `REQUESTS_RECOVERY` varchar(1) DEFAULT NULL,
  PRIMARY KEY (`SCHED_NAME`,`ENTRY_ID`),
  KEY `IDX_QRTZ_FT_TRIG_INST_NAME` (`SCHED_NAME`,`INSTANCE_NAME`),
  KEY `IDX_QRTZ_FT_INST_JOB_REQ_RCVRY` (`SCHED_NAME`,`INSTANCE_NAME`,`REQUESTS_RECOVERY`),
  KEY `IDX_QRTZ_FT_J_G` (`SCHED_NAME`,`JOB_NAME`,`JOB_GROUP`),
  KEY `IDX_QRTZ_FT_JG` (`SCHED_NAME`,`JOB_GROUP`),
  KEY `IDX_QRTZ_FT_T_G` (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`),
  KEY `IDX_QRTZ_FT_TG` (`SCHED_NAME`,`TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_FIRED_TRIGGERS`
--

LOCK TABLES `QRTZ_FIRED_TRIGGERS` WRITE;
/*!40000 ALTER TABLE `QRTZ_FIRED_TRIGGERS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_FIRED_TRIGGERS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_JOB_DETAILS`
--

DROP TABLE IF EXISTS `QRTZ_JOB_DETAILS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_JOB_DETAILS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `JOB_NAME` varchar(190) NOT NULL,
  `JOB_GROUP` varchar(190) NOT NULL,
  `DESCRIPTION` varchar(250) DEFAULT NULL,
  `JOB_CLASS_NAME` varchar(250) NOT NULL,
  `IS_DURABLE` varchar(1) NOT NULL,
  `IS_NONCONCURRENT` varchar(1) NOT NULL,
  `IS_UPDATE_DATA` varchar(1) NOT NULL,
  `REQUESTS_RECOVERY` varchar(1) NOT NULL,
  `JOB_DATA` blob,
  PRIMARY KEY (`SCHED_NAME`,`JOB_NAME`,`JOB_GROUP`),
  KEY `IDX_QRTZ_J_REQ_RECOVERY` (`SCHED_NAME`,`REQUESTS_RECOVERY`),
  KEY `IDX_QRTZ_J_GRP` (`SCHED_NAME`,`JOB_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_JOB_DETAILS`
--

LOCK TABLES `QRTZ_JOB_DETAILS` WRITE;
/*!40000 ALTER TABLE `QRTZ_JOB_DETAILS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_JOB_DETAILS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_LOCKS`
--

DROP TABLE IF EXISTS `QRTZ_LOCKS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_LOCKS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `LOCK_NAME` varchar(40) NOT NULL,
  PRIMARY KEY (`SCHED_NAME`,`LOCK_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_LOCKS`
--

LOCK TABLES `QRTZ_LOCKS` WRITE;
/*!40000 ALTER TABLE `QRTZ_LOCKS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_LOCKS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_PAUSED_TRIGGER_GRPS`
--

DROP TABLE IF EXISTS `QRTZ_PAUSED_TRIGGER_GRPS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_PAUSED_TRIGGER_GRPS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `TRIGGER_GROUP` varchar(190) NOT NULL,
  PRIMARY KEY (`SCHED_NAME`,`TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_PAUSED_TRIGGER_GRPS`
--

LOCK TABLES `QRTZ_PAUSED_TRIGGER_GRPS` WRITE;
/*!40000 ALTER TABLE `QRTZ_PAUSED_TRIGGER_GRPS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_PAUSED_TRIGGER_GRPS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_SCHEDULER_STATE`
--

DROP TABLE IF EXISTS `QRTZ_SCHEDULER_STATE`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_SCHEDULER_STATE` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `INSTANCE_NAME` varchar(190) NOT NULL,
  `LAST_CHECKIN_TIME` bigint NOT NULL,
  `CHECKIN_INTERVAL` bigint NOT NULL,
  PRIMARY KEY (`SCHED_NAME`,`INSTANCE_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_SCHEDULER_STATE`
--

LOCK TABLES `QRTZ_SCHEDULER_STATE` WRITE;
/*!40000 ALTER TABLE `QRTZ_SCHEDULER_STATE` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_SCHEDULER_STATE` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_SIMPLE_TRIGGERS`
--

DROP TABLE IF EXISTS `QRTZ_SIMPLE_TRIGGERS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_SIMPLE_TRIGGERS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `TRIGGER_NAME` varchar(190) NOT NULL,
  `TRIGGER_GROUP` varchar(190) NOT NULL,
  `REPEAT_COUNT` bigint NOT NULL,
  `REPEAT_INTERVAL` bigint NOT NULL,
  `TIMES_TRIGGERED` bigint NOT NULL,
  PRIMARY KEY (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`),
  CONSTRAINT `FK_QRTZ_SIMPLE_TRIGGERS_TRIGGERS` FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`) REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_SIMPLE_TRIGGERS`
--

LOCK TABLES `QRTZ_SIMPLE_TRIGGERS` WRITE;
/*!40000 ALTER TABLE `QRTZ_SIMPLE_TRIGGERS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_SIMPLE_TRIGGERS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_SIMPROP_TRIGGERS`
--

DROP TABLE IF EXISTS `QRTZ_SIMPROP_TRIGGERS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_SIMPROP_TRIGGERS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `TRIGGER_NAME` varchar(190) NOT NULL,
  `TRIGGER_GROUP` varchar(190) NOT NULL,
  `STR_PROP_1` varchar(512) DEFAULT NULL,
  `STR_PROP_2` varchar(512) DEFAULT NULL,
  `STR_PROP_3` varchar(512) DEFAULT NULL,
  `INT_PROP_1` int DEFAULT NULL,
  `INT_PROP_2` int DEFAULT NULL,
  `LONG_PROP_1` bigint DEFAULT NULL,
  `LONG_PROP_2` bigint DEFAULT NULL,
  `DEC_PROP_1` decimal(13,4) DEFAULT NULL,
  `DEC_PROP_2` decimal(13,4) DEFAULT NULL,
  `BOOL_PROP_1` varchar(1) DEFAULT NULL,
  `BOOL_PROP_2` varchar(1) DEFAULT NULL,
  PRIMARY KEY (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`),
  CONSTRAINT `FK_QRTZ_SIMPROP_TRIGGERS_TRIGGERS` FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`) REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_SIMPROP_TRIGGERS`
--

LOCK TABLES `QRTZ_SIMPROP_TRIGGERS` WRITE;
/*!40000 ALTER TABLE `QRTZ_SIMPROP_TRIGGERS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_SIMPROP_TRIGGERS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `QRTZ_TRIGGERS`
--

DROP TABLE IF EXISTS `QRTZ_TRIGGERS`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `QRTZ_TRIGGERS` (
  `SCHED_NAME` varchar(120) NOT NULL,
  `TRIGGER_NAME` varchar(190) NOT NULL,
  `TRIGGER_GROUP` varchar(190) NOT NULL,
  `JOB_NAME` varchar(190) NOT NULL,
  `JOB_GROUP` varchar(190) NOT NULL,
  `DESCRIPTION` varchar(250) DEFAULT NULL,
  `NEXT_FIRE_TIME` bigint DEFAULT NULL,
  `PREV_FIRE_TIME` bigint DEFAULT NULL,
  `PRIORITY` int DEFAULT NULL,
  `TRIGGER_STATE` varchar(16) NOT NULL,
  `TRIGGER_TYPE` varchar(8) NOT NULL,
  `START_TIME` bigint NOT NULL,
  `END_TIME` bigint DEFAULT NULL,
  `CALENDAR_NAME` varchar(190) DEFAULT NULL,
  `MISFIRE_INSTR` smallint DEFAULT NULL,
  `JOB_DATA` blob,
  PRIMARY KEY (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`),
  KEY `IDX_QRTZ_T_J` (`SCHED_NAME`,`JOB_NAME`,`JOB_GROUP`),
  KEY `IDX_QRTZ_T_JG` (`SCHED_NAME`,`JOB_GROUP`),
  KEY `IDX_QRTZ_T_C` (`SCHED_NAME`,`CALENDAR_NAME`),
  KEY `IDX_QRTZ_T_G` (`SCHED_NAME`,`TRIGGER_GROUP`),
  KEY `IDX_QRTZ_T_STATE` (`SCHED_NAME`,`TRIGGER_STATE`),
  KEY `IDX_QRTZ_T_N_STATE` (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`,`TRIGGER_STATE`),
  KEY `IDX_QRTZ_T_N_G_STATE` (`SCHED_NAME`,`TRIGGER_GROUP`,`TRIGGER_STATE`),
  KEY `IDX_QRTZ_T_NEXT_FIRE_TIME` (`SCHED_NAME`,`NEXT_FIRE_TIME`),
  KEY `IDX_QRTZ_T_NFT_ST` (`SCHED_NAME`,`TRIGGER_STATE`,`NEXT_FIRE_TIME`),
  KEY `IDX_QRTZ_T_NFT_MISFIRE` (`SCHED_NAME`,`MISFIRE_INSTR`,`NEXT_FIRE_TIME`),
  KEY `IDX_QRTZ_T_NFT_ST_MISFIRE` (`SCHED_NAME`,`MISFIRE_INSTR`,`NEXT_FIRE_TIME`,`TRIGGER_STATE`),
  KEY `IDX_QRTZ_T_NFT_ST_MISFIRE_GRP` (`SCHED_NAME`,`MISFIRE_INSTR`,`NEXT_FIRE_TIME`,`TRIGGER_GROUP`,`TRIGGER_STATE`),
  CONSTRAINT `FK_QRTZ_TRIGGERS_JOB_DETAILS` FOREIGN KEY (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`) REFERENCES `QRTZ_JOB_DETAILS` (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `QRTZ_TRIGGERS`
--

LOCK TABLES `QRTZ_TRIGGERS` WRITE;
/*!40000 ALTER TABLE `QRTZ_TRIGGERS` DISABLE KEYS */;
/*!40000 ALTER TABLE `QRTZ_TRIGGERS` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_api_access_log`
--

DROP TABLE IF EXISTS `infra_api_access_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_api_access_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '链路追踪编号',
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户编号',
  `user_type` tinyint NOT NULL DEFAULT '0' COMMENT '用户类型',
  `application_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '应用名',
  `request_method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '请求方法名',
  `request_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '请求地址',
  `request_params` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '请求参数',
  `response_body` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '响应结果',
  `user_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户 IP',
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '浏览器 UA',
  `operate_module` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作模块',
  `operate_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作名',
  `operate_type` tinyint DEFAULT '0' COMMENT '操作分类',
  `begin_time` datetime NOT NULL COMMENT '开始请求时间',
  `end_time` datetime NOT NULL COMMENT '结束请求时间',
  `duration` int NOT NULL COMMENT '执行时长',
  `result_code` int NOT NULL DEFAULT '0' COMMENT '结果码',
  `result_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '结果提示',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_create_time` (`create_time`) USING BTREE,
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=36233 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='API 访问日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_api_access_log`
--

LOCK TABLES `infra_api_access_log` WRITE;
/*!40000 ALTER TABLE `infra_api_access_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `infra_api_access_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_api_error_log`
--

DROP TABLE IF EXISTS `infra_api_error_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_api_error_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '链路追踪编号',
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户编号',
  `user_type` tinyint NOT NULL DEFAULT '0' COMMENT '用户类型',
  `application_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '应用名',
  `request_method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '请求方法名',
  `request_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '请求地址',
  `request_params` varchar(8000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '请求参数',
  `user_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户 IP',
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '浏览器 UA',
  `exception_time` datetime NOT NULL COMMENT '异常发生时间',
  `exception_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '异常名',
  `exception_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '异常导致的消息',
  `exception_root_cause_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '异常导致的根消息',
  `exception_stack_trace` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '异常的栈轨迹',
  `exception_class_name` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '异常发生的类全名',
  `exception_file_name` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '异常发生的类文件',
  `exception_method_name` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '异常发生的方法名',
  `exception_line_number` int NOT NULL COMMENT '异常发生的方法所在行',
  `process_status` tinyint NOT NULL COMMENT '处理状态',
  `process_time` datetime DEFAULT NULL COMMENT '处理时间',
  `process_user_id` bigint NOT NULL DEFAULT '0' COMMENT '处理用户编号',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_process_status` (`process_status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=23210 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='系统异常日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_api_error_log`
--

LOCK TABLES `infra_api_error_log` WRITE;
/*!40000 ALTER TABLE `infra_api_error_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `infra_api_error_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_config`
--

DROP TABLE IF EXISTS `infra_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数分组',
  `type` tinyint NOT NULL COMMENT '参数类型',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '参数键名',
  `value` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '参数键值',
  `visible` bit(1) NOT NULL COMMENT '是否可见',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='参数配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_config`
--

LOCK TABLES `infra_config` WRITE;
/*!40000 ALTER TABLE `infra_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `infra_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_file`
--

DROP TABLE IF EXISTS `infra_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_file` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '文件编号',
  `config_id` bigint DEFAULT NULL COMMENT '配置编号',
  `name` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件名',
  `path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件路径',
  `url` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件 URL',
  `type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件类型',
  `size` bigint NOT NULL COMMENT '文件大小',
  `access_type` tinyint NOT NULL DEFAULT '2' COMMENT '读取策略：1 公开读取，2 私有读取',
  `upload_status` tinyint NOT NULL DEFAULT '1' COMMENT '上传状态：0 等待上传，1 已完成，2 校验中',
  `upload_staging_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '校验前的私有临时对象路径',
  `upload_token_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '一次性上传令牌 SHA-256 摘要',
  `upload_expires_at` datetime DEFAULT NULL COMMENT '预签名上传过期时间',
  `upload_user_id` bigint DEFAULT NULL COMMENT '预签名签发用户编号',
  `upload_user_type` tinyint DEFAULT NULL COMMENT '预签名签发用户类型',
  `owner_user_id` bigint DEFAULT NULL COMMENT '文件所有者用户编号',
  `owner_user_type` tinyint DEFAULT NULL COMMENT '文件所有者用户类型',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务类型：非空表示受业务授权 SPI 管控',
  `business_id` bigint DEFAULT NULL COMMENT '业务对象编号',
  `delete_status` tinyint NOT NULL DEFAULT '0' COMMENT '删除状态：0 正常，1 等待清理',
  `delete_attempts` int NOT NULL DEFAULT '0' COMMENT '外部存储清理失败次数',
  `delete_next_retry_time` datetime DEFAULT NULL COMMENT '下次清理重试时间',
  `delete_last_error` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近失败异常类型',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_config_path` (`config_id`,`path`),
  UNIQUE KEY `uk_upload_token_hash` (`upload_token_hash`),
  UNIQUE KEY `uk_upload_staging_path` (`config_id`,`upload_staging_path`),
  CONSTRAINT `ck_file_business_binding` CHECK (((`business_type` is null) = (`business_id` is null))),
  KEY `idx_config_id` (`config_id`),
  KEY `idx_business_binding` (`business_type`,`business_id`),
  KEY `idx_path` (`path`),
  KEY `idx_type` (`type`),
  KEY `idx_delete_retry` (`delete_status`,`delete_next_retry_time`),
  KEY `idx_upload_expiry` (`upload_status`,`upload_expires_at`),
  CONSTRAINT `fk_file_config` FOREIGN KEY (`config_id`) REFERENCES `infra_file_config` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_file_access_type` CHECK (`access_type` in (1,2))
) ENGINE=InnoDB AUTO_INCREMENT=2142 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='文件表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_file`
--

LOCK TABLES `infra_file` WRITE;
/*!40000 ALTER TABLE `infra_file` DISABLE KEYS */;
/*!40000 ALTER TABLE `infra_file` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_file_config`
--

DROP TABLE IF EXISTS `infra_file_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_file_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置名',
  `storage` tinyint NOT NULL COMMENT '存储器',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `master` bit(1) NOT NULL COMMENT '是否为主配置',
  `config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件客户端配置的版本化 AES-GCM 密文',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_master` (`master`)
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='文件配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_file_config`
--

LOCK TABLES `infra_file_config` WRITE;
/*!40000 ALTER TABLE `infra_file_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `infra_file_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_file_content`
--

DROP TABLE IF EXISTS `infra_file_content`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_file_content` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `config_id` bigint NOT NULL COMMENT '配置编号',
  `path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件路径',
  `content` mediumblob NOT NULL COMMENT '文件内容',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_config_id` (`config_id`),
  CONSTRAINT `fk_file_content_config` FOREIGN KEY (`config_id`) REFERENCES `infra_file_config` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=286 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='文件表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_file_content`
--

LOCK TABLES `infra_file_content` WRITE;
/*!40000 ALTER TABLE `infra_file_content` DISABLE KEYS */;
/*!40000 ALTER TABLE `infra_file_content` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_job`
--

DROP TABLE IF EXISTS `infra_job`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_job` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务编号',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务名称',
  `status` tinyint NOT NULL COMMENT '任务状态',
  `handler_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '处理器的名字',
  `handler_param` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理器的参数',
  `cron_expression` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'CRON 表达式',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
  `retry_interval` int NOT NULL DEFAULT '0' COMMENT '重试间隔',
  `monitor_timeout` int NOT NULL DEFAULT '0' COMMENT '监控超时时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='定时任务表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_job`
--

LOCK TABLES `infra_job` WRITE;
/*!40000 ALTER TABLE `infra_job` DISABLE KEYS */;
INSERT INTO `infra_job` VALUES (25,'访问日志清理 Job',1,'accessLogCleanJob','','0 0 0 * * ?',3,0,0,'1','2023-10-03 10:59:41','1','2026-08-23 00:00:00',_binary '\0'),(26,'错误日志清理 Job',1,'errorLogCleanJob','','0 0 0 * * ?',3,0,0,'1','2023-10-03 11:00:43','1','2026-08-23 00:00:00',_binary '\0'),(27,'任务日志清理 Job',1,'jobLogCleanJob','','0 0 0 * * ?',3,0,0,'1','2023-10-03 11:01:33','1','2026-08-23 00:00:00',_binary '\0'),(28,'system 数据保留清理 Job',1,'systemDataRetentionCleanJob','','0 0 1 * * ?',3,0,0,'1','2026-08-23 00:00:00','1','2026-08-23 00:00:00',_binary '\0'),(29,'system 数据完整性审计 Job',1,'systemDataIntegrityAuditJob','','0 30 2 * * ?',0,0,0,'1','2026-08-23 00:00:00','1','2026-08-23 00:00:00',_binary '\0'),(30,'infra 数据完整性审计 Job',1,'infraDataIntegrityAuditJob','','0 45 2 * * ?',0,0,0,'1','2026-08-24 00:00:00','1','2026-08-24 00:00:00',_binary '\0'),(31,'文件外部存储清理重试 Job',1,'fileDeletionRetryJob','','0 * * * * ?',0,0,0,'1','2026-08-29 00:00:00','1','2026-08-29 00:00:00',_binary '\0'),(32,'AI 任务恢复 Job',1,'aiTaskRecoveryJob','','0 * * * * ?',0,0,0,'1','2026-09-19 22:00:00','1','2026-09-19 22:00:00',_binary '\0'),(33,'AI 保留期清理 Job',1,'aiRetentionCleanupJob','','0 15 * * * ?',0,0,0,'1','2026-09-20 09:00:00','1','2026-09-20 09:00:00',_binary '\0');
/*!40000 ALTER TABLE `infra_job` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `infra_job_log`
--

DROP TABLE IF EXISTS `infra_job_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `infra_job_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志编号',
  `job_id` bigint NOT NULL COMMENT '任务编号',
  `handler_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '处理器的名字',
  `handler_param` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理器的参数',
  `execute_index` tinyint NOT NULL DEFAULT '1' COMMENT '第几次执行',
  `begin_time` datetime NOT NULL COMMENT '开始执行时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束执行时间',
  `duration` int DEFAULT NULL COMMENT '执行时长',
  `status` tinyint NOT NULL COMMENT '任务状态',
  `result` varchar(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '结果数据',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_job_id` (`job_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=987 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='定时任务日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `infra_job_log`
--

LOCK TABLES `infra_job_log` WRITE;
/*!40000 ALTER TABLE `infra_job_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `infra_job_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_dept`
--

DROP TABLE IF EXISTS `system_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_dept` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门id',
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '部门名称',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父部门id',
  `sort` int NOT NULL DEFAULT '0' COMMENT '显示顺序',
  `leader_user_id` bigint DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `status` tinyint NOT NULL COMMENT '部门状态（0正常 1停用）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_status` (`status`),
  KEY `idx_leader_user_id` (`leader_user_id`),
  CONSTRAINT `chk_dept_parent_id_non_negative` CHECK ((`parent_id` >= 0))
) ENGINE=InnoDB AUTO_INCREMENT=116 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='部门表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_dept`
--

LOCK TABLES `system_dept` WRITE;
/*!40000 ALTER TABLE `system_dept` DISABLE KEYS */;
INSERT INTO `system_dept` VALUES (100,'总公司',0,0,1,NULL,NULL,0,'admin','2021-01-05 17:03:47','1','2026-08-23 14:13:52',_binary '\0');
/*!40000 ALTER TABLE `system_dept` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_dict_data`
--

DROP TABLE IF EXISTS `system_dict_data`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_dict_data` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '字典编码',
  `sort` int NOT NULL DEFAULT '0' COMMENT '字典排序',
  `label` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '字典标签',
  `value` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '字典类型',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `color_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '颜色类型',
  `css_class` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'css 样式',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_dict_type` (`dict_type`),
  CONSTRAINT `fk_dict_data_type` FOREIGN KEY (`dict_type`) REFERENCES `system_dict_type` (`type`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=3036 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='字典数据表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_dict_data`
--

LOCK TABLES `system_dict_data` WRITE;
/*!40000 ALTER TABLE `system_dict_data` DISABLE KEYS */;
INSERT INTO `system_dict_data` VALUES (1,1,'男','1','system_user_sex',0,'default','A','性别男','admin','2021-01-05 17:03:48','1','2022-03-29 00:14:39',_binary '\0'),(2,2,'女','2','system_user_sex',0,'success','','性别女','admin','2021-01-05 17:03:48','1','2023-11-15 23:30:37',_binary '\0'),(8,1,'正常','1','infra_job_status',0,'success','','正常状态','admin','2021-01-05 17:03:48','1','2022-02-16 19:33:38',_binary '\0'),(9,2,'暂停','2','infra_job_status',0,'danger','','停用状态','admin','2021-01-05 17:03:48','1','2022-02-16 19:33:45',_binary '\0'),(12,1,'系统内置','1','infra_config_type',0,'danger','','参数类型 - 系统内置','admin','2021-01-05 17:03:48','1','2022-02-16 19:06:02',_binary '\0'),(13,2,'自定义','2','infra_config_type',0,'primary','','参数类型 - 自定义','admin','2021-01-05 17:03:48','1','2022-02-16 19:06:07',_binary '\0'),(14,1,'通知','1','system_notice_type',0,'success','','通知','admin','2021-01-05 17:03:48','1','2022-02-16 13:05:57',_binary '\0'),(15,2,'公告','2','system_notice_type',0,'info','','公告','admin','2021-01-05 17:03:48','1','2022-02-16 13:06:01',_binary '\0'),(27,1,'开启','0','common_status',0,'primary','','开启状态','admin','2021-01-05 17:03:48','1','2022-02-16 08:00:39',_binary '\0'),(28,2,'关闭','1','common_status',0,'info','','关闭状态','admin','2021-01-05 17:03:48','1','2022-02-16 08:00:44',_binary '\0'),(29,1,'目录','1','system_menu_type',0,'','','目录','admin','2021-01-05 17:03:48','','2022-02-01 16:43:45',_binary '\0'),(30,2,'菜单','2','system_menu_type',0,'','','菜单','admin','2021-01-05 17:03:48','','2022-02-01 16:43:41',_binary '\0'),(31,3,'按钮','3','system_menu_type',0,'','','按钮','admin','2021-01-05 17:03:48','','2022-02-01 16:43:39',_binary '\0'),(32,1,'内置','1','system_role_type',0,'danger','','内置角色','admin','2021-01-05 17:03:48','1','2022-02-16 13:02:08',_binary '\0'),(33,2,'自定义','2','system_role_type',0,'primary','','自定义角色','admin','2021-01-05 17:03:48','1','2022-02-16 13:02:12',_binary '\0'),(34,1,'全部数据权限','1','system_data_scope',0,'','','全部数据权限','admin','2021-01-05 17:03:48','','2022-02-01 16:47:17',_binary '\0'),(35,2,'指定部门数据权限','2','system_data_scope',0,'','','指定部门数据权限','admin','2021-01-05 17:03:48','','2022-02-01 16:47:18',_binary '\0'),(36,3,'本部门数据权限','3','system_data_scope',0,'','','本部门数据权限','admin','2021-01-05 17:03:48','','2022-02-01 16:47:16',_binary '\0'),(37,4,'本部门及以下数据权限','4','system_data_scope',0,'','','本部门及以下数据权限','admin','2021-01-05 17:03:48','','2022-02-01 16:47:21',_binary '\0'),(38,5,'仅本人数据权限','5','system_data_scope',0,'','','仅本人数据权限','admin','2021-01-05 17:03:48','','2022-02-01 16:47:23',_binary '\0'),(39,0,'成功','0','system_login_result',0,'success','','登陆结果 - 成功','','2021-01-18 06:17:36','1','2022-02-16 13:23:49',_binary '\0'),(40,10,'账号或密码不正确','10','system_login_result',0,'primary','','登陆结果 - 账号或密码不正确','','2021-01-18 06:17:54','1','2022-02-16 13:24:27',_binary '\0'),(41,20,'用户被禁用','20','system_login_result',0,'warning','','登陆结果 - 用户被禁用','','2021-01-18 06:17:54','1','2022-02-16 13:23:57',_binary '\0'),(42,30,'验证码不存在','30','system_login_result',0,'info','','登陆结果 - 验证码不存在','','2021-01-18 06:17:54','1','2022-02-16 13:24:07',_binary '\0'),(43,31,'验证码不正确','31','system_login_result',0,'info','','登陆结果 - 验证码不正确','','2021-01-18 06:17:54','1','2022-02-16 13:24:11',_binary '\0'),(44,100,'未知异常','100','system_login_result',0,'danger','','登陆结果 - 未知异常','','2021-01-18 06:17:54','1','2022-02-16 13:24:23',_binary '\0'),(45,1,'是','true','infra_boolean_string',0,'danger','','Boolean 是否类型 - 是','','2021-01-19 03:20:55','1','2022-03-15 23:01:45',_binary '\0'),(46,1,'否','false','infra_boolean_string',0,'info','','Boolean 是否类型 - 否','','2021-01-19 03:20:55','1','2022-03-15 23:09:45',_binary '\0'),(53,0,'初始化中','0','infra_job_status',0,'primary','',NULL,'','2021-02-07 07:46:49','1','2022-02-16 19:33:29',_binary '\0'),(57,0,'运行中','0','infra_job_log_status',0,'primary','','RUNNING','','2021-02-08 10:04:24','1','2022-02-16 19:07:48',_binary '\0'),(58,1,'成功','1','infra_job_log_status',0,'success','',NULL,'','2021-02-08 10:06:57','1','2022-02-16 19:07:52',_binary '\0'),(59,2,'失败','2','infra_job_log_status',0,'warning','','失败','','2021-02-08 10:07:38','1','2022-02-16 19:07:56',_binary '\0'),(60,1,'会员','1','user_type',0,'primary','',NULL,'','2021-02-26 00:16:27','1','2022-02-16 10:22:19',_binary '\0'),(61,2,'管理员','2','user_type',0,'success','',NULL,'','2021-02-26 00:16:34','1','2025-04-06 18:37:43',_binary '\0'),(62,0,'未处理','0','infra_api_error_log_process_status',0,'primary','',NULL,'','2021-02-26 07:07:19','1','2022-02-16 20:14:17',_binary '\0'),(63,1,'已处理','1','infra_api_error_log_process_status',0,'success','',NULL,'','2021-02-26 07:07:26','1','2022-02-16 20:14:08',_binary '\0'),(64,2,'已忽略','2','infra_api_error_log_process_status',0,'danger','',NULL,'','2021-02-26 07:07:34','1','2022-02-16 20:14:14',_binary '\0'),(66,1,'阿里云','ALIYUN','system_sms_channel_code',0,'primary','',NULL,'1','2021-04-05 01:05:26','1','2024-07-22 22:23:25',_binary '\0'),(67,1,'验证码','1','system_sms_template_type',0,'warning','',NULL,'1','2021-04-05 21:50:57','1','2022-02-16 12:48:30',_binary '\0'),(68,2,'通知','2','system_sms_template_type',0,'primary','',NULL,'1','2021-04-05 21:51:08','1','2022-02-16 12:48:27',_binary '\0'),(69,0,'营销','3','system_sms_template_type',0,'danger','',NULL,'1','2021-04-05 21:51:15','1','2022-02-16 12:48:22',_binary '\0'),(70,0,'初始化','0','system_sms_send_status',0,'primary','',NULL,'1','2021-04-11 20:18:33','1','2022-02-16 10:26:07',_binary '\0'),(71,1,'发送成功','10','system_sms_send_status',0,'success','',NULL,'1','2021-04-11 20:18:43','1','2022-02-16 10:25:56',_binary '\0'),(72,2,'发送失败','20','system_sms_send_status',0,'danger','',NULL,'1','2021-04-11 20:18:49','1','2022-02-16 10:26:03',_binary '\0'),(73,3,'不发送','30','system_sms_send_status',0,'info','',NULL,'1','2021-04-11 20:19:44','1','2022-02-16 10:26:10',_binary '\0'),(74,0,'等待结果','0','system_sms_receive_status',0,'primary','',NULL,'1','2021-04-11 20:27:43','1','2022-02-16 10:28:24',_binary '\0'),(75,1,'接收成功','10','system_sms_receive_status',0,'success','',NULL,'1','2021-04-11 20:29:25','1','2022-02-16 10:28:28',_binary '\0'),(76,2,'接收失败','20','system_sms_receive_status',0,'danger','',NULL,'1','2021-04-11 20:29:31','1','2022-02-16 10:28:32',_binary '\0'),(80,100,'账号登录','100','system_login_type',0,'primary','','账号登录','1','2021-10-06 00:52:02','1','2022-02-16 13:11:34',_binary '\0'),(83,200,'主动登出','200','system_login_type',0,'primary','','主动登出','1','2021-10-06 00:52:58','1','2022-02-16 13:11:49',_binary '\0'),(85,202,'强制登出','202','system_login_type',0,'danger','','强制退出','1','2021-10-06 00:53:41','1','2022-02-16 13:11:57',_binary '\0'),(1151,10,'本地磁盘','10','infra_file_storage',0,'default','',NULL,'1','2022-03-15 00:25:41','1','2022-03-15 00:25:56',_binary '\0'),(1194,10,'微信小程序','10','terminal',0,'default','','终端 - 微信小程序','1','2022-12-10 10:51:11','1','2022-12-10 10:51:57',_binary '\0'),(1195,20,'H5 网页','20','terminal',0,'default','','终端 - H5 网页','1','2022-12-10 10:51:30','1','2022-12-10 10:51:59',_binary '\0'),(1196,11,'微信公众号','11','terminal',0,'default','','终端 - 微信公众号','1','2022-12-10 10:54:16','1','2022-12-10 10:52:01',_binary '\0'),(1197,31,'苹果 App','31','terminal',0,'default','','终端 - 苹果 App','1','2022-12-10 10:54:42','1','2022-12-10 10:52:18',_binary '\0'),(1198,32,'安卓 App','32','terminal',0,'default','','终端 - 安卓 App','1','2022-12-10 10:55:02','1','2022-12-10 10:59:17',_binary '\0'),(1227,1,'通知公告','1','system_notify_template_type',0,'primary','','站内信模版的类型 - 通知公告','1','2023-01-28 10:35:59','1','2023-01-28 10:35:59',_binary '\0'),(1228,2,'系统消息','2','system_notify_template_type',0,'success','','站内信模版的类型 - 系统消息','1','2023-01-28 10:36:20','1','2023-01-28 10:36:25',_binary '\0');
INSERT INTO `system_dict_data` VALUES (1154,20,'S3 对象存储','20','infra_file_storage',0,'default','',NULL,'1','2022-03-15 00:26:31','1','2022-03-15 00:26:45',_binary '\0');
/*!40000 ALTER TABLE `system_dict_data` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_dict_type`
--

DROP TABLE IF EXISTS `system_dict_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_dict_type` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '字典主键',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '字典名称',
  `type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '字典类型',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `deleted_time` datetime DEFAULT NULL COMMENT '删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_type` (`type`)
) ENGINE=InnoDB AUTO_INCREMENT=2010 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='字典类型表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_dict_type`
--

LOCK TABLES `system_dict_type` WRITE;
/*!40000 ALTER TABLE `system_dict_type` DISABLE KEYS */;
INSERT INTO `system_dict_type` VALUES (1,'用户性别','system_user_sex',0,NULL,'admin','2021-01-05 17:03:48','1','2022-05-16 20:29:32',_binary '\0',NULL),(6,'参数类型','infra_config_type',0,NULL,'admin','2021-01-05 17:03:48','','2022-02-01 16:36:54',_binary '\0',NULL),(7,'通知类型','system_notice_type',0,NULL,'admin','2021-01-05 17:03:48','','2022-02-01 16:35:26',_binary '\0',NULL),(10,'系统状态','common_status',0,NULL,'admin','2021-01-05 17:03:48','','2022-02-01 16:21:28',_binary '\0',NULL),(11,'Boolean 是否类型','infra_boolean_string',0,'boolean 转是否','','2021-01-19 03:20:08','','2022-02-01 16:37:10',_binary '\0',NULL),(104,'登陆结果','system_login_result',0,'登陆结果','','2021-01-18 06:17:11','','2022-02-01 16:36:00',_binary '\0',NULL),(107,'定时任务状态','infra_job_status',0,NULL,'','2021-02-07 07:44:16','','2022-02-01 16:51:11',_binary '\0',NULL),(108,'定时任务日志状态','infra_job_log_status',0,NULL,'','2021-02-08 10:03:51','','2022-02-01 16:50:43',_binary '\0',NULL),(109,'用户类型','user_type',0,NULL,'','2021-02-26 00:15:51','','2021-02-26 00:15:51',_binary '\0',NULL),(110,'API 异常数据的处理状态','infra_api_error_log_process_status',0,NULL,'','2021-02-26 07:07:01','','2022-02-01 16:50:53',_binary '\0',NULL),(111,'短信渠道编码','system_sms_channel_code',0,NULL,'1','2021-04-05 01:04:50','1','2022-02-16 02:09:08',_binary '\0',NULL),(112,'短信模板的类型','system_sms_template_type',0,NULL,'1','2021-04-05 21:50:43','1','2022-02-01 16:35:06',_binary '\0',NULL),(113,'短信发送状态','system_sms_send_status',0,NULL,'1','2021-04-11 20:18:03','1','2022-02-01 16:35:09',_binary '\0',NULL),(114,'短信接收状态','system_sms_receive_status',0,NULL,'1','2021-04-11 20:27:14','1','2022-02-01 16:35:14',_binary '\0',NULL),(116,'登陆日志的类型','system_login_type',0,'登陆日志的类型','1','2021-10-06 00:50:46','1','2022-02-01 16:35:56',_binary '\0',NULL),(145,'角色类型','system_role_type',0,'角色类型','1','2022-02-16 13:01:46','1','2022-02-16 13:01:46',_binary '\0',NULL),(146,'文件存储器','infra_file_storage',0,'文件存储器','1','2022-03-15 00:24:38','1','2022-03-15 00:24:38',_binary '\0',NULL),(160,'终端','terminal',0,'终端','1','2022-12-10 10:50:50','1','2022-12-10 10:53:11',_binary '\0',NULL),(167,'站内信模版的类型','system_notify_template_type',0,'站内信模版的类型','1','2023-01-28 10:35:10','1','2023-01-28 10:35:10',_binary '\0','1970-01-01 00:00:00');
INSERT INTO `system_dict_type` VALUES
  (2008,'菜单类型','system_menu_type',0,'V24 补齐内置字典类型','1','2026-08-24 00:00:00','1','2026-08-24 00:00:00',_binary '\0',NULL),
  (2009,'数据范围','system_data_scope',0,'V24 补齐内置字典类型','1','2026-08-24 00:00:00','1','2026-08-24 00:00:00',_binary '\0',NULL);
/*!40000 ALTER TABLE `system_dict_type` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_login_log`
--

DROP TABLE IF EXISTS `system_login_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_login_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
  `log_type` bigint NOT NULL COMMENT '日志类型',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '链路追踪编号',
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户编号',
  `user_type` tinyint NOT NULL DEFAULT '0' COMMENT '用户类型',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '用户账号',
  `result` tinyint NOT NULL COMMENT '登陆结果',
  `user_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户 IP',
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '浏览器 UA',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_id` (`user_id`),
  KEY `idx_username` (`username`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='系统访问记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_login_log`
--

LOCK TABLES `system_login_log` WRITE;
/*!40000 ALTER TABLE `system_login_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_login_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_menu`
--

DROP TABLE IF EXISTS `system_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '菜单名称',
  `permission` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '权限标识',
  `type` tinyint NOT NULL COMMENT '菜单类型',
  `sort` int NOT NULL DEFAULT '0' COMMENT '显示顺序',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父菜单ID',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '路由地址',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '#' COMMENT '菜单图标',
  `component` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '组件路径',
  `component_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '组件名',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '菜单状态',
  `visible` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否可见',
  `keep_alive` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否缓存',
  `always_show` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否总是显示',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_parent_id` (`parent_id`),
  CONSTRAINT `chk_menu_parent_id_non_negative` CHECK ((`parent_id` >= 0))
) ENGINE=InnoDB AUTO_INCREMENT=5047 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='菜单权限表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_menu`
--

LOCK TABLES `system_menu` WRITE;
/*!40000 ALTER TABLE `system_menu` DISABLE KEYS */;
INSERT INTO `system_menu` VALUES (1,'系统管理','',1,10,0,'/system','ep:tools',NULL,NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2025-03-15 21:30:27',_binary '\0'),(2,'基础设施','',1,20,0,'/infra','ep:monitor',NULL,NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-03-01 08:28:40',_binary '\0'),(100,'用户管理','system:user:list',2,1,1,'user','ep:avatar','system/user/index','SystemUser',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2025-03-15 21:30:41',_binary '\0'),(101,'角色管理','',2,2,1,'role','ep:user','system/role/index','SystemRole',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-05-01 18:35:29',_binary '\0'),(103,'部门管理','',2,4,1,'dept','fa:address-card','system/dept/index','SystemDept',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-02-29 01:06:28',_binary '\0'),(104,'岗位管理','',2,5,1,'post','fa:address-book-o','system/post/index','SystemPost',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-02-29 01:06:39',_binary '\0'),(105,'字典管理','',2,6,1,'dict','ep:collection','system/dict/index','SystemDictType',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-02-29 01:07:12',_binary '\0'),(106,'配置管理','',2,8,2,'config','fa:connectdevelop','infra/config/index','InfraConfig',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-04-23 00:02:45',_binary '\0'),(107,'通知公告','',2,4,2739,'notice','ep:takeaway-box','system/notice/index','SystemNotice',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-04-22 23:56:17',_binary '\0'),(108,'审计日志','',1,9,1,'log','ep:document-copy','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-02-29 01:08:30',_binary '\0'),(110,'定时任务','',2,7,2,'job','fa-solid:tasks','infra/job/index','InfraJob',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-02-29 08:57:36',_binary '\0'),(500,'操作日志','',2,1,108,'operate-log','ep:position','system/operate-log/index','SystemOperateLog',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-02-29 01:09:59',_binary '\0'),(501,'登录日志','',2,2,108,'login-log','ep:promotion','system/login-log/index','SystemLoginLog',0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2024-02-29 01:10:29',_binary '\0'),(1001,'用户查询','system:user:query',3,1,100,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1002,'用户新增','system:user:create',3,2,100,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1003,'用户修改','system:user:update',3,3,100,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1004,'用户删除','system:user:delete',3,4,100,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1005,'用户导出','system:user:export',3,5,100,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1006,'用户导入','system:user:import',3,6,100,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1007,'重置密码','system:user:update-password',3,7,100,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1008,'角色查询','system:role:query',3,1,101,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1009,'角色新增','system:role:create',3,2,101,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1010,'角色修改','system:role:update',3,3,101,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1011,'角色删除','system:role:delete',3,4,101,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1012,'角色导出','system:role:export',3,5,101,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1017,'部门查询','system:dept:query',3,1,103,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1018,'部门新增','system:dept:create',3,2,103,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1019,'部门修改','system:dept:update',3,3,103,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1020,'部门删除','system:dept:delete',3,4,103,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1021,'岗位查询','system:post:query',3,1,104,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1022,'岗位新增','system:post:create',3,2,104,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1023,'岗位修改','system:post:update',3,3,104,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1024,'岗位删除','system:post:delete',3,4,104,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1025,'岗位导出','system:post:export',3,5,104,'','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1026,'字典查询','system:dict:query',3,1,105,'#','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1027,'字典新增','system:dict:create',3,2,105,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1028,'字典修改','system:dict:update',3,3,105,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1029,'字典删除','system:dict:delete',3,4,105,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1030,'字典导出','system:dict:export',3,5,105,'#','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1031,'配置查询','infra:config:query',3,1,106,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1032,'配置新增','infra:config:create',3,2,106,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1033,'配置修改','infra:config:update',3,3,106,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1034,'配置删除','infra:config:delete',3,4,106,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1035,'配置导出','infra:config:export',3,5,106,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1036,'公告查询','system:notice:query',3,1,107,'#','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1037,'公告新增','system:notice:create',3,2,107,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1038,'公告修改','system:notice:update',3,3,107,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1039,'公告删除','system:notice:delete',3,4,107,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','1','2022-04-20 17:03:10',_binary '\0'),(1040,'操作查询','system:operate-log:query',3,1,500,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1042,'日志导出','system:operate-log:export',3,2,500,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1043,'登录查询','system:login-log:query',3,1,501,'#','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1045,'日志导出','system:login-log:export',3,3,501,'#','#','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1050,'任务新增','infra:job:create',3,2,110,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1051,'任务修改','infra:job:update',3,3,110,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1052,'任务删除','infra:job:delete',3,4,110,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1053,'状态修改','infra:job:update',3,5,110,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1054,'任务导出','infra:job:export',3,7,110,'','','',NULL,0,_binary '',_binary '',_binary '','admin','2021-01-05 17:03:48','','2022-04-20 17:03:10',_binary '\0'),(1063,'设置角色菜单权限','system:permission:assign-role-menu',3,6,101,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-01-06 17:53:44','','2022-04-20 17:03:10',_binary '\0'),(1064,'设置角色数据权限','system:permission:assign-role-data-scope',3,7,101,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-01-06 17:56:31','','2022-04-20 17:03:10',_binary '\0'),(1065,'设置用户角色','system:permission:assign-user-role',3,8,101,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-01-07 10:23:28','','2022-04-20 17:03:10',_binary '\0'),(1075,'任务触发','infra:job:trigger',3,8,110,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-02-07 13:03:10','','2022-04-20 17:03:10',_binary '\0'),(1087,'任务查询','infra:job:query',3,1,110,'','','',NULL,0,_binary '',_binary '',_binary '','1','2021-03-10 01:26:19','1','2022-04-20 17:03:10',_binary '\0'),(1090,'文件列表','',2,5,1243,'file','ep:upload-filled','infra/file/index','InfraFile',0,_binary '',_binary '',_binary '','','2021-03-12 20:16:20','1','2024-02-29 08:53:02',_binary '\0'),(1091,'文件查询','infra:file:query',3,1,1090,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-03-12 20:16:20','','2022-04-20 17:03:10',_binary '\0'),(1092,'文件删除','infra:file:delete',3,4,1090,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-03-12 20:16:20','','2022-04-20 17:03:10',_binary '\0'),(1093,'短信管理','',1,1,2739,'sms','ep:message',NULL,NULL,0,_binary '',_binary '',_binary '','1','2021-04-05 01:10:16','1','2024-04-22 23:56:03',_binary '\0'),(1094,'短信渠道','',2,0,1093,'sms-channel','fa:stack-exchange','system/sms/channel/index','SystemSmsChannel',0,_binary '',_binary '',_binary '','','2021-04-01 11:07:15','1','2024-02-29 01:15:54',_binary '\0'),(1095,'短信渠道查询','system:sms-channel:query',3,1,1094,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 11:07:15','','2022-04-20 17:03:10',_binary '\0'),(1096,'短信渠道创建','system:sms-channel:create',3,2,1094,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 11:07:15','','2022-04-20 17:03:10',_binary '\0'),(1097,'短信渠道更新','system:sms-channel:update',3,3,1094,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 11:07:15','','2022-04-20 17:03:10',_binary '\0'),(1098,'短信渠道删除','system:sms-channel:delete',3,4,1094,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 11:07:15','','2022-04-20 17:03:10',_binary '\0'),(1100,'短信模板','',2,1,1093,'sms-template','ep:connection','system/sms/template/index','SystemSmsTemplate',0,_binary '',_binary '',_binary '','','2021-04-01 17:35:17','1','2024-02-29 01:16:18',_binary '\0'),(1101,'短信模板查询','system:sms-template:query',3,1,1100,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 17:35:17','','2022-04-20 17:03:10',_binary '\0'),(1102,'短信模板创建','system:sms-template:create',3,2,1100,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 17:35:17','','2022-04-20 17:03:10',_binary '\0'),(1103,'短信模板更新','system:sms-template:update',3,3,1100,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 17:35:17','','2022-04-20 17:03:10',_binary '\0'),(1104,'短信模板删除','system:sms-template:delete',3,4,1100,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 17:35:17','','2022-04-20 17:03:10',_binary '\0'),(1105,'短信模板导出','system:sms-template:export',3,5,1100,'','','',NULL,0,_binary '',_binary '',_binary '','','2021-04-01 17:35:17','','2022-04-20 17:03:10',_binary '\0'),(1106,'发送测试短信','system:sms-template:send-sms',3,6,1100,'','','',NULL,0,_binary '',_binary '',_binary '','1','2021-04-11 00:26:40','1','2022-04-20 17:03:10',_binary '\0'),(1237,'文件配置','',2,0,1243,'file-config','fa-solid:file-signature','infra/file-config/index','InfraFileConfig',0,_binary '',_binary '',_binary '','','2022-03-15 14:35:28','1','2024-02-29 08:52:54',_binary '\0'),(1238,'文件配置查询','infra:file-config:query',3,1,1237,'','','',NULL,0,_binary '',_binary '',_binary '','','2022-03-15 14:35:28','','2022-04-20 17:03:10',_binary '\0'),(1239,'文件配置创建','infra:file-config:create',3,2,1237,'','','',NULL,0,_binary '',_binary '',_binary '','','2022-03-15 14:35:28','','2022-04-20 17:03:10',_binary '\0'),(1240,'文件配置更新','infra:file-config:update',3,3,1237,'','','',NULL,0,_binary '',_binary '',_binary '','','2022-03-15 14:35:28','','2022-04-20 17:03:10',_binary '\0'),(1241,'文件配置删除','infra:file-config:delete',3,4,1237,'','','',NULL,0,_binary '',_binary '',_binary '','','2022-03-15 14:35:28','','2022-04-20 17:03:10',_binary '\0'),(1242,'文件配置导出','infra:file-config:export',3,5,1237,'','','',NULL,0,_binary '',_binary '',_binary '','','2022-03-15 14:35:28','','2022-04-20 17:03:10',_binary '\0'),(1243,'文件管理','',2,6,2,'file','ep:files',NULL,'',0,_binary '',_binary '',_binary '','1','2022-03-16 23:47:40','1','2024-04-23 00:02:11',_binary '\0'),(2144,'站内信管理','',1,3,2739,'notify','ep:message-box',NULL,NULL,0,_binary '',_binary '',_binary '','1','2023-01-28 10:25:18','1','2024-04-22 23:56:12',_binary '\0'),(2145,'模板管理','',2,0,2144,'notify-template','fa:archive','system/notify/template/index','SystemNotifyTemplate',0,_binary '',_binary '',_binary '','','2023-01-28 02:26:42','1','2024-02-29 08:49:14',_binary '\0'),(2146,'站内信模板查询','system:notify-template:query',3,1,2145,'','','',NULL,0,_binary '',_binary '',_binary '','','2023-01-28 02:26:42','','2023-01-28 02:26:42',_binary '\0'),(2147,'站内信模板创建','system:notify-template:create',3,2,2145,'','','',NULL,0,_binary '',_binary '',_binary '','','2023-01-28 02:26:42','','2023-01-28 02:26:42',_binary '\0'),(2148,'站内信模板更新','system:notify-template:update',3,3,2145,'','','',NULL,0,_binary '',_binary '',_binary '','','2023-01-28 02:26:42','','2023-01-28 02:26:42',_binary '\0'),(2149,'站内信模板删除','system:notify-template:delete',3,4,2145,'','','',NULL,0,_binary '',_binary '',_binary '','','2023-01-28 02:26:42','','2023-01-28 02:26:42',_binary '\0'),(2150,'发送测试站内信','system:notify-template:send-notify',3,5,2145,'','','',NULL,0,_binary '',_binary '',_binary '','1','2023-01-28 10:54:43','1','2023-01-28 10:54:43',_binary '\0'),(2151,'消息记录','',2,0,2144,'notify-message','fa:edit','system/notify/message/index','SystemNotifyMessage',0,_binary '',_binary '',_binary '','','2023-01-28 04:28:22','1','2024-02-29 08:49:22',_binary '\0'),(2152,'站内信消息查询','system:notify-message:query',3,1,2151,'','','',NULL,0,_binary '',_binary '',_binary '','','2023-01-28 04:28:22','','2023-01-28 04:28:22',_binary '\0'),(2739,'消息中心','',1,7,1,'messages','ep:chat-dot-round','','',0,_binary '',_binary '',_binary '','1','2024-04-22 23:54:30','1','2024-04-23 09:36:35',_binary '\0');
INSERT INTO `system_menu` (`id`,`name`,`permission`,`type`,`sort`,`parent_id`,`path`,`icon`,`component`,`component_name`,`status`,`visible`,`keep_alive`,`always_show`,`creator`,`updater`) VALUES
(3010,'用户会话','system:session:page',2,8,1,'session','ep:connection','system/session/index','SystemSession',0,_binary '',_binary '',_binary '','1','1'),
(3011,'会话查询','system:session:page',3,1,3010,'','','',NULL,0,_binary '',_binary '',_binary '','1','1'),
(3012,'会话撤销','system:session:revoke',3,2,3010,'','','',NULL,0,_binary '',_binary '',_binary '','1','1'),
(102,'菜单管理','system:menu:query',2,3,1,'menu','ep:menu','system/menu/index','SystemMenu',0,_binary '',_binary '',_binary '','1','1'),
(3013,'菜单查询','system:menu:query',3,1,102,'','',NULL,NULL,0,_binary '',_binary '',_binary '','1','1'),
(3014,'菜单新增','system:menu:create',3,2,102,'','',NULL,NULL,0,_binary '',_binary '',_binary '','1','1'),
(3015,'菜单修改','system:menu:update',3,3,102,'','',NULL,NULL,0,_binary '',_binary '',_binary '','1','1'),
(3016,'菜单删除','system:menu:delete',3,4,102,'','',NULL,NULL,0,_binary '',_binary '',_binary '','1','1');
/*!40000 ALTER TABLE `system_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_notice`
--

DROP TABLE IF EXISTS `system_notice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_notice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '公告标题',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '公告内容',
  `type` tinyint NOT NULL COMMENT '公告类型（1通知 2公告）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='通知公告表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_notice`
--

LOCK TABLES `system_notice` WRITE;
/*!40000 ALTER TABLE `system_notice` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_notice` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_notify_message`
--

DROP TABLE IF EXISTS `system_notify_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_notify_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `user_type` tinyint NOT NULL COMMENT '用户类型',
  `template_id` bigint NOT NULL COMMENT '模版编号',
  `template_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板编码',
  `template_nickname` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版发送人名称',
  `template_content` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版内容',
  `template_type` int NOT NULL COMMENT '模版类型',
  `template_params` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版参数',
  `read_status` bit(1) NOT NULL COMMENT '是否已读',
  `read_time` datetime DEFAULT NULL COMMENT '阅读时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_id` (`user_id`),
  KEY `idx_read_status_create_time` (`read_status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='站内信消息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_notify_message`
--

LOCK TABLES `system_notify_message` WRITE;
/*!40000 ALTER TABLE `system_notify_message` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_notify_message` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_notify_template`
--

DROP TABLE IF EXISTS `system_notify_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_notify_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板名称',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版编码',
  `nickname` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发送人名称',
  `content` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模版内容',
  `type` tinyint NOT NULL COMMENT '类型',
  `params` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '参数数组',
  `status` tinyint NOT NULL COMMENT '状态',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='站内信模板表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_notify_template`
--

LOCK TABLES `system_notify_template` WRITE;
/*!40000 ALTER TABLE `system_notify_template` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_notify_template` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_user_session`
--

DROP TABLE IF EXISTS `system_user_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_user_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话编号',
  `user_id` bigint NOT NULL COMMENT '用户编号',
  `user_type` tinyint NOT NULL COMMENT '用户类型',
  `user_info` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户信息',
  `access_token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '访问令牌 SHA-256 摘要',
  `refresh_token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '刷新令牌 SHA-256 摘要',
  `refresh_family_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '刷新会话稳定撤销凭据 SHA-256 摘要',
  `access_expires_time` datetime NOT NULL COMMENT '访问令牌过期时间',
  `refresh_expires_time` datetime NOT NULL COMMENT '刷新令牌绝对过期时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_session_access_token_hash` (`access_token_hash`),
  UNIQUE KEY `uk_user_session_refresh_token_hash` (`refresh_token_hash`),
  UNIQUE KEY `uk_user_session_refresh_family_hash` (`refresh_family_hash`),
  KEY `idx_user_session_user` (`user_id`,`user_type`),
  KEY `idx_user_session_refresh_expires_time` (`refresh_expires_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户会话';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_user_session`
--

LOCK TABLES `system_user_session` WRITE;
/*!40000 ALTER TABLE `system_user_session` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_user_session` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_operate_log`
--

DROP TABLE IF EXISTS `system_operate_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_operate_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '链路追踪编号',
  `user_id` bigint DEFAULT NULL COMMENT '用户编号；系统任务或匿名强校验流程为空',
  `user_type` tinyint NOT NULL DEFAULT '0' COMMENT '用户类型',
  `type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作模块类型',
  `sub_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作名',
  `biz_id` bigint NOT NULL COMMENT '操作数据模块编号',
  `action` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '操作内容',
  `success` bit(1) NOT NULL DEFAULT b'1' COMMENT '操作结果',
  `extra` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '拓展字段',
  `request_method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '请求方法名',
  `request_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '请求地址',
  `user_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户 IP',
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '浏览器 UA',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_id` (`user_id`),
  KEY `idx_type` (`type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='操作日志记录 V2 版本';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_operate_log`
--

LOCK TABLES `system_operate_log` WRITE;
/*!40000 ALTER TABLE `system_operate_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_operate_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_post`
--

DROP TABLE IF EXISTS `system_post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_post` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '岗位编码',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '岗位名称',
  `sort` int NOT NULL COMMENT '显示顺序',
  `status` tinyint NOT NULL COMMENT '状态（0正常 1停用）',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='岗位信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_post`
--

LOCK TABLES `system_post` WRITE;
/*!40000 ALTER TABLE `system_post` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_post` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_role`
--

DROP TABLE IF EXISTS `system_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色名称',
  `code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色权限字符串',
  `sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` tinyint NOT NULL DEFAULT '5' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限 5：仅本人数据权限）',
  `data_scope_dept_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '[]' COMMENT '数据范围(指定部门数组)',
  `status` tinyint NOT NULL COMMENT '角色状态（0正常 1停用）',
  `type` tinyint NOT NULL COMMENT '角色类型',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_status` (`status`),
  CONSTRAINT `chk_role_data_scope_dept_ids_json` CHECK ((json_valid(`data_scope_dept_ids`) and (json_type(`data_scope_dept_ids`) = _utf8mb4'ARRAY')))
) ENGINE=InnoDB AUTO_INCREMENT=159 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='角色信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_role`
--

LOCK TABLES `system_role` WRITE;
/*!40000 ALTER TABLE `system_role` DISABLE KEYS */;
INSERT INTO `system_role` VALUES (1,'超级管理员','super_admin',1,1,'[]',0,1,'超级管理员','admin','2021-01-05 17:03:48','','2022-02-22 05:08:21',_binary '\0');
/*!40000 ALTER TABLE `system_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_role_menu`
--

DROP TABLE IF EXISTS `system_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_role_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增编号',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_role_id_menu_id` (`role_id`,`menu_id`),
  KEY `idx_role_id` (`role_id`),
  KEY `idx_menu_id` (`menu_id`),
  CONSTRAINT `fk_role_menu_menu` FOREIGN KEY (`menu_id`) REFERENCES `system_menu` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_role_menu_role` FOREIGN KEY (`role_id`) REFERENCES `system_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=6399 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='角色和菜单关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_role_menu`
--

LOCK TABLES `system_role_menu` WRITE;
/*!40000 ALTER TABLE `system_role_menu` DISABLE KEYS */;
INSERT INTO `system_role_menu` VALUES (6293,1,1,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6294,1,2,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6295,1,100,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6296,1,101,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6297,1,103,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6298,1,104,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6299,1,105,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6300,1,106,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6301,1,107,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6302,1,108,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6303,1,110,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6304,1,115,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6305,1,500,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6306,1,501,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6307,1,1090,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6308,1,1093,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6309,1,1094,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6310,1,1100,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6311,1,1237,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6312,1,1243,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6313,1,2144,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6314,1,2145,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6315,1,2151,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6316,1,2739,'1','2026-04-07 08:29:41','1','2026-04-07 08:29:41'),(6317,1,1001,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6318,1,1002,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6319,1,1003,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6320,1,1004,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6321,1,1005,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6322,1,1006,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6323,1,1007,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6324,1,1008,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6325,1,1009,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6326,1,1010,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6327,1,1011,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6328,1,1012,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6329,1,1017,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6330,1,1018,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6331,1,1019,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6332,1,1020,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6333,1,1021,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6334,1,1022,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6335,1,1023,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6336,1,1024,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6337,1,1025,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6338,1,1026,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6339,1,1027,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6340,1,1028,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6341,1,1029,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6342,1,1030,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6343,1,1031,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6344,1,1032,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6345,1,1033,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6346,1,1034,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6347,1,1035,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6348,1,1036,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6349,1,1037,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6350,1,1038,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6351,1,1039,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6352,1,1040,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6353,1,1042,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6354,1,1043,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6355,1,1045,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6356,1,1050,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6357,1,1051,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6358,1,1052,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6359,1,1053,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6360,1,1054,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6361,1,1056,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6362,1,1057,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6363,1,1058,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6364,1,1059,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6365,1,1060,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6366,1,1063,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6367,1,1064,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6368,1,1065,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6369,1,1075,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6370,1,1087,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6371,1,1091,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6372,1,1092,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6373,1,1095,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6374,1,1096,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6375,1,1097,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6376,1,1098,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6377,1,1101,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6378,1,1102,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6379,1,1103,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6380,1,1104,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6381,1,1105,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6382,1,1106,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6383,1,1238,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6384,1,1239,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6385,1,1240,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6386,1,1241,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6387,1,1242,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6388,1,2146,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6389,1,2147,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6390,1,2148,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6391,1,2149,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6392,1,2150,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6393,1,2152,'1','2026-04-07 08:30:13','1','2026-04-07 08:30:13'),(6394,1,3000,'1','2026-08-22 12:00:00','1','2026-08-22 12:00:00'),(6395,1,3001,'1','2026-08-22 12:00:00','1','2026-08-22 12:00:00'),(6396,1,3002,'1','2026-08-22 12:00:00','1','2026-08-22 12:00:00'),(6397,1,3003,'1','2026-08-22 12:00:00','1','2026-08-22 12:00:00'),(6398,1,3004,'1','2026-08-22 12:00:00','1','2026-08-22 12:00:00'),
(6399,1,102,'1','2026-09-05 00:00:00','1','2026-09-05 00:00:00'),
(6400,1,3013,'1','2026-09-05 00:00:00','1','2026-09-05 00:00:00'),
(6401,1,3014,'1','2026-09-05 00:00:00','1','2026-09-05 00:00:00'),
(6402,1,3015,'1','2026-09-05 00:00:00','1','2026-09-05 00:00:00'),
(6403,1,3016,'1','2026-09-05 00:00:00','1','2026-09-05 00:00:00');
INSERT INTO `system_role_menu` (`role_id`,`menu_id`,`creator`,`updater`) VALUES
(1,3010,'1','1'),(1,3011,'1','1'),(1,3012,'1','1');
/*!40000 ALTER TABLE `system_role_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_sms_channel`
--

DROP TABLE IF EXISTS `system_sms_channel`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_sms_channel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `signature` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信签名',
  `code` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '渠道编码',
  `status` tinyint NOT NULL COMMENT '开启状态',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `api_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信 API 账号的版本化 AES-GCM 密文',
  `api_secret` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '短信 API 密钥的版本化 AES-GCM 密文',
  `callback_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '短信发送回调 URL',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='短信渠道';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_sms_channel`
--

LOCK TABLES `system_sms_channel` WRITE;
/*!40000 ALTER TABLE `system_sms_channel` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_sms_channel` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_sms_code`
--

DROP TABLE IF EXISTS `system_sms_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_sms_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `mobile` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '手机号',
  `code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '验证码',
  `create_ip` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '创建 IP',
  `scene` tinyint NOT NULL COMMENT '发送场景',
  `today_index` tinyint NOT NULL COMMENT '今日发送的第几条',
  `used` bit(1) NOT NULL COMMENT '是否使用',
  `used_time` datetime DEFAULT NULL COMMENT '使用时间',
  `used_ip` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用 IP',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_mobile` (`mobile`) USING BTREE COMMENT '手机号',
  KEY `idx_mobile_scene` (`mobile`,`scene`)
) ENGINE=InnoDB AUTO_INCREMENT=682 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='手机验证码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_sms_code`
--

LOCK TABLES `system_sms_code` WRITE;
/*!40000 ALTER TABLE `system_sms_code` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_sms_code` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_sms_log`
--

DROP TABLE IF EXISTS `system_sms_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_sms_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `channel_id` bigint NOT NULL COMMENT '短信渠道编号',
  `channel_code` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信渠道编码',
  `template_id` bigint NOT NULL COMMENT '模板编号',
  `template_code` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板编码',
  `template_type` tinyint NOT NULL COMMENT '短信类型',
  `template_content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信内容',
  `template_params` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信参数',
  `api_template_id` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信 API 的模板编号',
  `mobile` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '手机号',
  `user_id` bigint DEFAULT NULL COMMENT '用户编号',
  `user_type` tinyint DEFAULT NULL COMMENT '用户类型',
  `send_status` tinyint NOT NULL DEFAULT '0' COMMENT '发送状态',
  `send_time` datetime DEFAULT NULL COMMENT '发送时间',
  `api_send_code` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '短信 API 发送结果的编码',
  `api_send_msg` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '短信 API 发送失败的提示',
  `api_request_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '短信 API 发送返回的唯一请求 ID',
  `api_serial_no` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '短信 API 发送返回的序号',
  `receive_status` tinyint NOT NULL DEFAULT '0' COMMENT '接收状态',
  `receive_time` datetime DEFAULT NULL COMMENT '接收时间',
  `api_receive_code` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API 接收结果的编码',
  `api_receive_msg` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API 接收结果的说明',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_channel_api_serial_no` (`channel_code`,`api_serial_no`),
  KEY `idx_mobile` (`mobile`),
  KEY `idx_send_time` (`send_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1528 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='短信日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_sms_log`
--

LOCK TABLES `system_sms_log` WRITE;
/*!40000 ALTER TABLE `system_sms_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_sms_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_sms_template`
--

DROP TABLE IF EXISTS `system_sms_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_sms_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `type` tinyint NOT NULL COMMENT '模板类型',
  `status` tinyint NOT NULL COMMENT '开启状态',
  `code` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板编码',
  `name` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板名称',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模板内容',
  `params` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数数组',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `api_template_id` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信 API 的模板编号',
  `channel_id` bigint NOT NULL COMMENT '短信渠道编号',
  `channel_code` varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短信渠道编码',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_channel_id` (`channel_id`),
  CONSTRAINT `fk_sms_template_channel` FOREIGN KEY (`channel_id`) REFERENCES `system_sms_channel` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='短信模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_sms_template`
--

LOCK TABLES `system_sms_template` WRITE;
/*!40000 ALTER TABLE `system_sms_template` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_sms_template` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_user_post`
--

DROP TABLE IF EXISTS `system_user_post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_user_post` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户ID',
  `post_id` bigint NOT NULL DEFAULT '0' COMMENT '岗位ID',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id_post_id` (`user_id`,`post_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_post_id` (`post_id`),
  CONSTRAINT `fk_user_post_post` FOREIGN KEY (`post_id`) REFERENCES `system_post` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_post_user` FOREIGN KEY (`user_id`) REFERENCES `system_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=128 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户岗位表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_user_post`
--

LOCK TABLES `system_user_post` WRITE;
/*!40000 ALTER TABLE `system_user_post` DISABLE KEYS */;
/*!40000 ALTER TABLE `system_user_post` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_user_role`
--

DROP TABLE IF EXISTS `system_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增编号',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id_role_id` (`user_id`,`role_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_role_id` (`role_id`),
  CONSTRAINT `fk_user_role_role` FOREIGN KEY (`role_id`) REFERENCES `system_role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_role_user` FOREIGN KEY (`user_id`) REFERENCES `system_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=51 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户和角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_user_role`
--

LOCK TABLES `system_user_role` WRITE;
/*!40000 ALTER TABLE `system_user_role` DISABLE KEYS */;
INSERT INTO `system_user_role` VALUES (1,1,1,'','2022-01-11 13:19:45','','2022-05-12 12:35:17');
/*!40000 ALTER TABLE `system_user_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_users`
--

DROP TABLE IF EXISTS `system_users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_users` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户账号',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '密码',
  `nickname` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户昵称',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `post_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '岗位编号数组',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户邮箱',
  `mobile` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号码',
  `sex` tinyint DEFAULT '0' COMMENT '用户性别',
  `avatar` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '头像地址',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '帐号状态（0正常 1停用）',
  `login_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime DEFAULT NULL COMMENT '最后登录时间',
  `must_change_password` bit(1) NOT NULL DEFAULT b'0' COMMENT '下次登录前必须修改密码',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_system_users_mobile_active` ((CASE WHEN `deleted` = b'0' THEN NULLIF(TRIM(`mobile`), '') ELSE NULL END)),
  UNIQUE KEY `uk_system_users_email_active` ((CASE WHEN `deleted` = b'0' THEN NULLIF(TRIM(`email`), '') ELSE NULL END)),
  KEY `idx_mobile` (`mobile`),
  KEY `idx_email` (`email`),
  KEY `idx_dept_id` (`dept_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=143 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_users`
--
-- 种子管理员密码为不可登录的封存标记，must_change_password 置 1。
-- 种子账号默认禁用；通过部署独立口令激活，见 docs/deployment.md。

LOCK TABLES `system_users` WRITE;
/*!40000 ALTER TABLE `system_users` DISABLE KEYS */;
INSERT INTO `system_users` VALUES (1,'admin','!bootstrap-required','总部门','管理员',100,'[]',NULL,NULL,2,'',1,'',NULL,b'1','admin','2021-01-05 17:03:47',NULL,'2026-08-23 14:13:52',_binary '\0');
/*!40000 ALTER TABLE `system_users` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;


--
-- AI 模型端点（V48）
--

DROP TABLE IF EXISTS `ai_model_endpoint_revision`;
DROP TABLE IF EXISTS `ai_model_endpoint`;

CREATE TABLE `ai_model_endpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '端点编号',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '端点名称',
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提供方标识（如 openai_compatible）',
  `base_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '基础地址（https）',
  `config_revision` int NOT NULL DEFAULT '1' COMMENT '当前非秘密配置版本',
  `credential_revision` int NOT NULL DEFAULT '0' COMMENT '凭据版本（0 表示未配置；轮换只递增该值）',
  `credential_ciphertext` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '凭据密文（AES-GCM，含版本前缀）',
  `enabled` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否启用',
  `referenced` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否被发布服务引用（引用后 provider/base_url 不可原地修改）',
  `embedding_dimension` int DEFAULT NULL COMMENT '已记录的嵌入维度（首次成功嵌入时写入；改变即拒绝写入既有索引）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_model_endpoint_name` (`name`,`deleted`),
  KEY `idx_ai_model_endpoint_provider` (`provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型端点';

CREATE TABLE `ai_model_endpoint_revision` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '版本编号',
  `endpoint_id` bigint NOT NULL COMMENT '端点编号',
  `revision` int NOT NULL COMMENT '非秘密配置版本（从 1 递增，写入后不可变）',
  `model_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型标识',
  `capabilities` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能力集合（逗号分隔：TEXT,EMBEDDING）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_endpoint_revision` (`endpoint_id`,`revision`),
  CONSTRAINT `fk_ai_model_endpoint_revision` FOREIGN KEY (`endpoint_id`) REFERENCES `ai_model_endpoint` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型端点配置版本（不可变）';

--
-- AI 模型能力探测结果（V49）
--

DROP TABLE IF EXISTS `ai_model_probe`;

CREATE TABLE `ai_model_probe` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '探测记录编号',
  `endpoint_id` bigint NOT NULL COMMENT '端点编号（逻辑引用，不建物理外键）',
  `config_revision` int NOT NULL COMMENT '探测时的配置版本',
  `credential_revision` int NOT NULL COMMENT '探测时的凭据版本（只记录版本号，不含凭据）',
  `probe_kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '探测类型（CONNECTIVITY/TEXT/TEXT_STREAM/STRUCTURED_OUTPUT/TOOL_CALLING/EMBEDDING）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结论状态（SUPPORTED/UNSUPPORTED/FAILED）',
  `detail_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '稳定明细码：失败原因名或不支持原因；成功为空',
  `embedding_dimension` int DEFAULT NULL COMMENT '嵌入探测观测到的向量维度',
  `latency_ms` int NOT NULL DEFAULT '0' COMMENT '真实调用耗时（毫秒）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_model_probe_endpoint_kind` (`endpoint_id`,`probe_kind`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型能力探测结果';

--
-- AI 应用与客户端凭据（V50）
--

DROP TABLE IF EXISTS `ai_application_credential`;
DROP TABLE IF EXISTS `ai_application`;

CREATE TABLE `ai_application` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '应用编号',
  `app_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '应用标识（全局唯一，创建后不可修改）',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '应用名称',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '应用说明',
  `origins` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '精确 Origin 列表（JSON 数组文本，已归一化）',
  `enabled` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否启用',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_application_app_code` (`app_code`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 应用';

CREATE TABLE `ai_application_credential` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '凭据编号',
  `application_id` bigint NOT NULL COMMENT '应用编号',
  `secret_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户端秘密的 SHA-256 摘要（十六进制），不保存明文',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（ACTIVE/REVOKED）',
  `revoked_time` datetime DEFAULT NULL COMMENT '吊销时间（吊销立即生效）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_application_credential_digest` (`secret_digest`,`deleted`),
  KEY `idx_ai_application_credential_app` (`application_id`,`status`),
  CONSTRAINT `fk_ai_application_credential` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 应用客户端凭据（只存摘要）';

--
-- AI 资源授权目录（V52）
--

DROP TABLE IF EXISTS `ai_resource_grant`;

CREATE TABLE `ai_resource_grant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '授权编号',
  `application_id` bigint NOT NULL COMMENT '应用编号',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（APP/USER）',
  `external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '外部用户标识（APP 主体为空串）',
  `resource_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET）',
  `resource_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资源标识（同类型内唯一；不同类型同 ID 不串权）',
  `actions` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '动作白名单（逗号分隔：READ,EXECUTE,EXPORT）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（ACTIVE/REVOKED）',
  `authz_revision` bigint NOT NULL DEFAULT '1' COMMENT '授权版本：授权或撤销时递增',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_resource_grant` (`application_id`,`subject_type`,`external_user_id`,`resource_type`,`resource_key`,`deleted`),
  KEY `idx_ai_resource_grant_lookup` (`application_id`,`subject_type`,`external_user_id`,`resource_type`),
  CONSTRAINT `fk_ai_resource_grant_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 资源授权目录';

-- AI 资源授权菜单与权限点（V52）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(4020, '资源授权', 'ai:grant:query', 2, 3, 4000, 'grant', 'ep:lock', 'ai/authorization/index', 'AiAuthorization', 0, b'1', b'1', b'1', '1', '2026-09-17 22:00:00', '1', '2026-09-17 22:00:00', b'0'),
(4021, '授权新增', 'ai:grant:create', 3, 1, 4020, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 22:00:00', '1', '2026-09-17 22:00:00', b'0'),
(4022, '授权修改', 'ai:grant:update', 3, 2, 4020, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 22:00:00', '1', '2026-09-17 22:00:00', b'0'),
(4023, '授权撤销', 'ai:grant:revoke', 3, 3, 4020, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 22:00:00', '1', '2026-09-17 22:00:00', b'0');


--
-- AI 访问票据（V53）
--

DROP TABLE IF EXISTS `ai_access_ticket`;

CREATE TABLE `ai_access_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '票据编号',
  `application_id` bigint NOT NULL COMMENT '应用编号',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（APP/USER）',
  `external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '外部用户标识（APP 主体为空串）',
  `token_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '票据 token 的 SHA-256 摘要（十六进制），不保存明文',
  `scope_snapshot` varchar(4096) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '裁剪后的范围快照（JSON：组织与对象白名单 + 来源 + 版本）',
  `scope_fingerprint` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '范围指纹（与 A03 判定一致；范围收窄后变化）',
  `authz_revision` bigint NOT NULL DEFAULT '1' COMMENT '签发时的授权版本',
  `expires_time` datetime NOT NULL COMMENT '到期时间（短期票据）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（ACTIVE/REVOKED）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_access_ticket_digest` (`token_digest`,`deleted`),
  KEY `idx_ai_access_ticket_subject` (`application_id`,`subject_type`,`external_user_id`),
  CONSTRAINT `fk_ai_access_ticket_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 访问票据（只存摘要）';

--
-- AI 业务文件绑定（V54）
--

DROP TABLE IF EXISTS `ai_file_binding`;

CREATE TABLE `ai_file_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '绑定编号',
  `file_id` bigint NOT NULL COMMENT 'infra 文件编号（逻辑引用，不建物理外键）',
  `business_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AI 业务类型（ai_report/ai_knowledge_document/ai_chat_session）',
  `business_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务对象标识（报表键/知识库键/会话键）',
  `application_id` bigint NOT NULL COMMENT '上传主体所属应用（跨应用隔离）',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '上传主体类型（APP/USER）',
  `external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '上传主体外部用户标识（所有者）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（ACTIVE/RELEASED）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_file_binding` (`file_id`,`business_type`,`business_key`,`deleted`),
  KEY `idx_ai_file_binding_business` (`business_type`,`business_key`,`status`),
  KEY `idx_ai_file_binding_file` (`file_id`,`status`),
  KEY `idx_ai_file_binding_subject` (`application_id`,`subject_type`,`external_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 业务文件绑定（业务 ACL）';

--
-- AI 服务草稿与资源绑定（V56）
--

DROP TABLE IF EXISTS `ai_service_resource`;
DROP TABLE IF EXISTS `ai_service_release`;
DROP TABLE IF EXISTS `ai_service`;

CREATE TABLE `ai_service` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '服务编号',
  `app_id` bigint NOT NULL COMMENT '所属应用编号（服务归属与绑定越权校验的基准）',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务标识（应用内唯一）',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名称',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '服务说明',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（DRAFT/READY/ARCHIVED）',
  `model_endpoint_id` bigint NOT NULL COMMENT '模型端点编号',
  `prompt_template` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提示词模板',
  `input_schema` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '输入 JSON Schema（平台校验后注入）',
  `output_schema` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '输出 JSON Schema（结构化输出时必填）',
  `required_capabilities` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所需能力（逗号分隔：TEXT,STRUCTURED_OUTPUT）',
  `run_subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '运行主体类型（APP/USER）',
  `eval_threshold` int NOT NULL DEFAULT '0' COMMENT '发布要求的评测得分门槛（0-100）',
  `draft_revision` int NOT NULL DEFAULT '1' COMMENT '草稿修订号：配置变更递增',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_service_code` (`app_id`,`code`,`deleted`),
  KEY `idx_ai_service_endpoint` (`model_endpoint_id`),
  CONSTRAINT `fk_ai_service_app` FOREIGN KEY (`app_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 服务（草稿）';

CREATE TABLE `ai_service_release` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '发布版本编号',
  `service_id` bigint NOT NULL COMMENT '服务编号',
  `release_version` int NOT NULL COMMENT '发布版本号（从 1 递增，写入后不可变）',
  `model_endpoint_id` bigint NOT NULL COMMENT '发布时固定的模型端点编号',
  `endpoint_config_revision` int NOT NULL COMMENT '发布时固定的端点配置版本',
  `prompt_template` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发布时固定的提示词模板',
  `input_schema` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发布时固定的输入 Schema',
  `output_schema` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '发布时固定的输出 Schema',
  `required_capabilities` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发布时固定的能力集合',
  `eval_threshold` int NOT NULL DEFAULT '0' COMMENT '发布时冻结的评测得分门槛（0-100）',
  `content_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发布内容摘要（评测与回退的稳定标识）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（CANDIDATE/ACTIVE/RETIRED）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_service_release_version` (`service_id`,`release_version`,`deleted`),
  CONSTRAINT `fk_ai_service_release_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 服务发布版本（不可变，S02）';

CREATE TABLE `ai_service_resource` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '绑定编号',
  `service_id` bigint NOT NULL COMMENT '服务编号',
  `release_id` bigint DEFAULT NULL COMMENT '发布版本编号（空表示草稿绑定）',
  `resource_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET）',
  `resource_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资源标识',
  `actions` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '需要的动作（逗号分隔：READ,EXECUTE,EXPORT）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（ACTIVE/RELEASED）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_service_resource` (`service_id`,`resource_type`,`resource_key`,`release_id`,`deleted`),
  KEY `idx_ai_service_resource_draft` (`service_id`,`release_id`,`status`),
  CONSTRAINT `fk_ai_service_resource_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 服务资源绑定（草稿/发布版本）';

--
-- AI 服务发布评测（V57）
--

DROP TABLE IF EXISTS `ai_service_release_evaluation`;

CREATE TABLE `ai_service_release_evaluation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '评测记录编号',
  `service_id` bigint NOT NULL COMMENT '服务编号（冗余自发布版本，便于按服务追溯）',
  `release_id` bigint NOT NULL COMMENT '发布版本编号',
  `content_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '被评测内容摘要：与发布版本内容摘要一致才是有效证据',
  `model_endpoint_id` bigint NOT NULL COMMENT '评测所用的模型端点编号',
  `endpoint_config_revision` int NOT NULL COMMENT '评测所用的端点配置版本',
  `score` int NOT NULL COMMENT '评测得分（0-100）',
  `threshold` int NOT NULL COMMENT '评测时冻结的门槛（得分 >= 门槛 才判定通过）',
  `passed` bit(1) NOT NULL COMMENT '是否通过（平台按门槛判定，调用方不能直接提交结论）',
  `case_count` int NOT NULL COMMENT '评测用例数（至少 1）',
  `notes` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '备注（不得写入提示词、响应正文或凭据）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_service_eval_release` (`release_id`,`id`),
  KEY `idx_ai_service_eval_service` (`service_id`,`id`),
  CONSTRAINT `fk_ai_service_eval_release` FOREIGN KEY (`release_id`) REFERENCES `ai_service_release` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_service_eval_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 服务发布评测（绑定内容摘要与端点配置版本，S02）';

--
-- AI 会话与消息（V59）
--

DROP TABLE IF EXISTS `ai_conversation`;

CREATE TABLE `ai_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话编号',
  `application_id` bigint NOT NULL COMMENT '应用编号',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（APP/USER）',
  `external_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '可信外部用户标识（APP 主体为空串）',
  `conversation_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话业务键（conv_ 前缀，应用+主体内唯一）',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '会话标题',
  `service_id` bigint DEFAULT NULL COMMENT '绑定的 AI 服务编号',
  `release_id` bigint DEFAULT NULL COMMENT '固定的发布版本编号（首个 run 解析后写入）',
  `business_context` varchar(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '{}' COMMENT '业务上下文（已注册字段的 JSON 对象文本）',
  `message_count` int NOT NULL DEFAULT '0' COMMENT '消息条数（逻辑删除后递减）',
  `last_message_time` datetime DEFAULT NULL COMMENT '最后一条消息时间',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE/DELETED）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_conversation_key` (`application_id`,`subject_type`,`external_user_id`,`conversation_key`,`deleted`),
  KEY `idx_ai_conversation_subject` (`application_id`,`subject_type`,`external_user_id`,`id`),
  KEY `idx_ai_conversation_service` (`service_id`,`id`),
  KEY `idx_ai_conversation_status_updated` (`status`,`update_time`),
  CONSTRAINT `fk_ai_conversation_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_conversation_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_conversation_release` FOREIGN KEY (`release_id`) REFERENCES `ai_service_release` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 会话（归属由服务端身份决定，绑定服务与发布版本，O01）';

DROP TABLE IF EXISTS `ai_conversation_message`;

CREATE TABLE `ai_conversation_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息编号',
  `conversation_id` bigint NOT NULL COMMENT '会话编号',
  `application_id` bigint NOT NULL COMMENT '应用编号（冗余自会话，用于主体过滤）',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（冗余自会话，用于主体过滤）',
  `external_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '外部用户标识（冗余自会话，用于主体过滤）',
  `sequence_no` int NOT NULL COMMENT '会话内序号（从 1 递增，分页稳定）',
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色（user/assistant/system）',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息正文（受控业务数据，不进日志）',
  `content_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '正文 SHA-256（审计与去重；摘要不等于正文）',
  `source_run_id` bigint DEFAULT NULL COMMENT '产生该消息的运行编号（O02 起写入）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE/DELETED）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_conversation_message_seq` (`conversation_id`,`sequence_no`,`deleted`),
  KEY `idx_ai_conversation_message_subject` (`application_id`,`subject_type`,`external_user_id`,`conversation_id`,`id`),
  CONSTRAINT `fk_ai_conversation_message_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversation` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 会话消息（受控业务数据，按主体过滤，O01）';

--
-- AI 运行、幂等与首任务（V60）
--

DROP TABLE IF EXISTS `ai_run`;

CREATE TABLE `ai_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '运行编号',
  `run_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '运行业务键（run_ 前缀，主体内唯一）',
  `application_id` bigint NOT NULL COMMENT '应用编号',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（APP/USER）',
  `external_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '可信外部用户标识',
  `conversation_id` bigint DEFAULT NULL COMMENT '会话编号',
  `service_id` bigint NOT NULL COMMENT '服务编号',
  `release_id` bigint NOT NULL COMMENT '受理时固定的发布版本编号',
  `model_endpoint_id` bigint NOT NULL COMMENT '受理时固定的模型端点编号',
  `endpoint_config_revision` int NOT NULL COMMENT '受理时固定的端点配置版本',
  `content_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '受理时固定的发布内容摘要',
  `input_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '请求摘要（幂等判定；不含 traceId 与 token）',
  `data_level` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'L2_INTERNAL' COMMENT '受理时声明的数据分级（L1_PUBLIC/L2_INTERNAL/L3_PERSONAL/L4_SECRET）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED）',
  `step_count` int NOT NULL DEFAULT '0' COMMENT '已执行步数（有界执行，O04）',
  `event_seq` int NOT NULL DEFAULT '0' COMMENT '已分配的事件序号（行锁内递增，O05）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_run_key` (`application_id`,`subject_type`,`external_user_id`,`run_key`,`deleted`),
  KEY `idx_ai_run_subject` (`application_id`,`subject_type`,`external_user_id`,`id`),
  KEY `idx_ai_run_conversation` (`conversation_id`,`id`),
  KEY `idx_ai_run_release` (`release_id`,`id`),
  KEY `idx_ai_run_status_updated` (`status`,`update_time`),
  CONSTRAINT `fk_ai_run_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_run_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_run_release` FOREIGN KEY (`release_id`) REFERENCES `ai_service_release` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_run_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversation` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 运行（受理即固定发布版本，O02）';

DROP TABLE IF EXISTS `ai_run_idempotency`;

CREATE TABLE `ai_run_idempotency` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '幂等记录编号',
  `application_id` bigint NOT NULL COMMENT '应用编号',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型',
  `external_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '可信外部用户标识',
  `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '幂等键（调用方提供，主体内唯一）',
  `request_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '请求摘要（与 run.input_digest 一致才算同一请求）',
  `run_id` bigint NOT NULL COMMENT '首次受理产生的运行编号',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_run_idempotency_key` (`application_id`,`subject_type`,`external_user_id`,`idempotency_key`,`deleted`),
  KEY `idx_ai_run_idempotency_run` (`run_id`),
  CONSTRAINT `fk_ai_run_idempotency_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_run_idempotency_run` FOREIGN KEY (`run_id`) REFERENCES `ai_run` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 运行幂等记录（唯一键兜底并发，O02）';

DROP TABLE IF EXISTS `ai_run_task`;

CREATE TABLE `ai_run_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务编号',
  `run_id` bigint NOT NULL COMMENT '运行编号',
  `task_kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务类型（首任务为 RUN_STEP）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '已尝试次数',
  `next_attempt_time` datetime DEFAULT NULL COMMENT '下次可尝试时间（重试等待）',
  `payload_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务载荷摘要（正文不入队）',
  `lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租约持有者（worker 标识）',
  `lease_expires_time` datetime DEFAULT NULL COMMENT '租约到期时间',
  `heartbeat_time` datetime DEFAULT NULL COMMENT '最近一次心跳时间',
  `claimed_epoch` int NOT NULL DEFAULT '0' COMMENT '领取代次（续租与落库的栅栏）',
  `max_attempts` int NOT NULL DEFAULT '3' COMMENT '最大尝试次数（达到后置 FAILED，不再重试）',
  `last_error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次失败原因码（稳定词表）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_run_task_kind` (`run_id`,`task_kind`,`deleted`),
  KEY `idx_ai_run_task_status` (`status`,`next_attempt_time`,`id`),
  KEY `idx_ai_run_task_lease` (`status`,`lease_expires_time`),
  CONSTRAINT `fk_ai_run_task_run` FOREIGN KEY (`run_id`) REFERENCES `ai_run` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 运行任务（首任务与重试等待；租约列由 O03 补齐，O02）';

--
-- AI 运行事件（V63）
--

DROP TABLE IF EXISTS `ai_run_event`;

CREATE TABLE `ai_run_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '事件编号',
  `run_id` bigint NOT NULL COMMENT '运行编号',
  `seq` int NOT NULL COMMENT '运行内事件序号（从 1 递增，由运行行分配）',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件状态（QUEUED/RUNNING/WAITING_INPUT/WAITING_CONFIRMATION/SUCCEEDED/FAILED/CANCELLED）',
  `block_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '结果块类型（受控结构；无块时为空）',
  `block_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '结果块正文（受控结构；不含提示词与模型输入正文）',
  `schema_version` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '1.0' COMMENT '事件契约版本（RunEvent v1）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_run_event_seq` (`run_id`,`seq`,`deleted`),
  KEY `idx_ai_run_event_created` (`create_time`,`id`),
  CONSTRAINT `fk_ai_run_event_run` FOREIGN KEY (`run_id`) REFERENCES `ai_run` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 运行事件（SSE 事件源，seq 由运行行分配，O05）';

-- AI 服务菜单与权限点（V56）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(4030, 'AI 服务', 'ai:service:query', 2, 4, 4000, 'service', 'ep:document', 'ai/service/index', 'AiService', 0, b'1', b'1', b'1', '1', '2026-09-19 10:00:00', '1', '2026-09-19 10:00:00', b'0'),
(4031, '服务新增', 'ai:service:create', 3, 1, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 10:00:00', '1', '2026-09-19 10:00:00', b'0'),
(4032, '服务修改', 'ai:service:update', 3, 2, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 10:00:00', '1', '2026-09-19 10:00:00', b'0'),
(4033, '服务删除', 'ai:service:delete', 3, 3, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 10:00:00', '1', '2026-09-19 10:00:00', b'0'),
(4034, '资源绑定', 'ai:service:bind', 3, 4, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 10:00:00', '1', '2026-09-19 10:00:00', b'0'),
(4035, '标记可发布', 'ai:service:publish', 3, 5, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 10:00:00', '1', '2026-09-19 10:00:00', b'0');

-- AI 服务发布、评测与调试菜单与权限点（V57-V58）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(4036, '创建发布候选', 'ai:service:release', 3, 6, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 16:00:00', '1', '2026-09-19 16:00:00', b'0'),
(4037, '切换发布版本', 'ai:service:activate', 3, 7, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 16:00:00', '1', '2026-09-19 16:00:00', b'0'),
(4038, '记录评测结果', 'ai:service:evaluate', 3, 8, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 16:00:00', '1', '2026-09-19 16:00:00', b'0'),
(4039, '服务调试', 'ai:service:debug', 3, 9, 4030, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-19 17:00:00', '1', '2026-09-19 17:00:00', b'0');

-- AI 开放平台菜单（V65）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(4040, 'AI 开放平台', 'ai:open-platform:query', 2, 5, 4000, 'open-platform', 'ep:link', 'ai/open-platform/index', 'AiOpenPlatform', 0, b'1', b'1', b'1', '1', '2026-09-20 09:00:00', '1', '2026-09-20 09:00:00', b'0');


-- AI 中台菜单与权限点（V48/V49/V50，与 AiModelEndpointController / AiModelCapabilityProbeController / AiApplicationController 的 @PreAuthorize 一一对应）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(4000, 'AI 中台', '', 1, 40, 0, '/ai', 'ep:cpu', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 11:00:00', '1', '2026-09-17 11:00:00', b'0'),
(4001, '模型端点', 'ai:model-endpoint:query', 2, 1, 4000, 'model-endpoint', 'ep:connection', 'ai/model-endpoint/index', 'AiModelEndpoint', 0, b'1', b'1', b'1', '1', '2026-09-17 11:00:00', '1', '2026-09-17 11:00:00', b'0'),
(4002, '端点新增', 'ai:model-endpoint:create', 3, 1, 4001, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 11:00:00', '1', '2026-09-17 11:00:00', b'0'),
(4003, '端点修改', 'ai:model-endpoint:update', 3, 2, 4001, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 11:00:00', '1', '2026-09-17 11:00:00', b'0'),
(4004, '端点删除', 'ai:model-endpoint:delete', 3, 3, 4001, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 11:00:00', '1', '2026-09-17 11:00:00', b'0'),
(4005, '端点能力探测', 'ai:model-endpoint:probe', 3, 4, 4001, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 18:00:00', '1', '2026-09-17 18:00:00', b'0');

--
-- AI 外部主体（V51）
--

DROP TABLE IF EXISTS `ai_subject`;

CREATE TABLE `ai_subject` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主体编号',
  `application_id` bigint NOT NULL COMMENT '所属应用编号',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（APP/USER）',
  `external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '可信外部用户标识（APP 主体为空串）',
  `display_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '外部主体显示名（仅用于管理端展示）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（ACTIVE/DISABLED）；业务侧撤销同步为 DISABLED',
  `scope_source` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '外部授权来源标识（业务系统/授权服务名）',
  `scope_version` bigint NOT NULL DEFAULT '1' COMMENT '当前范围版本；范围变化必须递增',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_subject_identity` (`application_id`,`subject_type`,`external_user_id`,`deleted`),
  KEY `idx_ai_subject_external_user` (`external_user_id`),
  CONSTRAINT `fk_ai_subject_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 外部主体（APP/USER）';

-- AI 应用菜单与权限点（V50）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`) VALUES
(4010, 'AI 应用', 'ai:application:query', 2, 2, 4000, 'application', 'ep:key', 'ai/application/index', 'AiApplication', 0, b'1', b'1', b'1', '1', '2026-09-17 21:00:00', '1', '2026-09-17 21:00:00', b'0'),
(4011, '应用新增', 'ai:application:create', 3, 1, 4010, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 21:00:00', '1', '2026-09-17 21:00:00', b'0'),
(4012, '应用修改', 'ai:application:update', 3, 2, 4010, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 21:00:00', '1', '2026-09-17 21:00:00', b'0'),
(4013, '应用删除', 'ai:application:delete', 3, 3, 4010, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 21:00:00', '1', '2026-09-17 21:00:00', b'0'),
(4014, '凭据轮换', 'ai:application:rotate', 3, 4, 4010, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 21:00:00', '1', '2026-09-17 21:00:00', b'0'),
(4015, '凭据吊销', 'ai:application:revoke', 3, 5, 4010, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '2026-09-17 21:00:00', '1', '2026-09-17 21:00:00', b'0');

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed
