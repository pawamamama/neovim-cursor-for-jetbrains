import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.util.List;

/**
 * V-SEP —— F2 分隔符迁移与名称净化的<b>边界</b>探针（第二轮审查）。
 *
 * <p>只测 {@link CustomPresets} 的纯函数层 + 真实平台 XmlSerializer 往返。
 * 每条断言声明的是「用户能感知的正确行为」，FAIL 即为一条 finding。
 */
public final class VSep {

    static final char NSEP = '\u241F';      // 新：名称/快照分隔
    static final char ESEP = '\u241E';      // 新：条目分隔
    static final char ONSEP = '\u0001';     // 旧
    static final char OESEP = '\u0002';     // 旧

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-SEP：F2 分隔符迁移 / 名称净化的边界 ===");
        V0Env.install();

        legacyReadable();
        mixedBothWays();
        putSelfConsistency();
        allSeparatorName();
        renameCollision();
        xmlAttributeSafe();
        extremeInputs();
        snapshotWithSeparator();
        leadingSpaceName();
        uiConsequence();

        System.out.println();
        System.out.printf("=== V-SEP 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        System.exit(fail > 0 ? 1 : 0);
    }

    // ---------------------------------------------------------------- S-1 / S-2

    /** 老用户数据（只有 U+0001/U+0002）必须仍能读出来。 */
    private static void legacyReadable() {
        System.out.println();
        System.out.println("--- S-1/S-2 老数据可读 + 写一次即迁移 ---");
        String legacy = "老A" + ONSEP + "a=1;b=2" + OESEP + "老B" + ONSEP + "a=3;b=4";
        List<CustomPresets.Entry> e = CustomPresets.parse(legacy);
        check("S-1 纯旧分隔符数据读出 2 条", e.size() == 2,
                "实测=" + e.size() + " 名字=" + names(e));
        check("S-1b 旧数据的名字与快照都没被黏连",
                e.size() == 2 && "老A".equals(e.get(0).name) && "a=1;b=2".equals(e.get(0).snapshot)
                        && "老B".equals(e.get(1).name),
                e.size() == 2 ? (e.get(0).name + " / " + e.get(1).name) : "条数不足");

        // 读出来后 put 一次（= 用户改了任何一个参数，afterPendingEdited 必然触发 put）
        String migrated = CustomPresets.put(legacy, "老B", "a=9;b=9");
        check("S-2 put 一次后整串不再含旧分隔符（完成迁移）",
                migrated.indexOf(ONSEP) < 0 && migrated.indexOf(OESEP) < 0,
                "含U+0001=" + (migrated.indexOf(ONSEP) >= 0)
                        + " 含U+0002=" + (migrated.indexOf(OESEP) >= 0));
        List<CustomPresets.Entry> m = CustomPresets.parse(migrated);
        check("S-2b 迁移后仍是 2 条，老A 的快照原样保留",
                m.size() == 2 && "a=1;b=2".equals(CustomPresets.get(migrated, "老A")),
                "条数=" + m.size() + " 老A=" + CustomPresets.get(migrated, "老A"));
        check("S-2c 迁移后改为写入的新快照生效",
                "a=9;b=9".equals(CustomPresets.get(migrated, "老B")),
                String.valueOf(CustomPresets.get(migrated, "老B")));
    }

    // ---------------------------------------------------------------- S-3 / S-4

    /** 同一条数据里混用新旧分隔符：不应静默丢条目。 */
    private static void mixedBothWays() {
        System.out.println();
        System.out.println("--- S-3/S-4 新旧分隔符混用 ---");
        // ③ 旧名称分隔 + 新条目分隔
        String m1 = "旧名" + ONSEP + "a=1" + ESEP + "新名" + NSEP + "a=2";
        List<CustomPresets.Entry> r1 = CustomPresets.parse(m1);
        check("S-3 混用（旧分隔的条目 + 新分隔的条目）应读出 2 条",
                r1.size() == 2, "实测=" + r1.size() + " 名字=" + names(r1));

        // ④ 新名称分隔 + 旧条目分隔
        String m2 = "新名" + NSEP + "a=1" + OESEP + "旧名" + ONSEP + "a=2";
        List<CustomPresets.Entry> r2 = CustomPresets.parse(m2);
        check("S-4 混用（新分隔的条目 + 旧分隔的条目）应读出 2 条",
                r2.size() == 2, "实测=" + r2.size() + " 名字=" + names(r2));
    }

