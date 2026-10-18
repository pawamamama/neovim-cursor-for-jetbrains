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

    // 【曾有一个 MAX_STEP_RATIO = 0.5 的单帧位移截断，已移除】
    //
    // 它本意是低帧率下让位移在几帧内均匀分布（防第一帧吃掉大部分位移），
    // 但代价是把临界阻尼曲线切成锯齿：大位移时每帧都触发截断、每帧清零速度，
    // 观感是「走到约二分之一处停顿一下」——那个"二分之一"正是 0.5。
    //
    // 触发条件恰好与卡顿场景重合：dt 越大越容易超限。
    // 平时 dt≈8ms、animationLength=0.05 时单帧只走 13.6%（不触发）；
    // 一旦 EDT 被抢占、dt 被 MAX_DT 钳到 33.3ms，单帧就要走 74.5% → 触发截断，
    // 于是「越卡越锯齿」。而"删除到上一行"正是大位移 + 高延迟的组合。
    // 上游 v0.5.0 同样移除了这一项（见其提交说明「撤销弹簧单帧位移截断」）。

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

    /**
     * 按 dt 推进弹簧状态。
     *
     * @param dt 时间步长（秒）
     * @return true 表示仍在运动（需要继续重绘）；false 表示已静止
     */
    public boolean update(double dt) {
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
        // 副作用很隐蔽：大位移时每帧都触发截断、速度被反复清零，临界阻尼曲线
        // 被切成「走一半、停一下、再走一半」的锯齿 ——
        // 用户实测描述为「删除到上一行，走到约二分之一处会停顿一下」。
        //
        // 注意仍<b>绝不能改衰减因子 c</b>：c 参与速度积分（b 里已含速度位移），
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
