package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * Tools | Neovide 光标：动画预设 ▸ —— 7 个动画预设的快捷切换（契约 §8）。
 *
 * <p><b>为什么用 {@link DefaultActionGroup} 而不是方案写的 {@code AnActionGroup}</b>：
 * 本机 381 个 jar 里<b>不存在</b> {@code com.intellij.openapi.actionSystem.AnActionGroup}
 * （javap 报「找不到类」，契约 §8.1 实测 A-9）。存在的是 {@code DefaultActionGroup}
 * 与抽象基类 {@code ActionGroup}。
 *
 * <p><b>为什么菜单项在构造器里动态 {@code add()}</b>：契约 §8.5 明令
 * {@code plugin.xml} 的 {@code <group>} 里<b>不能</b>再列 {@code <action>} 子项 ——
 * 两处都列会出现重名冲突。
 *
 * <p><b>选中态用 {@code ToggleAction} 的勾选，不用加粗</b>（契约 §8.3）：
 * {@code Cell.bold()} 只对 DSL 面板有效，菜单不适用；
 * 而 {@code Presentation.setText("✓ ...")} 会让菜单项宽度跳动，还与勾选重复。
 */
public final class AnimPresetGroup extends DefaultActionGroup {

    public AnimPresetGroup() {
        super(CursorPresets.MENU_ANIM, true);          // 第二个参数 true = popup
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            add(new ApplyAnimPresetAction(p));
        }
    }

    /** 菜单里的一项。选择判据与面板同源（{@link CursorPresetApplier#matchAnimPreset}）。 */
    static final class ApplyAnimPresetAction extends ToggleAction {
        private final CursorPresets.AnimPreset preset;

        ApplyAnimPresetAction(CursorPresets.AnimPreset p) {
            super(p.name());
            this.preset = p;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            // 与面板同源的判据：matchAnimPreset(state) == preset。
            // 这样天然保证「最多一个勾选」—— 不需要任何「记住上次点了哪个」的状态。
            return currentState() != null
                    && CursorPresetApplier.matchAnimPreset(currentState()) == preset;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (!state) {
                // 用户点已勾选的项时平台会回调 false。此时【必须】忽略：
                // "取消该预设"之后的状态没有定义（契约 §8.3）。
                return;
            }
            CursorPresetApplier.applyAnimPreset(preset);
        }

        /** 探针用：这一项代表哪个预设。 */
        CursorPresets.AnimPreset presetForProbe() {
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
