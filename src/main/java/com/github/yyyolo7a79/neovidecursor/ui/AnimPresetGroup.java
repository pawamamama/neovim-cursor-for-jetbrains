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

        // 内置 + 自定义全部由 rebuildCustom 建（避免两处各建一半、累加口径不一）
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

    /**
     * 重建整个菜单 = 内置（只读） + 可选分隔线 + 自定义（运行期数据）。
     *
     * <p><b>为什么必须 removeAll() 后整体重建，而不是"只删自定义那部分"</b>
     * （第三轮审查 ①，blocker）：
     * 最初只 {@code remove(ApplyCustomAnimPresetAction)}，<b>从不删 {@code addSeparator()}
     * 加的分隔线</b>，而本方法在 {@link #update} 里每次弹出菜单都会调 ——
     * 实测打开 50 次菜单后子项 5→55、分隔线 1→51，菜单被撑爆。
     *
     * <p>而且 {@code list.isEmpty()} 提前 return 时也不清理，删光自定义预设后仍残留一条空线。
     *
     * <p>整体重建的代价可以忽略（菜单项最多几十个），换来的是"任何时刻菜单内容都严格等于
     * 当前 state"，不存在累加或残留。
     */
    private void rebuildCustom() {
        removeAll();
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            add(new ApplyAnimPresetAction(p));
        }
        NeovideCursorSettings svc = settingsOrNull();
        if (svc == null) {
            return;
        }
        java.util.List<com.github.yyyolo7a79.neovidecursor.settings.CustomPresets.Entry> list =
                com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                        .parse(svc.getState().customAnimPresets);
        if (list.isEmpty()) {
            return;                      // 没有自定义预设 → 不加分隔线（不留空线）
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
            //
            // 【第三轮审查 ④，high：必须排除「正在用自定义预设」的状态】
            // 内置项按【数值】判定（matchAnimPreset），自定义项按【名字】判定（animCustomName）
            // —— 两套判据可以同时为真。实测：自定义预设的数值恰好等于「基础」时，
            // 「基础」与「我的基础副本」两项【同时打勾】，单选契约被打破。
            // 用户在内置预设上点「新建预设」必然触发（副本数值就是原样复制的）。
            //
            // 修法：animCustomName 非空 = 当前在用某个自定义预设 → 内置项一律不勾。
            // 这与面板下拉的「自定义优先」是同一个语义。
            NeovideCursorSettings.State st = currentState();
            return st != null
                    && st.animCustomName.isEmpty()
                    && CursorPresetApplier.matchAnimPreset(st) == preset;
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
