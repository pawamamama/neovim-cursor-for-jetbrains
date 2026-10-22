package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import org.jetbrains.annotations.NotNull;

/**
 * Tools | Neovide 光标：动画预设 ▸ —— 内置动画预设的快捷切换（数量与 {@code ANIM_PRESETS} 同源）。
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

        // 内置预设（只读）
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            add(new ApplyAnimPresetAction(p));
        }

        // 自定义预设（用户自己存的）—— 动态展开。
        //
        // 【为什么必须在这里加（用户多次反馈："保存了自定义设置但是 tools 依旧没有"）】
        // 用户既然能在面板里存自定义预设，Tools 里就必须能一键切到它，
        // 否则"快捷切换"这个入口对自建预设是残废的。
        //
        // 注意：自定义预设是【运行期数据】（存在 State 里），不是编译期常量，
        // 所以只能在构造 / update 时从 settings 读。
        rebuildCustom();
    }

    /** 从 settings 重建自定义预设部分（内置部分不动）。 */
    private void rebuildCustom() {
        for (AnAction a : getChildActionsOrStubs()) {
            if (a instanceof ApplyCustomAnimPresetAction) {
                remove(a);
            }
        }
        NeovideCursorSettings svc = settingsOrNull();
        if (svc == null) {
            return;
        }
        java.util.List<com.github.yyyolo7a79.neovidecursor.settings.CustomPresets.Entry> list =
                com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                        .parse(svc.getState().customAnimPresets);
        if (list.isEmpty()) {
            return;
        }
        addSeparator();
        for (com.github.yyyolo7a79.neovidecursor.settings.CustomPresets.Entry e : list) {
            add(new ApplyCustomAnimPresetAction(e.name));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // 每次弹出前重建：用户可能在面板里刚新建 / 改名 / 删除了自定义预设。
        rebuildCustom();
        super.update(e);
    }

    private static NeovideCursorSettings settingsOrNull() {
        try {
            return NeovideCursorSettings.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自定义预设的一项。点它就整份应用那个快照。 */
    static final class ApplyCustomAnimPresetAction extends ToggleAction {
        private final String presetName;

        ApplyCustomAnimPresetAction(String name) {
            super(name);
            this.presetName = name;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            NeovideCursorSettings svc = settingsOrNull();
            // 只有当"当前正在用的自定义预设"就是它时才勾选。
            // 判据与面板同源（animCustomName），不靠猜数值。
            return svc != null && presetName.equals(svc.getState().animCustomName);
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (!state) {
                return;                    // 取消勾选无定义，忽略（与内置项一致）
            }
            CursorPresetApplier.applyCustomAnimPreset(presetName);
        }

        String presetNameForProbe() {
            return presetName;
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
