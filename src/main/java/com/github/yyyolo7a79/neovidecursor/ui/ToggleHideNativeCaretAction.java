package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * 原生光标显隐切换。
 *
 * <ul>
 *   <li><b>不勾选（默认）</b>：保留原生光标，拖尾只在移动时叠加、停下后淡出 ——
 *       与 VS Code 版默认观感一致，跟手零延迟</li>
 *   <li><b>勾选</b>：隐藏原生光标，由插件常驻绘制光标（不闪烁、颜色取 tailColor），
 *       但跟随取决于 EDT 调度，繁忙时会有滞后</li>
 * </ul>
 */
public class ToggleHideNativeCaretAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.getConfig().hideNativeCaret;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setHideNativeCaret(state);
    }
}
