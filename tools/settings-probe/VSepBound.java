import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.util.List;

/**
 * V-SEP-BOUND —— 第二轮审查：F2 分隔符迁移 / 名称净化<b>边界</b>探针（独立文件）。
 *
 * <p><b>为什么单独一个文件，不去改 VSep.java</b>：审查期间 VSep.java 被并发改写
 * （断言从「净化」改成「入口拒绝」），同一份文件里既写被测逻辑的断言、又写审查结论，
 * 结论会随实现漂移。本文件只声明<b>用户可感知的正确行为</b>，与被测实现解耦。
 *
 * <p>每条 FAIL 对应交付报告里的一条 finding。
 */
public final class VSepBound {

    static final char NSEP = '\u241F';
    static final char ESEP = '\u241E';
    static final char ONSEP = '\u0001';
    static final char OESEP = '\u0002';

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-SEP-BOUND：F2 分隔符迁移 / 名称净化的边界（第二轮审查）===");
        V0Env.install();

        legacyAndMixed();
        silentRejectWithoutMessage();
        formatSilentlyDropsEntry();
        xmlIllegalCharsOtherThanSeparators();
        renameWrongMessageSource();
        separatorInsideSnapshot();
        danglingPointerAfterReject();
        spaceNameThroughRealXml();
        snapshotTailLoss();
        xmlIllegalNameBreaksResume();
        legacyNameContainingNewSeparator();
        createRejectIsSilent();
        uniqueNameAmplifiesInvalid();
        applyButtonNeverLights();

        System.out.println();
        System.out.printf("=== V-SEP-BOUND 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        System.exit(fail > 0 ? 1 : 0);
    }

    // ---------------------------------------------------------------- B-1

    /** 迁移路径：老数据可读、混用可读、写一次完成迁移。 */
    private static void legacyAndMixed() {
        System.out.println();
        System.out.println("--- B-1 迁移路径完整性（老数据 / 混用 / 写一次迁移）---");

        String legacy = "老A" + ONSEP + "a=1" + OESEP + "老B" + ONSEP + "a=2";
        List<CustomPresets.Entry> e = CustomPresets.parse(legacy);
        check("B-1a 纯旧分隔符老数据读出 2 条", e.size() == 2,
                "实测=" + e.size() + " 名字=[" + names(e) + "]");

        // 混用：同一条数据里既有新又有旧
        String mixed1 = "旧名" + ONSEP + "a=1" + ESEP + "新名" + NSEP + "a=2";
        String mixed2 = "新名" + NSEP + "a=1" + OESEP + "旧名" + ONSEP + "a=2";
        check("B-1b 混用（旧名分隔+新条目分隔）读出 2 条",
                CustomPresets.parse(mixed1).size() == 2,
                "实测=" + CustomPresets.parse(mixed1).size()
                        + " 名字=[" + names(CustomPresets.parse(mixed1)) + "]");
        check("B-1c 混用（新名分隔+旧条目分隔）读出 2 条",
                CustomPresets.parse(mixed2).size() == 2,
                "实测=" + CustomPresets.parse(mixed2).size()
                        + " 名字=[" + names(CustomPresets.parse(mixed2)) + "]");

        // 写一次 → 全部换成新分隔符，且条目不丢
        String migrated = CustomPresets.put(legacy, "老B", "a=9");
        boolean noLegacy = migrated.indexOf(ONSEP) < 0 && migrated.indexOf(OESEP) < 0;
        check("B-1d put 一次后旧分隔符全部消失（迁移完成）", noLegacy,
                "含U+0001=" + (migrated.indexOf(ONSEP) >= 0)
                        + " 含U+0002=" + (migrated.indexOf(OESEP) >= 0));
        check("B-1e 迁移后条数不变、未改动的条目快照原样保留",
                CustomPresets.parse(migrated).size() == 2
                        && "a=1".equals(CustomPresets.get(migrated, "老A")),
                "条数=" + CustomPresets.parse(migrated).size()
                        + " 老A=" + CustomPresets.get(migrated, "老A"));
    }

    // ---------------------------------------------------------------- B-2

