import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.xmlb.XmlSerializer;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * verifier 自建探针 P —— 设置持久化往返 + 默认值回落 + 旧键迁移幂等。
 *
 * <p>驱动的是 build\classes 里【真实的编译产物】：{@link NeovideCursorSettings} 的
 * serialize/deserialize/loadState/noStateLoaded/migrateIfNeeded 全是真代码，
 * 平台侧只伪造 {@link Application} 与 {@link PropertiesComponent}（无法离线启动真 IDEA）。
 *
 * <p>三块：
 *   P1  跨进程往返：阶段 write 把「全部 16 项非默认值」写成真实 XML 文件；
 *       阶段 read 在【另一个 JVM】里解析同一文件 → deserialize → loadState → 逐字段比对。
 *   P2  空 XML / 无 state → 回落默认值，且与 NeovideConfig 现值逐位相等。
 *   P3  旧键迁移三分支（A-1 / A-2 / B）+ 落盘失败回退 + 第二次启动幂等（不重复覆盖、不复活）。
 *
 * 用法：
 *   java ... VerifyPersistence write <xml>      # 写文件
 *   java ... VerifyPersistence read  <xml>      # 读文件并判定
 *   java ... VerifyPersistence all   <xml>      # 同进程跑 P2/P3（P1 由脚本串两进程）
 */

public final class VerifyPersistence {

    private static int pass = 0;
    private static int fail = 0;

    // ===== P1 期望值（write 与 read 共用；read 在另一个 JVM 里独立重算期望）=====
    static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("stateVersion", "1");
        EXPECTED.put("enabled", "false");
        EXPECTED.put("trailMode", "AFTERIMAGE");
        EXPECTED.put("hideNativeCaret", "false");
        EXPECTED.put("alwaysHighFps", "false");
        EXPECTED.put("overlayFadeOut", "false");
        EXPECTED.put("cursorDisappearDelay", "320");
        EXPECTED.put("cursorFadeOutDuration", "0.615");
        EXPECTED.put("tailColor", "#0A1B2C");
        EXPECTED.put("tailOpacity", "0.625");
        EXPECTED.put("useShadow", "false");
        EXPECTED.put("shadowColor", "#3D4E5F");
        EXPECTED.put("glowOpacity", "0.375");
        EXPECTED.put("glowLayers", "11");
        EXPECTED.put("shadowBlurFactor", "0.47");
        EXPECTED.put("trailSize", "0.812");
        EXPECTED.put("stretchRatioK", "6.2");
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        File file = new File(args.length > 1 ? args[1] : "verify-state.xml");

        System.out.println("=== verifier 探针 P（持久化往返 / 默认回落 / 迁移幂等）===");
        System.out.println("  java=" + System.getProperty("java.version")
                + "  pid=" + ProcessHandle.current().pid()
                + "  mode=" + mode + "  file=" + file.getAbsolutePath());

        if (mode.equals("write") || mode.equals("all")) {
            p1Write(file);
        }
        if (mode.equals("read")) {
            p1Read(file);
        }
        if (mode.equals("all")) {
            p2EmptyXml();
            p3Migration();
        }

