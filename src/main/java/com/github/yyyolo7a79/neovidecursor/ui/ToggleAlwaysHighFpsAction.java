package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * 「常驻刷新」开关 —— 让调度器永远保持高帧率，不再在空闲时降频。
 *
 * <p><b>它解决的是"冷启动"</b>：默认策略下光标停止后调度间隔会从 8ms 涨到
 * 120ms（失焦时 600ms）。于是停手后第一次操作，从按键到画出第一帧之间
 * 最长要等一个空闲周期 —— 这就是「看代码时删个空行会卡一下」的时序根源。
 * 开启后无论有无动画都按 8ms 运行（约 125fps），该延迟被彻底消除。
 *
 * <p><b>代价</b>：每秒约 125 次任务投递到 EDT，且永不停歇。
 * EDT 是单线程，编辑器繁忙时会和编辑器抢时间；标签页很多或机器较慢时
 * 可能反而更卡。详见 {@code NeovideConfig#alwaysHighFps}。
 */
public class ToggleAlwaysHighFpsAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.isAlwaysHighFps();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setAlwaysHighFps(state);
    }
}
