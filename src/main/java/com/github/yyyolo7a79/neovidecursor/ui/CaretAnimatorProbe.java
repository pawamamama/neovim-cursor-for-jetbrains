package com.github.yyyolo7a79.neovidecursor.ui;

/** 探针用：暴露运动帧间隔常量（{@code ACTIVE_DELAY_MS} 是包私有）。 */
public final class CaretAnimatorProbe {
    private CaretAnimatorProbe() {
    }

    /** @return 运动时的调度间隔（毫秒） */
    public static int ACTIVE_DELAY_MS() {
        return CaretAnimator.ACTIVE_DELAY_MS;
    }
}