    // ---------------------------------------------------------------- S-5

    /**
     * S-5（<b>按 0.9.10 的「入口拒绝」设计重写</b>）。
     *
     * <p><b>为什么要改这些断言</b>：最初实现是「{@code format} 里把非法字符替换成空格再 trim」，
     * 于是 S-5 断言「名字含分隔符也能存进去，只是名字被改过」。
     *
     * <p>但那条设计本身就是错的（第二轮审查 S-5/S-11b 实测）：
     * <b>静默改名会让内存名与落盘名不一致</b> ——
     * {@code put("X")} 之后 {@code has("X") == false}，用户点了「新建」却发现下拉里
     * 出现一个改过名的预设，按原名字再也取不回来。
     *
     * <p>现在改为<b>入口拒绝</b>：{@link CustomPresets#isValidName} 为 false 时
     * {@code put} 什么都不做（返回原串）。于是正确的断言是：
     * <b>非法名被拒绝、表里不新增任何条目、而且不产生改名的替身</b>。
     * 合法名（中文 / 空格 / 分号 / 等号 / 前导空格）则必须原样存原样取。
     */
    private static void putSelfConsistency() {
        System.out.println();
        System.out.println("--- S-5 入口校验：非法名拒绝、合法名自反 ---");

        // 非法名（含分隔符）→ 必须被拒绝，不产生任何条目
        String raw = NSEP + "X";
        String all = CustomPresets.put("", raw, "a=1");
        check("S-5a 名字含分隔符时 put 被拒绝（表里不新增、也不产生改名替身）",
                CustomPresets.parse(all).isEmpty(),
                "条数=" + CustomPresets.parse(all).size());
        check("S-5b 被拒绝后按原名字取不到任何东西（没有改名替身）",
                CustomPresets.get(all, raw) == null
                        && CustomPresets.get(all, "X") == null,
                "get(raw)=" + CustomPresets.get(all, raw));

        // 合法名：含空格 / 前导空格 / 中文 / 分号 → 必须原样往返
        String mid = "前 后";
        String all2 = CustomPresets.put("", mid, "a=1");
        check("S-5c 合法名（含空格）原样存、原样取",
                "a=1".equals(CustomPresets.get(all2, mid)),
                "get=" + CustomPresets.get(all2, mid));

        String leading = "  A";
        String all3 = CustomPresets.put("", leading, "a=2");
        check("S-5c2 前导空格名不被 trim（内存名 == 落盘名）",
                "a=2".equals(CustomPresets.get(all3, leading)),
                "get(\"  A\")=" + CustomPresets.get(all3, leading));

        // rename：非法目标名必须失败（返回 null），且原条目不受影响
        String base = CustomPresets.put("", "甲", "a=1");
        String ren = CustomPresets.rename(base, "甲", "乙" + ESEP + "丙");
        check("S-5d rename 到非法名返回 null，原条目仍在",
                ren == null && "a=1".equals(CustomPresets.get(base, "甲")),
                "ren=" + (ren == null ? "null" : "非null")
                        + " 原名可读=" + ("a=1".equals(CustomPresets.get(base, "甲"))));
    }

    // ---------------------------------------------------------------- S-6

