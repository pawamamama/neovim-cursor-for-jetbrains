package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorBundle;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * 性能诊断开关：跳过全部重绘，只保留物理计算。
 *
 * <p>用于回答一个关键问题 —— <b>卡顿到底来自「重绘」还是「EDT 调度」</b>：
 * <ul>
 *   <li>勾选后 fps <b>大幅上升</b> → 瓶颈在重绘，值得改造成独立窗口方案</li>
 *   <li>勾选后 fps <b>依然很低</b> → 瓶颈在 EDT 负载，换窗口方案也没用</li>
 * </ul>
 *
 * <p>注意：勾选后拖尾会消失（不再绘制），这是预期行为，取消勾选即恢复。
 */
public class TogglePaintDiagAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.isPaintDisabled();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setPaintDisabled(state);
        Messages.showInfoMessage(
                state
                        ? NeovideCursorBundle.message("action.paintDiag.on")
                        : NeovideCursorBundle.message("action.paintDiag.off"),
                NeovideCursorBundle.message("action.paintDiag.title"));
    }
}
