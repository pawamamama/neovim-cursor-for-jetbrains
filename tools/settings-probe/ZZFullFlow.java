import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 临时诊断：完整时序 —— 加载脏状态 → 点「恢复为默认值」→ 点「应用」→ 看 State。
 *
 * 用户当前状态：tailOpacity=0.05、选中「基础 副本」。
 * 症状：恢复后光标仍半透明。
 */
public final class ZZFullFlow {
    public static void main(String[] a) throws Exception {
        V0Env.install();

        // 1) 构造「用户当前」的持久化状态
        NeovideCursorSettings svc = V0Env.svc;
        NeovideCursorSettings.State s = svc.getState();
        s.tailOpacity = 0.05f;
        s.cursorWidthPx = 5;
        s.rgbGradient = true;
        s.animPresetBase = 0;
        s.animCustomName = "基础 副本";
        s.customAnimPresets = "基础 副本\u241FtailOpacity=0.05;animationLength=0.1";

        System.out.println("[初始] tailOpacity=" + s.tailOpacity + " name=" + s.animCustomName);

        // 2) 建面板并 reset（= 用户打开设置页）
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);       // 走 VUi 建面板（它内部会 disableValidators，绕开 JBPopupFactory）
        conf.reset();
        System.out.println("[reset后] pending.tailOpacity=" + conf.pendingForProbe().tailOpacity
                + " name=" + conf.pendingForProbe().animCustomName
                + " baseline.tailOpacity=" + baselineOf(conf).tailOpacity);

        // 3) 点「恢复为默认值」
        Method restore = NeovideCursorConfigurable.class.getDeclaredMethod("restoreAnimPreset");
        restore.setAccessible(true);
        restore.invoke(conf);
        System.out.println("[恢复后] pending.tailOpacity=" + conf.pendingForProbe().tailOpacity
                + " name=" + conf.pendingForProbe().animCustomName
                + " baseline.tailOpacity=" + baselineOf(conf).tailOpacity);

        // 4) 点「应用」
        conf.apply();
        System.out.println("[应用后] state.tailOpacity=" + svc.getState().tailOpacity
                + " name=" + svc.getState().animCustomName);

        boolean ok = svc.getState().tailOpacity > 0.99f;
        System.out.println(ok ? "=> 正确：光标应恢复不透明"
                : "=> BUG：state 仍是 " + svc.getState().tailOpacity + "，光标会半透明");
        System.exit(ok ? 0 : 1);
    }

    private static NeovideCursorSettings.State baselineOf(NeovideCursorConfigurable conf)
            throws Exception {
        Field f = NeovideCursorConfigurable.class.getDeclaredField("baseline");
        f.setAccessible(true);
        return (NeovideCursorSettings.State) f.get(conf);
    }
}
