package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * 「隐藏原生光标（常驻绘制）」开关。
 *
 * <p><b>上游踩坑 #19 的两个选项：</b>
 *
 * <ul>
 *   <li><b>不隐藏（默认）</b>—— 原生光标负责零延迟跟随，覆盖层只在移动时
 *       叠加拖尾、停下后淡出。这是上游验证过的最佳观感，
 *       也是「不开常驻刷新也不卡」的根本原因：跟随不依赖我们的调度。</li>
 *   <li><b>隐藏（本开关勾选后）</b>—— 覆盖层就是光标本体，颜色和不闪烁特性
 *       可自定义，但<b>跟随完全取决于 EDT 调度</b>：空闲降频时会有明显延迟，
 *       表现为「停手后第一次操作卡一下」，此时通常需要配合「常驻刷新」。</li>
 * </ul>
 *
 * <p>默认不勾选。切换后立即对已打开的编辑器生效，无需重启。
 */
public class ToggleHideNativeCaretAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.isHideNativeCaret();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setHideNativeCaret(state);
    }
}