        System.out.println();
        System.out.printf("=== 结果：通过 %d，失败 %d ===%n", pass, fail);
        if (fail > 0) {
            throw new AssertionError("有 " + fail + " 项未通过");
        }
        System.out.println("ALL OK");
    }

    // ==================== P1：真实 XML 文件往返（跨进程）====================

    private static void p1Write(File file) throws Exception {
        System.out.println();
        System.out.println("--- P1a. 写：把 16 项全部改成非默认值，用平台 XmlSerializer 序列化到真实文件 ---");
        NeovideCursorSettings.State s = stateFromExpected();

        Element el = XmlSerializer.serialize(s);
        String xml = new XMLOutputter().outputString(el);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(xml);
        }
        System.out.println("  XML 长度 = " + xml.length() + " 字节");
        System.out.println("  XML 内容 = " + xml);
        System.out.println("  文件大小 = " + file.length() + " 字节");
        check("写阶段：文件已落盘且非空", file.isFile() && file.length() > 0, file.getAbsolutePath());
    }

    private static void p1Read(File file) throws Exception {
        System.out.println();
        System.out.println("--- P1b. 读：另一个 JVM 解析同一文件 → deserialize → loadState → 逐字段比对 ---");
        check("读阶段：文件存在", file.isFile(), file.getAbsolutePath());
        Document doc = new SAXBuilder().build(file);
        Element root = doc.getRootElement();
        System.out.println("  根元素 = <" + root.getName() + ">  属性 = " + root.getAttributes());

        NeovideCursorSettings.State back =
                XmlSerializer.deserialize(root, NeovideCursorSettings.State.class);
        check("读阶段：deserialize 返回非 null", back != null, String.valueOf(back));

        NeovideCursorSettings svc = new NeovideCursorSettings();
        svc.loadState(back);                 // 真实 loadState（含 clamp）
        NeovideCursorSettings.State got = svc.getState();

        System.out.println("  " + pad("字段") + " | " + pad("期望") + " | " + pad("实际") + " | 判定");
        for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
            String actual = fieldAsString(got, e.getKey());
            boolean ok = e.getValue().equals(actual);
            System.out.println("  " + pad(e.getKey()) + " | " + pad(e.getValue()) + " | " + pad(actual)
                    + " | " + (ok ? "OK" : "MISMATCH"));
            if (!ok) {
                failOnce("P1 " + e.getKey() + " 跨进程往返后不一致（期望 " + e.getValue() + "，实际 " + actual + "）");
            }
        }
        check("P1：17 个字段跨进程往返后逐字段相等", fail == 0, "当前失败数 " + fail);
    }

    private static NeovideCursorSettings.State stateFromExpected() {
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.stateVersion = 1;
        s.enabled = false;
        s.trailMode = NeovideConfig.TrailMode.AFTERIMAGE;
        s.hideNativeCaret = false;
        s.alwaysHighFps = false;
        s.overlayFadeOut = false;
        s.cursorDisappearDelay = 320;
        s.cursorFadeOutDuration = 0.615f;
        s.tailColor = "#0A1B2C";
        s.tailOpacity = 0.625f;
        s.useShadow = false;
        s.shadowColor = "#3D4E5F";
        s.glowOpacity = 0.375f;
        s.glowLayers = 11;
        s.shadowBlurFactor = 0.47f;
        s.trailSize = 0.812f;
        s.stretchRatioK = 6.2f;
        return s;
    }

    // ==================== P2：空 XML / 无 state → 默认值 ====================

    private static void p2EmptyXml() throws Exception {
        System.out.println();
        System.out.println("--- P2. 空 XML / 无 state → 回落默认值（与 NeovideConfig 现值逐位一致）---");

        // P2a：平台在 XML 里只有 <component name="..."/> 时的形态
        Element empty = new Element("component");
        empty.setAttribute("name", "NeovideCursorSettings");
        NeovideCursorSettings.State fromEmpty =
                XmlSerializer.deserialize(empty, NeovideCursorSettings.State.class);
        check("P2a 空 <component/> deserialize 返回非 null（平台据此调 loadState）",
                fromEmpty != null, String.valueOf(fromEmpty));

        NeovideCursorSettings svcNoApp = new NeovideCursorSettings();
        if (fromEmpty != null) {
            svcNoApp.loadState(fromEmpty);
        } else {
            svcNoApp.noStateLoaded();
        }
        compareDefaults("P2a 空 XML → loadState", svcNoApp.getState());

        // P2b：首次安装：平台根本不调 loadState，只调 noStateLoaded
        NeovideCursorSettings fresh = new NeovideCursorSettings();
        fresh.noStateLoaded();
        compareDefaults("P2b noStateLoaded（无 Application）", fresh.getState());
        System.out.println("  [P2b] 无 Application 时 persistQuietly 返回 false ⇒ stateVersion 回退 = "
                + fresh.getState().stateVersion + "（离线环境的预期行为，见 NeovideCursorSettings:429-434）");
        check("P2b 离线无 Application 时 stateVersion 回退为 0（不谎称落盘成功）",
                fresh.getState().stateVersion == 0, "实际 " + fresh.getState().stateVersion);
    }

    /** 逐字段与 NeovideConfig 现值比对（float/颜色用逐位/逐字比较）。 */
    private static void compareDefaults(String tag, NeovideCursorSettings.State s) {
        NeovideConfig c = new NeovideConfig();
        int before = fail;
        eqB(tag + " enabled", s.enabled, c.enabled);
        eqE(tag + " trailMode", s.trailMode, c.trailMode);
        eqB(tag + " hideNativeCaret", s.hideNativeCaret, c.hideNativeCaret);
        eqB(tag + " alwaysHighFps", s.alwaysHighFps, c.alwaysHighFps);
        eqB(tag + " overlayFadeOut", s.overlayFadeOut, c.overlayFadeOut);
        eqI(tag + " cursorDisappearDelay", s.cursorDisappearDelay, c.cursorDisappearDelay);
        eqF(tag + " cursorFadeOutDuration", s.cursorFadeOutDuration, c.cursorFadeOutDuration);
        eqS(tag + " tailColor", s.tailColor, c.tailColor);
        eqF(tag + " tailOpacity", s.tailOpacity, c.tailOpacity);
        eqB(tag + " useShadow", s.useShadow, c.useShadow);
        eqS(tag + " shadowColor", s.shadowColor, c.shadowColor);
        eqF(tag + " glowOpacity", s.glowOpacity, c.glowOpacity);
        eqI(tag + " glowLayers", s.glowLayers, c.glowLayers);
        eqF(tag + " shadowBlurFactor", s.shadowBlurFactor, c.shadowBlurFactor);
        eqF(tag + " trailSize", s.trailSize, c.trailSize);
        // 第 16 项：派生值逐位复现 config.trailFactors
        float[] derived = NeovideCursorSettings.deriveTrailFactors(s.stretchRatioK);
        boolean same = derived.length == c.trailFactors.length;
        for (int i = 0; i < Math.min(derived.length, c.trailFactors.length); i++) {
            same &= Float.floatToRawIntBits(derived[i]) == Float.floatToRawIntBits(c.trailFactors[i]);
        }
        check(tag + " | derive(stretchRatioK=" + s.stretchRatioK + ") 与 NeovideConfig.trailFactors 逐位相等",
                same, arr(derived) + " vs " + arr(c.trailFactors));
        check(tag + " | 15 个字段 + 派生数组全部与 NeovideConfig 现值一致", fail == before,
                "本段失败 " + (fail - before));
    }

    // ==================== P3：旧键迁移 ====================

    private static void p3Migration() throws Exception {
        System.out.println();
        System.out.println("--- P3. 旧键迁移（三分支 + 落盘失败 + 第二次启动幂等）---");

        FakeProps pc = new FakeProps();
        FakeApp app = new FakeApp();
        app.pc = pc;
        installFakeApp(app);
        System.out.println("  已注入假 Application；getService(PropertiesComponent) 可用 = "
                + (ApplicationManager.getApplication().getService(PropertiesComponent.class) == pc));

        // ---------- A-2：干净安装（无旧键）→ 默认 true ----------
        pc.clear();
        NeovideCursorSettings a2 = new NeovideCursorSettings();
        a2.noStateLoaded();
        System.out.println("  [A-2 无旧键] " + a2.getState());
        check("A-2 干净安装 alwaysHighFps = true（这正是被修掉的 bug）",
                a2.getState().alwaysHighFps, "实际 " + a2.getState().alwaysHighFps);
        check("A-2 干净安装 overlayFadeOut = true", a2.getState().overlayFadeOut,
                "实际 " + a2.getState().overlayFadeOut);
        check("A-2 迁移后 stateVersion = 1", a2.getState().stateVersion == 1,
                "实际 " + a2.getState().stateVersion);
        check("A-2 无旧键可删（unset 调用 0 次）", pc.unsetCalls == 0, "实际 " + pc.unsetCalls);
        check("A-2 落盘被调用（saveSettings ≥ 1）", app.saves >= 1, "实际 " + app.saves);

        // ---------- A-1：旧键存在且为 false → 必须搬 false ----------
        pc.clear();
        pc.map.put("neovide.cursor.alwaysHighFps", "false");
        pc.map.put("neovide.cursor.overlayFadeOut", "false");
        NeovideCursorSettings a1 = new NeovideCursorSettings();
        a1.noStateLoaded();
        System.out.println("  [A-1 旧键=false] " + a1.getState());
        check("A-1 旧键 alwaysHighFps=false 被保留（未被重置成 true）",
                !a1.getState().alwaysHighFps, "实际 " + a1.getState().alwaysHighFps);
        check("A-1 旧键 overlayFadeOut=false 被保留",
                !a1.getState().overlayFadeOut, "实际 " + a1.getState().overlayFadeOut);
        check("A-1 两个旧键都被删除", pc.map.isEmpty(), "剩余 " + pc.map.keySet());

        // ---------- A-1：旧键 = true ----------
        pc.clear();
        pc.map.put("neovide.cursor.alwaysHighFps", "true");
        pc.map.put("neovide.cursor.overlayFadeOut", "true");
        NeovideCursorSettings a1t = new NeovideCursorSettings();
        a1t.noStateLoaded();
        check("A-1 旧键 alwaysHighFps=true 被保留", a1t.getState().alwaysHighFps,
                "实际 " + a1t.getState().alwaysHighFps);
        check("A-1 旧键 overlayFadeOut=true 被保留", a1t.getState().overlayFadeOut,
                "实际 " + a1t.getState().overlayFadeOut);

        // ---------- A-1 + 迁移幂等：再跑一次 noStateLoaded 不得覆盖已迁移的 state ----------
        // 场景：第一次迁移已落盘（stateVersion=1），随后旧键被别的途径写回（降级再升级）
        pc.clear();
        pc.map.put("neovide.cursor.alwaysHighFps", "false");   // 旧键复活且与 state 相反
        NeovideCursorSettings.State existing = new NeovideCursorSettings.State();
        existing.stateVersion = 1;
        existing.alwaysHighFps = true;
        existing.overlayFadeOut = true;
        int savesBefore = app.saves;
        NeovideCursorSettings b = new NeovideCursorSettings();
        b.loadState(existing);
        System.out.println("  [B 已迁移 + 旧键复活] " + b.getState());
        check("B 已迁移时不读旧键（alwaysHighFps 保持 state 的 true）", b.getState().alwaysHighFps,
                "实际 " + b.getState().alwaysHighFps);
        check("B 已迁移时旧键被清理（防降级-再升级复活）", pc.map.isEmpty(), "剩余 " + pc.map.keySet());
        System.out.println("  落盘次数：migrate 前 " + savesBefore + " → 后 " + app.saves);

        // 同一个 service 再跑一次 noStateLoaded（模拟平台二次回调）：stateVersion 已是 1
        NeovideCursorSettings.State before2 = new NeovideCursorSettings.State();
        before2.copyFrom(b.getState());
        b.noStateLoaded();
        check("幂等：第二次 noStateLoaded 不覆盖已迁移的 state",
                b.getState().alwaysHighFps == before2.alwaysHighFps
                        && b.getState().stateVersion == before2.stateVersion,
                "before=" + before2.alwaysHighFps + "/v" + before2.stateVersion
                        + " after=" + b.getState().alwaysHighFps + "/v" + b.getState().stateVersion);

        // ---------- 落盘失败：版本号回退 0、旧键保留 ----------
        app.throwOnSave = true;
        pc.clear();
        pc.map.put("neovide.cursor.alwaysHighFps", "false");
        NeovideCursorSettings failing = new NeovideCursorSettings();
        failing.noStateLoaded();
        System.out.println("  [落盘失败] " + failing.getState());
        check("落盘失败 ⇒ stateVersion 回退 0（下次启动重试）",
                failing.getState().stateVersion == 0, "实际 " + failing.getState().stateVersion);
        check("落盘失败 ⇒ 旧键保留（绝不出现「键删了、新值没写」的丢值窗口）",
                pc.isValueSet("neovide.cursor.alwaysHighFps"), "剩余 " + pc.map.keySet());
        app.throwOnSave = false;
    }

    // ==================== 假平台 ====================

    /** 假 Application 的可观测状态（saveSettings 次数 / 是否抛异常 / 假 PropertiesComponent）。 */
    static final class FakeApp {
        FakeProps pc;
        int saves;
        boolean throwOnSave;
    }

    /** 把 FakeApp 包成 Application 代理并装进 ApplicationManager。 */
    private static void installFakeApp(FakeApp app) {
        Application proxy = (Application) Proxy.newProxyInstance(
                VerifyPersistence.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getService":
                            if (args != null && args.length == 1 && args[0] == PropertiesComponent.class) {
                                return app.pc;
                            }
                            return null;
                        case "isDispatchThread":
                            return true;
                        case "saveSettings":
                            app.saves++;
                            if (app.throwOnSave) {
                                throw new IllegalStateException("模拟落盘失败");
                            }
                            return null;
                        case "invokeLater":
                            ((Runnable) args[0]).run();
                            return null;
                        case "toString":
                            return "FakeApp";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return p == args[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
        ApplicationManager.setApplication(proxy, () -> { });
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    static final class FakeProps extends PropertiesComponent {
        final Map<String, String> map = new HashMap<>();
        int unsetCalls;

        void clear() {
            map.clear();
            unsetCalls = 0;
        }

        @Override public void unsetValue(String name) { map.remove(name); unsetCalls++; }
        @Override public boolean isValueSet(String name) { return map.containsKey(name); }
        @Override public String getValue(String name) { return map.get(name); }
        @Override public void setValue(String name, String value) { map.put(name, value); }
        @Override public void setValue(String name, String value, String def) { map.put(name, value); }
        @Override public void setValue(String name, float value, float def) { map.put(name, String.valueOf(value)); }
        @Override public void setValue(String name, int value, int def) { map.put(name, String.valueOf(value)); }
        @Override public void setValue(String name, boolean value, boolean def) { map.put(name, String.valueOf(value)); }
        @Override public String[] getValues(String name) { return new String[0]; }
        @Override public void setValues(String name, String[] values) { }
        @Override public List<String> getList(String name) { return new ArrayList<>(); }
        @Override public void setList(String name, Collection<String> values) { }
        @Override public boolean updateValue(String name, boolean value) {
            map.put(name, String.valueOf(value));
            return true;
        }
    }

    // ==================== 工具 ====================

    private static void eqB(String tag, boolean a, boolean b) {
        check(tag + " 一致（" + a + "）", a == b, "State=" + a + " config=" + b);
    }

    private static void eqI(String tag, int a, int b) {
        check(tag + " 一致（" + a + "）", a == b, "State=" + a + " config=" + b);
    }

    private static void eqF(String tag, float a, float b) {
        boolean same = Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
        check(tag + " 一致（逐位 " + a + "/0x" + Integer.toHexString(Float.floatToRawIntBits(a)) + "）",
                same, "State=0x" + Integer.toHexString(Float.floatToRawIntBits(a))
                        + " config=0x" + Integer.toHexString(Float.floatToRawIntBits(b)));
    }

    private static void eqS(String tag, String a, String b) {
        check(tag + " 一致（" + a + "）", a != null && a.equals(b), "State=" + a + " config=" + b);
    }

    private static void eqE(String tag, NeovideConfig.TrailMode a, NeovideConfig.TrailMode b) {
        check(tag + " 一致（" + a + "）", a == b, "State=" + a + " config=" + b);
    }

    private static String fieldAsString(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            if (v instanceof Float) {
                float fv = (Float) v;
                return Float.toString(fv);
            }
            return String.valueOf(v);
        } catch (Exception e) {
            return "<ERR " + e + ">";
        }
    }

    private static String arr(float[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(a[i]);
        }
        return sb.append("]").toString();
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 22) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static void failOnce(String msg) {
        fail++;
        System.out.println("    FAIL " + msg);
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + (detail == null || detail.isEmpty() ? "" : "   " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }
}
