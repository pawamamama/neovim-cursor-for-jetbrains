package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * Tools | Neovide 光标：颜色预设 ▸ —— 5 个颜色预设的快捷切换（契约 §8）。
 *
 * <p>与 {@link AnimPresetGroup} 结构完全同构，只是写入的是 2 个 HEX。
 * 两者<b>完全正交</b>：换颜色不动任何动画字段，换动画不动任何颜色字段。
 */
public final class ColorPresetGroup extends DefaultActionGroup {

    public ColorPresetGroup() {
        super(CursorPresets.MENU_COLOR, true);
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            add(new ApplyColorPresetAction(p));
        }
    }

    /** 菜单里的一项。 */
    static final class ApplyColorPresetAction extends ToggleAction {
        private final CursorPresets.ColorPreset preset;

        ApplyColorPresetAction(CursorPresets.ColorPreset p) {
            super(p.name());
            this.preset = p;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return currentState() != null
                    && CursorPresetApplier.matchColorPreset(currentState()) == preset;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (!state) {
                return;         // 取消勾选不做事（预设是单选语义）
            }
            CursorPresetApplier.applyColorPreset(preset);
        }

        /** 探针用：这一项代表哪个预设。 */
        CursorPresets.ColorPreset presetForProbe() {
            return preset;
        }

        private static NeovideCursorSettings.State currentState() {
            try {
                NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
                return svc == null ? null : svc.getState();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
