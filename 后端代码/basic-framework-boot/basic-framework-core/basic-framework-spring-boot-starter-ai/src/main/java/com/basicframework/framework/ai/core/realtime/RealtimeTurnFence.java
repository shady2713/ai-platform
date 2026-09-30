package com.basicframework.framework.ai.core.realtime;

/**
 * 回合栅栏（X05 冻结）：打断后"旧回合的帧不得继续输出"的唯一判据。
 *
 * <p>为什么用回合序号而不是时间戳或标志位：时间戳依赖时钟、标志位依赖"谁先写"，两者在
 * "打断的瞬间已在飞的帧"上都会漏。回合序号是单调的：打断把当前回合 +1，之后任何携带旧回合号的
 * 上行帧或下行事件都被判定为过期并丢弃——与到达顺序无关。
 *
 * <p>判定三态：
 * <ul>
 *   <li>{@link Verdict#CURRENT}：回合号等于当前回合，正常处理；</li>
 *   <li>{@link Verdict#STALE}：回合号小于当前回合，**丢弃并计数**（晚到的旧音频/旧转写）；</li>
 *   <li>{@link Verdict#FUTURE}：回合号大于当前回合，客户端/上游不能凭空发明回合，按不合规拒绝。</li>
 * </ul>
 *
 * <p>非线程安全：会话命中的请求由平台按会话串行化（条件更新 + 版本号），本类只表达判定语义。
 */
public final class RealtimeTurnFence {

    /** 回合判定结论。 */
    public enum Verdict {
        /** 属于当前回合。 */
        CURRENT,
        /** 属于已打断的旧回合：丢弃并计数。 */
        STALE,
        /** 超出当前回合：不合规（拒绝，不推进栅栏）。 */
        FUTURE
    }

    private long currentTurn;

    private long droppedStaleFrames;

    private RealtimeTurnFence(long currentTurn, long droppedStaleFrames) {
        this.currentTurn = currentTurn;
        this.droppedStaleFrames = droppedStaleFrames;
    }

    /** 从给定回合与已丢弃计数装载（重连后从持久化事实恢复）。 */
    public static RealtimeTurnFence of(long currentTurn, long droppedStaleFrames) {
        if (currentTurn < 0) {
            throw new IllegalArgumentException("当前回合不能为负：" + currentTurn);
        }
        if (droppedStaleFrames < 0) {
            throw new IllegalArgumentException("已丢弃计数不能为负：" + droppedStaleFrames);
        }
        return new RealtimeTurnFence(currentTurn, droppedStaleFrames);
    }

    /** 判定一个入帧/事件的回合归属；STALE 时累计丢弃计数。 */
    public Verdict classify(long turnNo) {
        if (turnNo < currentTurn) {
            droppedStaleFrames++;
            return Verdict.STALE;
        }
        if (turnNo > currentTurn) {
            return Verdict.FUTURE;
        }
        return Verdict.CURRENT;
    }

    /** 打断：推进回合（打断本身把当前输出回合标记为过期）。 */
    public long advance() {
        currentTurn++;
        return currentTurn;
    }

    /** 当前回合。 */
    public long currentTurn() {
        return currentTurn;
    }

    /** 因过期被丢弃的帧/事件累计数（可持久化，用于审计与"不得静默丢弃"的证明）。 */
    public long droppedStaleFrames() {
        return droppedStaleFrames;
    }
}
