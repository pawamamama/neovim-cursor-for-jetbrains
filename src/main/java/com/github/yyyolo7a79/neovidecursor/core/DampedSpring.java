package com.github.yyyolo7a79.neovidecursor.core;

/**
 * 阻尼弹簧动画 —— 拖尾运动的物理核心。
 *
 * <p>逐行移植自 VS Code 版 {@code neovide-cursor.js} 的 {@code DampedSpringAnimation} 类
 * （原文件第 73-101 行）。这是一个解析解形式的临界阻尼弹簧：
 * 给定"当前位置相对目标的偏移"和"速度"，按时间步长 dt 推进，指数式衰减到 0。
 *
 * <p>与常见弹簧实现的区别：它不是逐帧迭代积分，而是用闭式解直接跳到 dt 之后的状态，
 * 因此<b>完全不受帧率波动影响</b> —— 同样长的时间，无论中间掉多少帧，结果都一致。
 */
public class DampedSpring {

    // 【曾有一个 MAX_STEP_RATIO = 0.5 的单帧位移截断，已于本次移除】
    //
    // 它本意是低帧率下让位移在几帧内均匀分布（防止第一帧吃掉大部分位移），
    // 但代价是把临界阻尼曲线切成锯齿：大位移时每帧都触发截断、每帧清零速度，
    // 观感是「走到约二分之一处停顿一下」—— 那个"二分之一"就是 0.5。
    //
    // 现在低帧率场景由别处接管（固定 12ms 调度 + 恢复检测归位异常 dt），
    // 因此这里严格按原版 JS 的闭式解推进。

    /** 当前相对位移（相对目标点的偏移量） */
    private double position = 0.0;

    /** 当前速度 */
    private double velocity = 0.0;

    /** 动画特征时长（秒）：越大衰减越慢、拖尾越长 */
    private double animationLength;

    public DampedSpring(double animationLength) {
        this.animationLength = animationLength;
    }

    public void setAnimationLength(double animationLength) {
        this.animationLength = animationLength;
    }

    public double getPosition() {
        return position;
    }

    public void setPosition(double position) {
        this.position = position;
    }

    // ==================== 探针访问器（纯新增，零行为变化）====================
    //
    // 【为什么需要】velocity 与 animationLength 都没有访问器，而守门探针
    // VAuditBatch1 必须能把 NaN 精确喂进这两个量、并在 jump 之后读出实际分到的时长。
    // 没有它们，「只删掉 velocity 那一条守卫」这种变异不会让任何断言变红 —— 等于没测。

    /** 探针专用：直接写入速度（生产代码只通过 {@link #update} 改它）。 */
    public void setVelocityForProbe(double velocity) {
        this.velocity = velocity;
    }

    /** 探针专用：读当前速度。 */
    public double getVelocityForProbe() {
        return velocity;
    }

    /** 探针专用：读当前动画时长（{@code TrailCorner.jump} 每帧写入）。 */
    public double getAnimationLengthForProbe() {
        return animationLength;
    }

    /**
     * 按 dt 推进弹簧状态。
     *
     * @param dt 时间步长（秒）
     * @return true 表示仍在运动（需要继续重绘）；false 表示已静止
     */
    public boolean update(double dt) {
        // ==================== 【M-4，2026-10-30】非有限值守卫 ====================
        //
        // 【为什么必须有】下面那个复位分支用的是 `Math.abs(position) < 0.001`，
        // 而 `Math.abs(NaN) < 0.001` 恒为 false —— 于是 NaN 一旦进来，复位分支
        // 永不触发；同时 TrailCorner.update() 用 `destX != previousDestX` 判断目标是否变化，
        // 这个比较对 NaN 恒为 true，于是每帧都把 NaN 重新写回弹簧。
        // 结果是：该角点<b>永久 NaN</b> → `Path2D.lineTo(NaN, …)` 被 Java2D 静默忽略
        // → 光标不显示、日志里一个字都没有，直到某次显式 jump/snapTo 才可能恢复。
        //
        // 【归零而不是「忽略本帧」】position=0 的语义是「已在目标位置上」，
        // 视觉上是「没有拖尾」而不是「光标消失」；而且下一次 jump() 会重新写入
        // 有限时长与位置，有限值一进来就正常推进 —— 这就是自愈。
        if (!Double.isFinite(position) || !Double.isFinite(velocity)
                || !Double.isFinite(animationLength)) {
            reset();
            return false;
        }

        // 动画时长已短于时间步长，或位移已足够小 —— 直接归零，避免无意义的微小抖动
        if (animationLength <= dt || Math.abs(position) < 0.001) {
            position = 0.0;
            velocity = 0.0;
            return false;
        }

        // 阻尼系数：animationLength 越大，o 越小，衰减越慢（拖尾越长）
        final double o = 4.0 / animationLength;
        final double c = Math.exp(-o * dt);

        final double a = position;
        final double b = position * o + velocity;

        double newPosition = (a + b * dt) * c;
        double newVelocity = c * (-a * o - b * dt * o + b);

        // 【不做单帧位移截断 —— 曾用 MAX_STEP_RATIO = 0.5，已移除】
        //
        // 曾经的写法：把单帧位移限制为「当前偏移的 50%」，超了就截断并把速度清零。
        // 它带来一个很隐蔽的副作用：大位移时<b>每一帧都会触发截断</b>，
        // 于是速度被反复清零，临界阻尼曲线被切成「走一半、停一下、再走一半」的
        // 锯齿运动 —— 用户实测的现象是「删除到上一行，走到约二分之一处会停顿一下」。
        // 那个"二分之一"正是 0.5 这个系数的数值痕迹。
        //
        // 该截断原本是为了防低帧率下一帧跳太远，但现在这个场景已由别处接管：
        //   · ACTIVE_DELAY_MS 固定为 12ms，正常情况 dt 很稳定
        //   · CaretAnimator 的「恢复检测」会在 dt 异常（>100ms）时把 dt 归位
        // 因此这里严格按原版 JS 的闭式解推进，不做任何截断 —— 运动才是连续的。
        //
        // 注意仍然<b>绝不能改衰减因子 c</b>：c 参与速度积分（b 里已含速度位移），
        // 改它会让位置被反向放大（实测 100 → 150），逐帧累积后动画鬼畜（踩坑 #4）。

        position = newPosition;
        velocity = newVelocity;

        // 位移仍大于 0.5 像素则视为"还在动"（原 JS 版用 0.01，此处按像素尺度说明）
        return Math.abs(position) >= 0.01;
    }

    /** 立即停止运动并归零 */
    public void reset() {
        position = 0.0;
        velocity = 0.0;
    }
}