    /**
     * S-6（按「入口拒绝」设计重写）：全分隔符 / 空名必须被<b>明确拒绝</b>。
     *
     * <p>旧断言是「净化后变空名，不应产生空名条目，但也不该静默丢弃」——
     * 那本质上是在要求「把用户输入悄悄改成别的东西还要让人能找到」，
     * 两条要求互相矛盾。现在改为：**拒绝，并给 UI 一个可显示的原因**。
     */
    private static void allSeparatorName() {
        System.out.println();
        System.out.println("--- S-6 非法名字必须被明确拒绝（而不是偷偷改名）---");
        String only = "" + NSEP + ESEP + ONSEP + OESEP;
        check("S-6a 全分隔符名 isValidName=false", !CustomPresets.isValidName(only),
                "isValid=" + CustomPresets.isValidName(only));
        check("S-6b 全分隔符名有可显示的原因（UI 能提示用户）",
                CustomPresets.nameError(only) != null,
                "error=" + CustomPresets.nameError(only));
        String all = CustomPresets.put("", only, "a=1");
        check("S-6c 全分隔符名 put 后表里不新增条目",
                CustomPresets.parse(all).isEmpty(), "条数=" + CustomPresets.parse(all).size());

        // 空名 / 全空白名同理
        check("S-6d 空名与全空白名都被拒绝",
                !CustomPresets.isValidName("") && !CustomPresets.isValidName("   "),
                "空=" + CustomPresets.isValidName("") + " 空白=" + CustomPresets.isValidName("   "));

        // 合法边界：单字符、中文、含分号/等号
        check("S-6e 合法边界名（单字符/中文/含分号等号）都通过校验",
                CustomPresets.isValidName("A")
                        && CustomPresets.isValidName("中文名")
                        && CustomPresets.isValidName("A;B=C"),
                "");
    }

    // ---------------------------------------------------------------- S-7

    /** rename 到「净化后会撞车」的名字：必须按净化后的名字查重。 */
    private static void renameCollision() {
        System.out.println();
        System.out.println("--- S-7 rename 到净化后同名的目标 ---");
        String all = CustomPresets.put("", "A B", "a=1");
        all = CustomPresets.put(all, "旧的", "a=2");
        String ren = CustomPresets.rename(all, "旧的", "A\u241FB");
        check("S-7a rename 到净化后与已有预设同名的目标应被拒绝（返回 null）",
                ren == null, "返回值=" + (ren == null ? "null" : escape(ren)));
        if (ren != null) {
            check("S-7b 拒绝之外还必须不丢数据：两条都在",
                    CustomPresets.parse(ren).size() == 2,
                    "条数=" + CustomPresets.parse(ren).size()
                            + " 名字=[" + names(CustomPresets.parse(ren)) + "]");
        } else {
            check("S-7b （已在 S-7a 拒绝，无需再判）", true, "");
        }

        // put 的同一类问题：净化后与已有条目同名 → 只会多一条永远选不中的重复项
        String dup = CustomPresets.put(all, "A\u241FB", "a=3");
        List<CustomPresets.Entry> pe = CustomPresets.parse(dup);
        check("S-7c put 一个「净化后同名」的新名字，不应变成不可见的重复条目",
                pe.size() == 2, "条数=" + pe.size() + " 名字=[" + names(pe) + "]");
    }

    // ---------------------------------------------------------------- S-8