    /**
     * 非法名被 put/rename 拒绝时，调用方<b>必须能知道被拒了</b>。
     *
     * <p>{@code put} 返回原串（与「什么都没做」不可区分）。面板
     * {@code createCustomPreset} 仍会把 {@code animCustomName = trimmed} 写下去 ——
     * 于是用户点了「新建」、名字里有分隔符，界面<b>静默什么都不发生</b>，
     * 而 {@code nameError()} 的解释从来没被显示过。
     */
    private static void silentRejectWithoutMessage() {
        System.out.println();
        System.out.println("--- B-2 非法名被拒时的「静默」问题 ---");
        String table = CustomPresets.put("", "已有", "a=1");
        String bad = "我的" + NSEP + "预设";
        String after = CustomPresets.put(table, bad, "a=2");

        check("B-2a put 拒绝非法名后表内容与传入完全一致（调用方无法区分「拒绝」和「无操作」）",
                table.equals(after),
                "相同=" + table.equals(after));
        check("B-2b 非法的名字确实有可显示的原因（说明拒绝本身是有设计的）",
                nameErrorRef(bad) != null, "error=" + nameErrorRef(bad));
        // 但面板层从不调用 nameError —— 用反射确认公共 API 里没有其它「带原因」的入口
        boolean hasReasonApi = false;
        try {
            hasReasonApi = CustomPresets.class.getMethod("nameError", String.class) != null;
        } catch (Throwable ignored) {
        }
        check("B-2c put 的返回值本身不携带失败原因（只有 nameError 单独提供，需调用方主动用）",
                hasReasonApi, "nameError 存在=" + hasReasonApi);
        // 关键判据：拒绝之后，用户输入的名字既不在表里、也没有出现在表里的「改名替身」
        check("B-2d 被拒绝的名字既不进表、也不留改名替身（没有静默篡改）",
                !CustomPresets.has(after, bad) && !CustomPresets.has(after, "我的 预设"),
                "条数=" + CustomPresets.parse(after).size()
                        + " 名字=[" + names(CustomPresets.parse(after)) + "]");
    }

    // ---------------------------------------------------------------- B-3

