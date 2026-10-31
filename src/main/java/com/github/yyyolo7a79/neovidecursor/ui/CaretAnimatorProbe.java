package com.github.yyyolo7a79.neovidecursor.ui;

/** 探针用：暴露运动帧间隔常量（{@code ACTIVE_DELAY_MS} 是包私有）。 */
public final class CaretAnimatorProbe {
    private CaretAnimatorProbe() {
    }

    /** @return 运动时的调度间隔（毫秒） */
    public static int ACTIVE_DELAY_MS() {
        return CaretAnimator.ACTIVE_DELAY_MS;
    }

    /**
     * 【BUG-007】不可见编辑器的调度间隔（纳秒）；可见时返回 {@code -1}（交给原逻辑）。
     *
     * <p>转发生产用的同一个函数，不做任何复刻 —— 探针断言的就是生产行为。
     */
    public static long hiddenTierDelayNs(boolean showing) {
        return CaretAnimator.hiddenTierDelayNs(showing);
    }

    /**
     * 【U-M-7】在慢路径「已 markAt、未收尾」的那一刻注入一次动作（通常是抛异常）。
     *
     * <p>生产恒不调用；只有 {@code VCatchRollback} 用它制造「标志已消费、活没干完」的现场。
     *
     * @param hook 要执行的动作；{@code null} = 拆掉钩子
     */
    public static void setAfterMarkHook(Runnable hook) {
        CaretAnimator.afterMarkHookForProbe = hook;
    }
}
