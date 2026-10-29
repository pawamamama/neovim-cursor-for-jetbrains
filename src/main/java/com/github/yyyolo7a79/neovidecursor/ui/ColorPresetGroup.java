package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
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
        rebuild();
    }

    /**
     * 每次弹出前重建（2026-10-29）。
     *
     * <p>与 {@link AnimPresetGroup} 同构：用户刚在面板里新建/改名/删除的颜色预设，
     * 必须立刻反映到菜单。整体重建而不是增量删改 —— 增量实现漏删分隔线会把菜单撑爆
     * （AnimPresetGroup 里记录过那次 blocker）。
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        rebuild();
    }

    private void rebuild() {
        removeAll();
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            add(new ApplyColorPresetAction(p));
        }
        NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
        if (svc == null) {
            return;
        }
        java.util.List<CustomPresets.Entry> list =
                CustomPresets.parse(svc.getState().customColorPresets);
        if (list.isEmpty()) {
            return;
        }
        addSeparator();
        for (CustomPresets.Entry entry : list) {
            add(new ApplyCustomColorPresetAction(entry.name));
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
            // 【2026-10-29 审查 H1】必须要求「当前没站在自定义颜色预设上」。
            //
            // 不加这个守卫时，两套判据可以同时为真：内置项按 HEX 相等判定，
            // 自定义项按名字判定。于是刚从一个内置色派生出的副本（还没改过色，
            // 数值必然与父相同）会让「薄荷」和「薄荷 副本」**同时打勾**，单选契约破裂。
            //
            // 动画侧 AnimPresetGroup 早就因为同一个问题（第三轮审查 ④，high）加了
            // `animCustomName.isEmpty()`，颜色侧这次补上。
            NeovideCursorSettings.State st = currentState();
            return st != null && st.colorCustomName.isEmpty()
                    && CursorPresetApplier.matchColorPreset(st) == preset;
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

    /** 自定义颜色预设的菜单项（2026-10-29）。选中判据按名字，与面板同源。 */
    static final class ApplyCustomColorPresetAction extends ToggleAction {
        private final String presetName;

        ApplyCustomColorPresetAction(String name) {
            super(name);
            this.presetName = name;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
            return svc != null && presetName.equals(svc.getState().colorCustomName);
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (!state) {
                return;         // 取消勾选不做事（预设是单选语义）
            }
            CursorPresetApplier.applyCustomColorPreset(presetName);
        }
    }
}