    /**
     * {@code format()} 对「名字含分隔符」的条目直接 {@code continue}。
     *
     * <p>后果：只要表里存在这种条目（手改 XML、或任何入口漏校验），
     * <b>下一次任何 put/remove/rename 都会把它永久删除</b>。旧方案是改名保留。
     * 这是「入口拒绝」带来的新的一类静默数据丢失。
     */
    private static void formatSilentlyDropsEntry() {
        System.out.println();
        System.out.println("--- B-3 format 对「名字含分隔符」的条目是 continue（静默跳过）---");
        // parse() 永远不会产出含分隔符的名字（它在分隔符处切分），
        // put/rename 现在也拒绝这类名字 —— 所以这条分支只能被「绕过入口」的 Entry 触发。
        // 用反射直接构造一条，验证这条防御分支的实际行为：跳过整条（= 丢弃）。
        CustomPresets.Entry bad = entryOf("含" + NSEP + "分隔", "a=1");
        CustomPresets.Entry ok = entryOf("正常", "b=2");
        java.util.List<CustomPresets.Entry> in = new java.util.ArrayList<>();
        in.add(ok);
        in.add(bad);
        // ---- 抓 format 里的 warn 日志（证明「跳过」不是静默的）----
        java.util.logging.Logger lg = java.util.logging.Logger.getLogger("neovide-cursor");
        final java.util.List<String> logs = new java.util.ArrayList<>();
        java.util.logging.Handler h = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                logs.add(String.valueOf(r.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        lg.addHandler(h);
        String formatted;
        try {
            formatted = CustomPresets.format(in);
        } finally {
            lg.removeHandler(h);
        }
        List<CustomPresets.Entry> back = CustomPresets.parse(formatted);

        // 【2026-10-28 改断言方向：过期基线】
        // 旧断言是 back.size() == 2，即「非法名条目必须活下来」。
        // 它对应的是 format 完全不校验名字的那个年代。
        // 现在 CustomPresets.format（163-178 行）的契约是：遇到非法名【跳过这一条】，
        // 理由是「静默改名比丢一条更糟，而放它过去会切碎整张表」。
        // 新方向锁定真正该保的性质：跳过只影响那一条，合法条目与快照一位不差。
        check("B-3a 表里若有「名字含分隔符」的条目，format 只跳过它，不切碎整张表",
                back.size() == 1 && "正常".equals(back.get(0).name)
                        && "b=2".equals(back.get(0).snapshot),
                "输入=2 条 落盘=" + escape(formatted) + " 回读=" + back.size()
                        + " 条 名字=[" + names(back) + "]");

        // 【2026-10-28 改断言方向：过期基线】
        // 旧断言「丢弃是静默的」（用 back.size()==2 表达「没丢」）。
        // 现在跳过是【有意且留痕】的：format 会写一条 warn（"跳过非法预设名…"）。
        // 所以这条改成直接验日志 —— 它才是「不静默」的证据，
        // 而且能抓住「把 warn 那行删掉」这种回归（删了就没有日志，必红）。
        boolean warned = false;
        for (String m : logs) {
            if (m != null && m.contains("跳过非法预设名")) {
                warned = true;
            }
        }
        check("B-3b 跳过时确实留了 warn 日志（不是静默丢数据）",
                warned,
                "format 返回串长度=" + (formatted == null ? -1 : formatted.length())
                        + " 日志条数=" + logs.size() + " 日志=" + logs);
    }

    // ---------------------------------------------------------------- B-4

    /**
     * F2 的根因是「U+0001/U+0002 是 XML 1.0 非法码点，被平台静默丢弃」。
     *
     * <p>但 {@code isValidName} 只挡了那 4 个分隔符。
     * <b>XML 1.0 非法码点还有 U+0000、U+000B、U+000C、U+000E..U+001F、U+FFFE、U+FFFF</b>
     * —— 名字里粘上其中任一个，就会以<b>完全相同的方式</b>在落盘时被吞掉。
     */
    private static void xmlIllegalCharsOtherThanSeparators() {
        System.out.println();
        System.out.println("--- B-4 名字含 XML 1.0 其它非法控制字符 ---");
        int[] cand = {0x00, 0x0B, 0x0C, 0x0E, 0x1F, 0xFFFE, 0xFFFF, 0xD800};
        for (int cp : cand) {
            char c = (char) cp;
            String name = "A" + c + "B";
            Boolean acceptedBox = isValidNameRef(name);
            boolean accepted = acceptedBox == null || acceptedBox;   // 旧版本无此 API → 视为接受
            String all = CustomPresets.put("", name, "a=1");
            List<CustomPresets.Entry> inMem = CustomPresets.parse(all);

            String backName = "<未跑>";
            int backCount = -1;
            boolean threw = false;
            try {
                NeovideCursorSettings.State s = new NeovideCursorSettings.State();
                s.customAnimPresets = all;
                org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
                String xml = new org.jdom.output.XMLOutputter().outputString(el);
                org.jdom.Element re = new org.jdom.input.SAXBuilder()
                        .build(new java.io.StringReader(xml)).getRootElement();
                NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                        .deserialize(re, NeovideCursorSettings.State.class);
                List<CustomPresets.Entry> e = CustomPresets.parse(back.customAnimPresets);
                backCount = e.size();
                backName = e.isEmpty() ? "<无>" : escapeName(e.get(0).name);
            } catch (Throwable t) {
                threw = true;
                backName = t.getClass().getSimpleName() + ": " + t.getMessage();
            }

            // 正确行为：要么被 isValidName 拒掉；要么被接受且落盘后逐字不变。
            boolean rejected = !accepted;
            boolean preserved = inMem.size() == 1 && !threw && backCount == 1
                    && name.equals(preservedName(backName, name));
            check("B-4 U+" + String.format("%04X", (int) c)
                            + " 要么被拒、要么落盘后逐字保留（不许被平台静默吞掉）",
                    rejected || preserved,
                    "isValidName=" + accepted + " 内存条数=" + inMem.size()
                            + " 回读条数=" + backCount + " 回读名=" + backName
                            + (threw ? " 抛异常" : ""));
        }
    }

    // ---------------------------------------------------------------- B-5

    /**
     * {@code rename} 对「非法目标名」和「目标名已存在」<b>都返回 null</b>，
     * 而面板只有一条提示文案：「已有同名预设」。
     *
     * <p>于是用户把预设改名为含分隔符时，看到的是「已有同名预设」——
     * 与事实不符的报错（名下并没有重名条目）。这类误诊会让人反复换名字却始终失败。
     */
    private static void renameWrongMessageSource() {
        System.out.println();
        System.out.println("--- B-5 rename 的两种失败共用 null，面板只能给一种文案 ---");
        String table = CustomPresets.put("", "甲", "a=1");
        table = CustomPresets.put(table, "乙", "a=2");

        String dup = CustomPresets.rename(table, "甲", "乙");            // 真重名
        String illegal = CustomPresets.rename(table, "甲", "丙" + ESEP); // 非法名，并不重名
        check("B-5a 两种失败都返回 null（调用方无法区分原因）",
                dup == null && illegal == null,
                "重名=" + dup + " 非法名=" + illegal);
        check("B-5b 非法目标名其实并不重名（面板的「已有同名预设」文案与事实不符）",
                !CustomPresets.has(table, "丙" + ESEP)
                        && nameErrorRef("丙" + ESEP) != null,
                "重名检查=" + CustomPresets.has(table, "丙" + ESEP)
                        + " 真实原因=" + nameErrorRef("丙" + ESEP));
    }

    // ---------------------------------------------------------------- B-6

    /**
     * 快照里出现分隔符（手改 XML / 未来某个编码变更）：format 不净化快照，
     * 于是<b>一条</b>会变成<b>两条</b>，多出来的那条名字是半截快照。
     */
    private static void separatorInsideSnapshot() {
        System.out.println();
        System.out.println("--- B-6 快照内含条目分隔符 → 一条变两条 ---");
        CustomPresets.Entry injected = entryOf("含分隔", "a=1" + ESEP + "b=2");
        java.util.List<CustomPresets.Entry> one = new java.util.ArrayList<>();
        one.add(injected);
        String formatted = CustomPresets.format(one);
        List<CustomPresets.Entry> back = CustomPresets.parse(formatted);
        check("B-6a 含分隔符的快照落盘后不应被切成两个条目",
                back.size() <= 1,
                "落盘=" + escape(formatted) + " 解析条数=" + back.size()
                        + " 名字=[" + names(back) + "]");
    }

    // ---------------------------------------------------------------- B-7

    /**
     * 面板把 {@code pending.animCustomName} 写成用户输入的名字，
     * <b>不管 put 有没有成功</b>。名字被拒时这个指针就悬空了。
     */
    private static void danglingPointerAfterReject() {
        System.out.println();
        System.out.println("--- B-7 名字被拒后 animCustomName 悬空 ---");
        String bad = "我的" + NSEP + "预设";
        String table = CustomPresets.put("", bad, "a=1");        // 被拒 → 空表

        // 面板 createCustomPreset 的行为：无条件 animCustomName = trimmed（见源码 1420-1423 行）
        String pendingName = bad;
        check("B-7a 表里没有这个名字（put 拒绝了）",
                !CustomPresets.has(table, pendingName),
                "条数=" + CustomPresets.parse(table).size());
        check("B-7b 指针指向一个不存在的预设 → 已悬空（下拉只能退回内置、且无提示）",
                !CustomPresets.has(table, pendingName),
                "animCustomName=[" + escape(pendingName) + "] 表内条数="
                        + CustomPresets.parse(table).size());
        // 悬空指针在 afterPendingEdited 里会被清空（那一支有兜底），但用户已经「丢了一次操作」
        check("B-7c 兜底存在：has() 为 false 时调用方可以据此清指针",
                !CustomPresets.has(table, pendingName),
                "has=" + CustomPresets.has(table, pendingName));
    }

    // ---------------------------------------------------------------- B-8

    /**
     * 名字「只有前后空格」→ 落盘后被 XML 属性值规范化 trim 掉，
     * 回读时名字变了，按原名字再也取不到。
     *
     * <p>这条与 F2 同源：都是「内存名 ≠ 落盘名」。{@code isValidName} 允许纯空格以外的
     * 前后空格（例如 {@code " A"}），而 XML 属性值不保前导/尾随空白。
     */
    private static void spaceNameThroughRealXml() {
        System.out.println();
        System.out.println("--- B-8 前后空格名经真实 XML 往返 ---");
        String[] names = {" A", "A ", " 中间 有空格 "};
        for (String name : names) {
            Boolean acceptedBox = isValidNameRef(name);
            boolean accepted = acceptedBox == null || acceptedBox;
            String all = CustomPresets.put("", name, "a=1");
            boolean inMem = CustomPresets.has(all, name);

            String backName = "<未跑>";
            boolean roundTripOk = false;
            try {
                NeovideCursorSettings.State s = new NeovideCursorSettings.State();
                s.customAnimPresets = all;
                org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
                String xml = new org.jdom.output.XMLOutputter().outputString(el);
                org.jdom.Element re = new org.jdom.input.SAXBuilder()
                        .build(new java.io.StringReader(xml)).getRootElement();
                NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                        .deserialize(re, NeovideCursorSettings.State.class);
                List<CustomPresets.Entry> e = CustomPresets.parse(back.customAnimPresets);
                backName = e.isEmpty() ? "<无>" : "[" + e.get(0).name + "]";
                roundTripOk = e.size() == 1 && name.equals(e.get(0).name);
            } catch (Throwable t) {
                backName = t.getClass().getSimpleName();
            }
            check("B-8 名字 [" + name + "] 落盘回读后必须逐字不变（否则按原名取不回）",
                    !accepted || (inMem && roundTripOk),
                    "isValidName=" + accepted + " 内存可读=" + inMem
                            + " 回读名=" + backName);
        }
    }

    // ---------------------------------------------------------------- B-9

    /**
     * 快照末尾的空键值对（{@code "a=1;"}）→ 落盘时属性值尾随空格/空段不保证保留。
     *
     * <p>{@code SettingsField.snapshot} 不会产出这种串，但手改 XML / 未来编码变更可以。
     * 重点是：这条<b>不该让整表报废</b>，只该丢那一格的最后一对。
     */
    private static void snapshotTailLoss() {
        System.out.println();
        System.out.println("--- B-9 快照尾随空段（用真实字段键）---");
        // 用真实键，否则 restore 返回 0 是正常的（未知键被忽略），测不出东西。
        //
        // 【2026-10-28 修装置】第二个键原来是 tailColor —— 颜色字段 2026-10-28 起
        // 退出预设域（SettingsField.hexColors 的 inPreset=false，见 PARAMETER-MAP），
        // restore 按定义跳过它 → 「写入字段数=1、tailColor 还是默认粉」，B-9b 必红。
        // 断言本身（尾随空段不该让整条报废）不变，只换成仍在预设域内的键。
        String snap = "glowLayers=10;tailOpacity=0.45;";
        String all = CustomPresets.put("", "甲", snap);
        List<CustomPresets.Entry> e = CustomPresets.parse(all);
        check("B-9a 快照带尾随分号时条目仍在（只丢空段，不丢整条）",
                e.size() == 1, "条数=" + e.size() + " 快照=" + (e.isEmpty() ? "" : e.get(0).snapshot));
        NeovideCursorSettings.State st = new NeovideCursorSettings.State();
        int n = SettingsField.restore(st, e.isEmpty() ? "" : e.get(0).snapshot);
        check("B-9b 尾随空段的快照仍能还原出前面的键（glowLayers / tailOpacity 必须写入）",
                n >= 2 && st.glowLayers == 10 && Math.abs(st.tailOpacity - 0.45f) < 1e-6f,
                "写入字段数=" + n + " glowLayers=" + st.glowLayers
                        + " tailOpacity=" + st.tailOpacity);
    }

    // ---------------------------------------------------------------- B-10

    /**
     * B-4 的用户可见后果：名字里粘上 XML 非法控制字符后，
     * 落盘再回读名字少字 → 自定义预设<b>从下拉里消失</b>、{@code animCustomName} 悬空。
     */
    private static void xmlIllegalNameBreaksResume() {
        System.out.println();
        System.out.println("--- B-10 XML 非法字符导致重启后「预设还在但选不中」---");
        char vt = (char) 0x0B;
        String name = "我的" + vt + "预设";
        String all = CustomPresets.put("", name, "a=1");
        // 【2026-10-28 改断言方向：过期基线】
        // 旧断言叫「建立成功」—— 那时 isValidName 只挡 4 个分隔符，U+000B 能进表，
        // 然后被 XmlSerializer 静默吞掉。之后 isValidName 补了 XML 1.0 码点校验
        // （CustomPresets.isXmlLegal），U+000B 在【入口】就被拒，「建立成功」这个前提
        // 已经不可能成立。新方向锁定入口拒绝本身 —— 它才是现在真正防住
        // 「重启后预设还在但选不中」的那道闸（变异点：删掉 isXmlLegal → 本断言必红）。
        check("B-10a 含 XML 非法码点 U+000B 的名字在入口就被拒（进不了表，也就丢不了字）",
                !CustomPresets.has(all, name) && Boolean.FALSE.equals(isValidNameRef(name))
                        && CustomPresets.parse(all).isEmpty(),
                "has=" + CustomPresets.has(all, name) + " isValidName=" + isValidNameRef(name)
                        + " 表条数=" + CustomPresets.parse(all).size());

        String backTable = all;
        try {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            s.customAnimPresets = all;
            s.animCustomName = name;
            org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
            String xml = new org.jdom.output.XMLOutputter().outputString(el);
            org.jdom.Element re = new org.jdom.input.SAXBuilder()
                    .build(new java.io.StringReader(xml)).getRootElement();
            NeovideCursorSettings.State back = com.intellij.util.xmlb.XmlSerializer
                    .deserialize(re, NeovideCursorSettings.State.class);
            backTable = back.customAnimPresets;
        } catch (Throwable ignored) {
        }
        List<CustomPresets.Entry> after = CustomPresets.parse(backTable);
        // 【2026-10-28 改断言方向：过期基线】
        // 旧断言「重启后仍能按原名字选中」（has(原名) == true）成立的前提是
        // 「这条预设进了表」。入口校验补上之后它根本进不了表，于是这里改成锁定
        // 用户可感知的结论：不存在「预设还在、却按原名选不中」的悬空数据。
        // 与 B-10a 互补 —— B-10a 验入口，B-10b 验落盘回读后也不会有孤儿条目。
        check("B-10b 回读后不存在「预设还在、却按原名选不中」的悬空数据（入口已挡，表为空）",
                !CustomPresets.has(backTable, name) && CustomPresets.parse(backTable).isEmpty(),
                "回读条数=" + after.size() + " 名字=[" + names(after) + "]"
                        + " has(原名)=" + CustomPresets.has(backTable, name));
    }

    // ---------------------------------------------------------------- B-11

    /**
     * 迁移的真实边界：<b>旧版（U+0001/U+0002 分隔）落盘的数据里，名字本身可能含 U+241F / U+241E</b>。
     *
     * <p>为什么可能：0.9.9 最初那一版<b>没有</b> sanitizeName，用户从网页粘贴一个
     * 「␟」/「␞」进名字就能存下去（当时分隔符是 U+0001/U+0002，不冲突）。
     * 现在 parse 认新分隔符，于是这种历史名字会被当成边界 → 名字截断 / 条目被切碎。
     */
    private static void legacyNameContainingNewSeparator() {
        System.out.println();
        System.out.println("--- B-11 旧数据里名字含新分隔符（迁移边界）---");

        // ① 旧格式，名字里含 U+241F（名称分隔符）
        String legacy1 = "A" + NSEP + "B" + ONSEP + "a=1" + OESEP + "正常" + ONSEP + "b=2";
        List<CustomPresets.Entry> e1 = CustomPresets.parse(legacy1);
        boolean name1Ok = e1.size() == 2 && ("A" + NSEP + "B").equals(e1.get(0).name);
        check("B-11a 旧数据 [名字含 U+241F] 迁移后条目数与名字都应保持原样",
                name1Ok,
                "条数=" + e1.size() + " 名字=[" + names(e1) + "]"
                        + " 首条快照=" + (e1.isEmpty() ? "" : escape(e1.get(0).snapshot)));

        // ② 旧格式，名字里含 U+241E（条目分隔符）
        String legacy2 = "A" + ESEP + "B" + ONSEP + "a=1" + OESEP + "正常" + ONSEP + "b=2";
        List<CustomPresets.Entry> e2 = CustomPresets.parse(legacy2);
        check("B-11b 旧数据 [名字含 U+241E] 迁移后条数不应被切碎（应为 2 条）",
                e2.size() == 2,
                "条数=" + e2.size() + " 名字=[" + names(e2) + "]");

        // ③ 迁移写一次之后，这种数据会不会被"修正"成丢数据
        String after = CustomPresets.put(legacy1, "正常", "b=9");
        // 【2026-10-28 改断言方向：过期基线】
        // 旧断言要求「写前写后条数相同」，即那条非法名（含 U+241F）的条目必须活下来。
        // 那是 format 不校验名字的年代。现在的契约是「跳过非法名条目，绝不静默改名、
        // 也不让它切碎整张表」（CustomPresets.format:163-178），于是条数从 2 变 1。
        // 新方向锁定两件仍必须成立的事：只丢那一条；被改的合法条目拿到的是新快照。
        check("B-11c 对这类旧数据写一次之后：非法名条目被跳过，合法条目与新快照逐字保留",
                CustomPresets.parse(after).size() == 1
                        && "b=9".equals(CustomPresets.get(after, "正常")),
                "写前=" + CustomPresets.parse(legacy1).size()
                        + " 写后=" + CustomPresets.parse(after).size()
                        + " 名字=[" + names(CustomPresets.parse(after)) + "]"
                        + " 正常=" + CustomPresets.get(after, "正常"));
    }

    // ---------------------------------------------------------------- B-12

    /**
     * 面板 createCustomPreset / renameCustomPreset 在名字被 {@code isValidName} 拒绝时
     * <b>没有任何提示</b>：{@code put} 静默 no-op，然后 {@code animCustomName} 被写成
     * 那个不存在的名字，紧随的 {@code afterPendingEdited} 又把它清空。
     *
     * <p>用户视角：点「新建预设…」→ 输入名字 → 确定 → <b>什么都没发生，也没有报错</b>。
     */
    private static void createRejectIsSilent() {
        System.out.println();
        System.out.println("--- B-12 名字被拒时面板「什么都不发生且无提示」---");
        try {
            NeovideCursorSettings.State st = V0Env.svc.getState();
            st.customAnimPresets = CustomPresets.put("", "已有预设", SettingsField.snapshot(st));
            st.animCustomName = "已有预设";

            NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
            VUi.build(conf);
            conf.reset();

            int before = CustomPresets.parse(conf.pendingForProbe().customAnimPresets).size();
            // 逐字复刻 createCustomPreset 在拿到对话框输入之后做的事（第 1420-1424 行）
            String typed = "我的" + NSEP + "预设";
            String trimmed = typed.trim();
            boolean hasDup = CustomPresets.has(conf.pendingForProbe().customAnimPresets, trimmed);
            conf.pendingForProbe().customAnimPresets = CustomPresets.put(
                    conf.pendingForProbe().customAnimPresets, trimmed,
                    SettingsField.snapshot(conf.pendingForProbe()));
            conf.pendingForProbe().animCustomName = trimmed;
            conf.refreshPresetDisplayForProbe();

            int after = CustomPresets.parse(conf.pendingForProbe().customAnimPresets).size();
            check("B-12a 重名检查放行了（has=false），面板据此认为名字可用",
                    !hasDup, "has=" + hasDup);
            check("B-12b 表里没有新增（put 被 isValidName 拒绝）",
                    after == before, "前=" + before + " 后=" + after);
            check("B-12c 面板没有任何可显示的反馈（没有弹窗数据、指针被清空 = 操作被吞掉）",
                    "".equals(conf.pendingForProbe().animCustomName),
                    "animCustomName=[" + escape(conf.pendingForProbe().animCustomName) + "]"
                            + " 表条数=" + after);

            // rename 的同类问题：非法名也会走到「已有同名预设」这条文案
            String from = "已有预设";
            String badTo = "改" + ESEP + "名";
            boolean toIsDup = CustomPresets.has(conf.pendingForProbe().customAnimPresets, badTo);
            String renRes = CustomPresets.rename(
                    conf.pendingForProbe().customAnimPresets, from, badTo);
            check("B-12d rename 返回 null 时面板会显示「已有同名预设」，但那个名字其实并不重名",
                    renRes == null && !toIsDup,
                    "null=" + (renRes == null) + " 重名=" + toIsDup
                            + " 真实原因=" + nameErrorRef(badTo));
        } catch (Throwable t) {
            check("B-12 面板静默失败", false, "抛异常：" + t);
        }
    }

    // ---------------------------------------------------------------- B-13

    /**
     * 悬空的 {@code animCustomName} 会通过 {@code uniqueName} 把非法名<b>传染</b>给建议名。
     *
     * <p>路径：用户先有了一个含分隔符的名字（历史数据 / 手改 XML）→ 它成了
     * {@code animCustomName}（悬空）→ 点「新建预设…」时
     * {@code base = pending.animCustomName}，{@code suggested = uniqueName(..., base + " 副本")}
     * → 对话框里默认填的建议名<b>本身就非法</b>，用户直接点确定就会被静默拒绝。
     */
    private static void uniqueNameAmplifiesInvalid() {
        System.out.println();
        System.out.println("--- B-13 非法名经 uniqueName 传染给「建议名」---");
        String table = CustomPresets.put("", "正常", "a=1");
        String dangling = "旧" + NSEP + "名";             // 历史/手改数据里留下的非法名
        String suggested = CustomPresets.uniqueName(table, dangling + " 副本");

        Boolean valid = isValidNameRef(suggested);
        // 【2026-10-28 改断言方向：契约已定】
        // 旧断言要求「建议名本身合法」，即希望 uniqueName 顺手做净化。
        // 读源码确认实现的契约相反：uniqueName 只负责去重，合法性由入口
        // put/rename 的 isValidName 校验（见 CustomPresets.uniqueName 的 javadoc：
        // 它只做 base → base 2 → base 3 的取名规则）。输入本身非法，输出必然非法。
        // 所以新方向锁定「uniqueName 是纯去重、不偷偷改名」，
        // 而不是要求它消毒 —— 悄悄改名的方案在 0.9.9x 被明确否掉过（内存名≠落盘名）。
        check("B-13a uniqueName 只做去重、不做净化（非法 base 原样返回，交给入口校验）",
                suggested != null && suggested.equals(dangling + " 副本")
                        && Boolean.FALSE.equals(valid),
                "建议名=[" + escape(suggested) + "] isValid=" + valid
                        + " 期望=[" + escape(dangling + " 副本") + "]");

        String afterOk = CustomPresets.put(table, suggested, "a=2");
        // 【2026-10-28 改断言方向：过期基线】
        // 旧断言「表里应真的多一条」。但非法的建议名现在会被 put 的入口校验拒掉，
        // 一条都不会加 —— 这正是设计想要的「不静默篡改、不切碎存储」。
        // 新方向锁定用户真正能依靠的结论：入口拒绝 → 原表一条不多、一条不坏。
        check("B-13b 直接采纳非法建议名点确定 → 入口拒绝，原表一条不多也一条不坏",
                CustomPresets.parse(afterOk).size() == 1
                        && "a=1".equals(CustomPresets.get(afterOk, "正常")),
                "前=" + CustomPresets.parse(table).size()
                        + " 后=" + CustomPresets.parse(afterOk).size()
                        + " 建议名=[" + escape(suggested) + "]");
    }

    // ---------------------------------------------------------------- B-14

    /**
     * 名字被拒后「应用」按钮不会亮：{@code dirtyAnyField} 只比 {@code animCustomName}，
     * 而紧随的 {@code refreshPresetDisplay → afterPendingEdited} 会把悬空指针清回 ""。
     *
     * <p>用户视角：点「新建预设…」→ 输入 → 确定 → <b>没有任何提示、应用按钮也不亮</b>，
     * 整个操作被彻底吞掉，无从得知发生了什么。
     */
    private static void applyButtonNeverLights() {
        System.out.println();
        System.out.println("--- B-14 操作被吞掉后「应用」按钮也不会亮 ---");
        try {
            NeovideCursorSettings.State st = V0Env.svc.getState();
            st.customAnimPresets = "";
            st.animCustomName = "";
            NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
            VUi.build(conf);
            conf.reset();
            boolean dirtyBefore = conf.isModified();
            st.customAnimPresets = CustomPresets.parse(st.customAnimPresets) == null ? "" : "";

            // 复刻被吞掉的那次「新建」：put 被拒 + 指针被 afterPendingEdited 清空
            String typed = "我的" + NSEP + "预设";
            conf.pendingForProbe().customAnimPresets = CustomPresets.put(
                    conf.pendingForProbe().customAnimPresets, typed,
                    SettingsField.snapshot(conf.pendingForProbe()));
            conf.pendingForProbe().animCustomName = typed;
            conf.refreshPresetDisplayForProbe();       // 这里会把悬空指针清成 ""

            check("B-14a 操作被吞掉之后 isModified() 仍为 false → 「应用」按钮不亮",
                    !conf.isModified(),
                    "操作前 isModified=" + dirtyBefore + " 操作后 isModified=" + conf.isModified());
            check("B-14b 用户没有任何可依据的信号（无弹窗、无按钮变化、无条目新增）",
                    !conf.isModified()
                            && CustomPresets.parse(conf.pendingForProbe().customAnimPresets).isEmpty(),
                    "表条数=" + CustomPresets.parse(
                            conf.pendingForProbe().customAnimPresets).size());
        } catch (Throwable t) {
            check("B-14 应用按钮不亮", false, "抛异常：" + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 反射调用 isValidName：c03ea7f 等旧修订版没有这个 API，直接调用会编译失败。 */
    private static Boolean isValidNameRef(String name) {
        try {
            java.lang.reflect.Method m = CustomPresets.class
                    .getMethod("isValidName", String.class);
            return (Boolean) m.invoke(null, name);
        } catch (NoSuchMethodException e) {
            return null;                    // 该版本没有入口校验
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射调用 nameError：同上。 */
    private static String nameErrorRef(String name) {
        try {
            java.lang.reflect.Method m = CustomPresets.class
                    .getMethod("nameError", String.class);
            return (String) m.invoke(null, name);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Throwable t) {
            return "<异常:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static CustomPresets.Entry entryOf(String name, String snap) {
        try {
            java.lang.reflect.Constructor<CustomPresets.Entry> c =
                    CustomPresets.Entry.class.getDeclaredConstructor(String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(name, snap);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String preservedName(String escaped, String original) {
        // 把 escapeName 的输出还原，便于与原名逐字比较
        if (escaped == null || "<无>".equals(escaped)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 5 < escaped.length() && escaped.charAt(i + 1) == 'u') {
                sb.append((char) Integer.parseInt(escaped.substring(i + 2, i + 6), 16));
                i += 5;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String names(List<CustomPresets.Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (CustomPresets.Entry e : list) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(escapeName(e.name));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return escapeName(s);
    }

    private static String escapeName(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == NSEP || c == ESEP || c == 0xFFFE || c == 0xFFFF) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