    /** U+241F / U+241E 在 XML 属性值中的往返（是否需要转义 / 是否被吞）。 */
    private static void xmlAttributeSafe() {
        System.out.println();
        System.out.println("--- S-8 U+241F/U+241E 在 XML 属性值里的往返 ---");
        try {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            String snap = SettingsField.snapshot(s);
            s.customAnimPresets = CustomPresets.put("", "预设甲", snap);
            s.customAnimPresets = CustomPresets.put(s.customAnimPresets, "预设乙", snap);
            s.animCustomName = "预设乙";

            org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
            String xml = new org.jdom.output.XMLOutputter().outputString(el);
            boolean rawSepInXml = xml.indexOf(NSEP) >= 0 || xml.indexOf(ESEP) >= 0;
            boolean escSepInXml = xml.contains("&#x241F;") || xml.contains("&#x241E;")
                    || xml.contains("&#9727;") || xml.contains("&#9246;");
            check("S-8a 序列化后的 XML 里两个分隔符以「原文或字符引用」之一存在（没被吞）",
                    rawSepInXml || escSepInXml,
                    "原文=" + rawSepInXml + " 字符引用=" + escSepInXml
                            + " 片段=" + snippet(xml));

            // 走一遍真正的字符串化 → 再解析回来（等价于落盘后重启）
            org.jdom.Element reparsed = new org.jdom.input.SAXBuilder()
                    .build(new java.io.StringReader(xml)).getRootElement();
            NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                    .deserialize(reparsed, NeovideCursorSettings.State.class);
            List<CustomPresets.Entry> after = CustomPresets.parse(back.customAnimPresets);
            check("S-8b 落盘字符串 → 回读后仍是 2 条",
                    after.size() == 2,
                    "回读=" + after.size() + " 名字=[" + names(after) + "]");
            check("S-8c 回读后名字逐字相等（无 U+FFFD / 无丢字）",
                    after.size() == 2 && "预设甲".equals(after.get(0).name)
                            && "预设乙".equals(after.get(1).name),
                    "名字=[" + names(after) + "]");

            // 名字里含 \n / \t（用户粘贴）：走【字符串 → SAX → 反序列化】——
            // 这条才真正经历 XML 属性值规范化（\t / \n 在属性里会被 SDK 折叠成空格），
            // 只 serialize→deserialize 是绕过规范化，测不出问题。
            NeovideCursorSettings.State t = new NeovideCursorSettings.State();
            t.customAnimPresets = CustomPresets.put("", "换\n行\t制表", snap);
            org.jdom.Element te = com.intellij.util.xmlb.XmlSerializer.serialize(t);
            String txml = new org.jdom.output.XMLOutputter().outputString(te);
            org.jdom.Element tre = new org.jdom.input.SAXBuilder()
                    .build(new java.io.StringReader(txml)).getRootElement();
            NeovideCursorSettings.State tback = com.intellij.util.xmlb.XmlSerializer
                    .deserialize(tre, NeovideCursorSettings.State.class);
            List<CustomPresets.Entry> ta = CustomPresets.parse(tback.customAnimPresets);
            check("S-8d 名字含 \n / \t 时经「字符串→SAX→反序列化」条数不丢",
                    ta.size() == 1, "条数=" + ta.size() + " 落盘=" + escape(tback.customAnimPresets));
            check("S-8e 名字含 \n / \t 时名字逐字不变（属性值规范化不得改写它）",
                    ta.size() == 1 && "换\n行\t制表".equals(ta.get(0).name),
                    "名字=[" + escape(ta.isEmpty() ? null : ta.get(0).name) + "]"
                            + " XML片段=" + escape(attrOf(txml)));
        } catch (Throwable ex) {
            check("S-8 XML 往返", false, "抛异常：" + ex);
        }
    }

    // ---------------------------------------------------------------- S-9

