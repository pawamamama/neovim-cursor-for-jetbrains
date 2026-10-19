package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * 「辉光淡出」开关（F2 / 批次2）。
 *
 * <ul>
 *   <li><b>勾选（默认）</b>：拖尾停止运动后，辉光先保持 50ms，再用 75ms
 *       线性熄灭 —— 不再"啪一下消失"。<b>只淡辉光，光标本体始终不透明。</b></li>
 *   <li><b>不勾选</b>：辉光瞬时归零，行为与 0.9.1 完全一致。</li>
 * </ul>
 *
 * <p>注意：光标本体不参与淡出。因为默认档「隐藏原生光标」已勾选，此时覆盖层
 * 就是肉眼看到的"光标"，让整体淡出会变成"光标自己消失"。
 *
 * <p>切换后立即生效，无需重启。
 */
public class ToggleFadeOutAction extends ToggleAction {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return NeovideCaretManager.isOverlayFadeOut();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        NeovideCaretManager.setOverlayFadeOut(state);
    }
}
