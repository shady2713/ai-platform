package com.basicframework.module.ai.dal.mysql.action;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分析步数计数器（D09）：对 {@code ai_run.step_count} 的原子读写。
 *
 * <p>为什么用一条带状态条件的 UPDATE 而不是"先查再写"：取消与终态检查必须与计数在同一条语句里完成——
 * {@code WHERE status IN ('ACCEPTED','RUNNING')} 保证运行被取消/结束后计数不再增长，
 * 调度器据此拒绝执行后续步骤（AT-016），不存在"检查通过后运行刚被取消"的窗口。
 */
@Mapper
public interface AiRunStepCounterMapper {

    /**
     * 读取运行状态、已用步数与**数据库侧**已运行时长（毫秒）。
     *
     * <p>时长由数据库计算（{@code NOW() - create_time}）：容器与应用的时区/时钟可能不同，
     * 用 JVM 时钟去减数据库时间戳会得到荒谬的耗时（曾把 0 秒算成 8 小时）。
     */
    @Select("SELECT status AS status, step_count AS stepCount,"
            + " TIMESTAMPDIFF(MICROSECOND, create_time, NOW()) / 1000 AS elapsedMillis"
            + " FROM ai_run WHERE id = #{runId}")
    AiRunStepRow selectRun(@Param("runId") Long runId);

    /** 原子占用一步：仅当运行仍活跃时成功（返回 0 表示运行已取消/终态或不存在）。 */
    @Update("UPDATE ai_run SET step_count = step_count + 1, version = version + 1"
            + " WHERE id = #{runId} AND status IN ('ACCEPTED', 'RUNNING')")
    int incrementIfActive(@Param("runId") Long runId);

    /** 运行行投影（状态 + 已用步数 + 数据库侧已运行时长）。 */
    record AiRunStepRow(String status, Integer stepCount, Long elapsedMillis) {}
}