    /** 极端输入不抛异常。 */
    private static void extremeInputs() {
        System.out.println();
        System.out.println("--- S-9 极端输入不抛异常 ---");
        boolean ok = true;
        String detail = "";
        try {
            CustomPresets.parse(null);
            CustomPresets.parse("");
            CustomPresets.parse("" + NSEP);
            CustomPresets.parse("" + ESEP);
            CustomPresets.parse("" + OESEP + "" + ONSEP);
            CustomPresets.parse(NSEP + "a=1" + ESEP + ESEP + NSEP + "b=2");   // 空条目
            CustomPresets.format(null);
            CustomPresets.format(new java.util.ArrayList<CustomPresets.Entry>());
            CustomPresets.put(null, "甲", "a=1");
            CustomPresets.put("", null, "a=1");
            CustomPresets.put("", "甲", null);
            CustomPresets.remove(null, null);
            CustomPresets.rename(null, null, null);
            CustomPresets.uniqueName("", null);
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                big.append("预");
            }
            String allBig = CustomPresets.put("", big.toString(), "a=1");
            ok = CustomPresets.parse(allBig).size() == 1;
            detail = "超长名（2000 字）条数=" + CustomPresets.parse(allBig).size();
        } catch (Throwable ex) {
            ok = false;
            detail = "抛异常：" + ex;
        }
        check("S-9 空/单分隔符/超长/空参数一律不抛异常且结果可解析", ok, detail);
    }

    // ---------------------------------------------------------------- S-10

    /** 快照里含分隔符（手改 XML 可造）：整表不能被切碎到丢光。 */
    private static void snapshotWithSeparator() {
        System.out.println();
        System.out.println("--- S-10 快照里含分隔符 ---");
        String good = CustomPresets.put("", "完好", "a=1");
        String evil = good + ESEP + "坏" + NSEP + "x=1;y=2";
        List<CustomPresets.Entry> e = CustomPresets.parse(evil);
        check("S-10a 手改快照后前面的完好条目仍然可读", CustomPresets.has(evil, "完好"),
                "条数=" + e.size() + " 名字=[" + names(e) + "]");
        // 名称里的分隔符已被 sanitizeName 挡住，但快照没有被净化（format 直接 append）
        CustomPresets.Entry injected = newEntry("含分隔", "a=1" + ESEP + "b=2");
        java.util.List<CustomPresets.Entry> one = new java.util.ArrayList<>();
        one.add(injected);
        String formatted = CustomPresets.format(one);
        check("S-10b format 不净化快照 → 一条含分隔符的快照会变成两条",
                CustomPresets.parse(formatted).size() <= 1,
                "落盘=" + escape(formatted) + " 解析条数=" + CustomPresets.parse(formatted).size());
    }

    // ---------------------------------------------------------------- S-11

    /** format 净化时会 trim 名字：前导/尾随空格被吃掉。 */
    private static void leadingSpaceName() {
        System.out.println();
        System.out.println("--- S-11 名字带前导空格（XML 手改/粘贴）---");
        String legacy = " A" + ONSEP + "a=1";     // 老数据里名字带前导空格
        List<CustomPresets.Entry> e = CustomPresets.parse(legacy);
        check("S-11a 老数据里的前导空格名字能原样读出",
                e.size() == 1 && " A".equals(e.get(0).name),
                "条数=" + e.size() + " 名字=[" + (e.isEmpty() ? "" : e.get(0).name) + "]");
        String migrated = CustomPresets.put(legacy, " B", "a=2");
        boolean lost = CustomPresets.get(migrated, " A") == null;
        check("S-11b 一旦写一次，前导空格名字被 trim → 按原名再也取不到",
                !lost,
                "get(\" A\")=" + CustomPresets.get(migrated, " A")
                        + " 落盘名字=[" + names(CustomPresets.parse(migrated)) + "]");
    }

    // ---------------------------------------------------------------- S-12

    /**
     * S-12（按「入口拒绝」设计重写）：面板上的可感知后果。
     *
     * <p>旧版本测的是「名字被净化后还能在 UI 里找到」——
     * 那是旧设计的自洽性检查。新设计下非法名在入口就被拒，
     * 所以真正要测的是两件相反的事：
     * <ol>
     *   <li><b>合法名</b>：建完之后下拉里能选中、能删掉（这是用户日常路径）；</li>
     *   <li><b>非法名</b>：面板给出可显示的错误，且表里不出现改名替身。</li>
     * </ol>
     */
    private static void uiConsequence() {
        System.out.println();
        System.out.println("--- S-12 面板可感知后果（合法名可建可删 / 非法名被拒并提示）---");
        try {
            NeovideCursorSettings.State st = V0Env.svc.getState();
            String snap = SettingsField.snapshot(st);

            // ---- ① 合法名：建 → 下拉可见 → 可删除 ----
            String good = "我的预设";
            st.customAnimPresets = CustomPresets.put("", good, snap);
            st.animCustomName = good;

            NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
            VUi ui = VUi.build(conf);
            conf.reset();
            conf.refreshPresetDisplayForProbe();

            com.intellij.openapi.ui.ComboBox<String> combo = conf.animPresetComboForProbe();
            boolean itemThere = false;
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (good.equals(combo.getItemAt(i))) {
                    itemThere = true;
                }
            }
            check("S-12a 合法名建完后在下拉里出现", itemThere,
                    "项数=" + combo.getItemCount());
            String selected = combo.getSelectedIndex() >= 0
                    ? combo.getItemAt(combo.getSelectedIndex()) : "<无>";
            check("S-12b 下拉当前项指向这个自定义预设（不掉回内置）",
                    good.equals(selected),
                    "当前项=[" + selected + "] animCustomName=["
                            + escape(conf.pendingForProbe().animCustomName) + "]");
            check("S-12c 内存 animCustomName 与落盘名字一致",
                    good.equals(conf.pendingForProbe().animCustomName),
                    "内存=[" + escape(conf.pendingForProbe().animCustomName) + "]");

            int before = CustomPresets.parse(conf.pendingForProbe().customAnimPresets).size();
            String after = CustomPresets.remove(conf.pendingForProbe().customAnimPresets,
                    conf.pendingForProbe().animCustomName);
            int afterN = CustomPresets.parse(after).size();
            check("S-12d 删除当前预设后表里真的少一条", afterN == before - 1,
                    "前=" + before + " 后=" + afterN);

            // ---- ② 非法名：被拒绝 + 有可显示原因 ----
            String bad = "我的" + NSEP + "预设";
            String err = CustomPresets.nameError(bad);
            String table2 = CustomPresets.put("", bad, snap);
            check("S-12e 非法名有可显示的错误原因（面板可据此提示用户）",
                    err != null && !err.isEmpty(), "error=" + err);
            check("S-12f 非法名不进表，也不会以改名后的样子出现",
                    CustomPresets.parse(table2).isEmpty()
                            && !CustomPresets.has(table2, "我的 预设"),
                    "条数=" + CustomPresets.parse(table2).size());
        } catch (Throwable t) {
            check("S-12 面板后果", false, "抛异常：" + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static CustomPresets.Entry newEntry(String name, String snap) {
        // Entry 的构造器是包级私有：用 put+parse 造一条，再把快照改成含分隔符的，
        // 通过反射拿不到就直接拼串（format 的输入只需 name/snapshot 两个字段）。
        String all = CustomPresets.put("", name, "PLACEHOLDER");
        // 用反射构造一个 Entry 实例（只为驱动 format 的快照路径）
        try {
            java.lang.reflect.Constructor<CustomPresets.Entry> c =
                    CustomPresets.Entry.class.getDeclaredConstructor(String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(name, snap);
        } catch (Throwable t) {
            // 退化：拿 parse 出来的条目（快照占位），用不了就返回 null 由调用方兜
            List<CustomPresets.Entry> l = CustomPresets.parse(all);
            return l.isEmpty() ? null : l.get(0);
        }
    }

    private static String names(List<CustomPresets.Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (CustomPresets.Entry e : list) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(e.name);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == NSEP || c == ESEP) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String attrOf(String xml) {
        if (xml == null) {
            return "null";
        }
        int i = xml.indexOf("customAnimPresets");
        if (i < 0) {
            return "未找到属性";
        }
        int q = xml.indexOf('"', i);
        int q2 = xml.indexOf('"', q + 1);
        return q2 > q ? xml.substring(q + 1, Math.min(q2, q + 60)) : xml.substring(i, Math.min(xml.length(), i + 80));
    }

    private static String snippet(String xml) {
        if (xml == null) {
            return "null";
        }
        int i = xml.indexOf("customAnimPresets");
        if (i < 0) {
            return xml.length() > 160 ? xml.substring(0, 160) + "…" : xml;
        }
        int end = Math.min(xml.length(), i + 120);
        return escape(xml.substring(i, end));
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}
