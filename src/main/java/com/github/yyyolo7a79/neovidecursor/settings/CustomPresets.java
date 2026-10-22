package com.github.yyyolo7a79.neovidecursor.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自定义预设的<b>纯函数层</b>（0.9.9）。无状态、不碰任何单例、可独立单测。
 *
 * <h2>模型（为什么和 0.9.8 不同）</h2>
 *
 * <p>0.9.8 的模型是「每个内置预设各自记住用户的改动」（6 个字段的覆盖值）。
 * 问题：<b>内置预设被污染了</b>。用户改了「原版粉色」之后，那个内置预设不再是原版，
 * 只能靠「（已修改）」后缀 + 「恢复为默认值」按钮收场。
 *
 * <p>0.9.9 改成 IntelliJ 配色方案 / GIMP 工具预设的模型：
 * <ul>
 *   <li><b>内置预设永久只读</b>（{@link CursorPresets#ANIM_PRESETS} 是常量表，一位数都不动）；</li>
 *   <li>用户改动任何参数 → <b>自动派生</b>一个自定义预设（GIMP 官方文档：编辑内置预设时
 *       自动创建副本，因此「Duplicate」命令永远是禁用的 —— 不需要）；</li>
 *   <li>自定义预设可<b>改名</b>、<b>删除</b>，全参数快照持久化；</li>
 *   <li>于是「已修改」这个概念连同它的后缀一起消失。</li>
 * </ul>
 *
 * <h2>存储格式</h2>
 *
 * <pre>
 *   customAnimPresets = 名称[U+241F]快照[U+241E]名称[U+241F]快照
 *   快照              = key=value;key=value;...   （{@link SettingsField#snapshot} 产出）
 * </pre>
 *
 * <h2>分隔符为什么是 U+241F / U+241E（这条踩过大坑，务必别改回去）</h2>
 *
 * <p>最初用的是 `U+0001`(SOH) / `U+0002`(STX)，理由是「用户不可能从键盘键入」。
 * 但那是<b>键盘视角</b>，不是<b>存储视角</b> ——
 * <b>U+0001/U+0002 是 XML 1.0 非法码点</b>（XML 1.0 只允许
 * `#x9 | #xA | #xD | [#x20-#xD7FF] | ...`），
 * 而 IDEA 的 {@code XmlSerializer} 把它们<b>静默丢弃</b>、也不会写成字符引用。
 *
 * <p>实测后果（2026-10-22 用真实平台类复现）：
 * <pre>
 *   内存：  测试预设[U+0001]enabled=1;trailMode=SPRING;...
 *   XML ：  测试预设enabled=1;trailMode=SPRING;...     &lt;- 分隔符消失，名字与内容黏连
 *   回读：  解析出 0 条预设                              &lt;- 用户建的自定义预设【全部消失】
 * </pre>
 *
 * <p>换成 `U+241F`(SYMBOL FOR UNIT SEPARATOR) / `U+241E`(SYMBOL FOR RECORD SEPARATOR)：
 * 两者都在 XML 1.0 合法区间内，且仍是「用户不可能从面板键入」的可打印符号
 * （它们本身就是「可见形式的控制字符」符号，语义恰好对应）。
 * 实测回读后 2 条预设完整保留。
 *
 * <p><b>兼容旧值</b>：{@link #parse} 同时接受旧分隔符（`U+0001`/`U+0002`），
 * 这样已经落盘过的用户数据不会因为换分隔符而报废。
 *
 * <p><b>解析绝不抛异常</b>：坏条目整条忽略，其余照常。
 * 手改坏一个字符不该让整页设置打不开。
 */
public final class CustomPresets {

    /**
     * 名称与快照之间的分隔符 = U+241F（SYMBOL FOR UNIT SEPARATOR）。
     *
     * <p><b>必须是 XML 1.0 合法码点</b>。U+0001 曾是这里，被平台静默丢弃
     * → 自定义预设重启后全消失。详见类注释。
     */
    public static final char NAME_SEP = '\u241F';

    /**
     * 条目之间的分隔符 = U+241E（SYMBOL FOR RECORD SEPARATOR）。
     *
     * <p><b>为什么条目分隔符不能用 {@code ;}</b>：快照内部本来就用 {@code ;} 分隔键值对，
     * 而<b>名称允许含 {@code ;}</b>。两者共用 {@code ;} 时，一个带分号的名称会把条目切碎
     * （探针 C-6/C-8/C-10 当场抓到过）。
     */
    public static final char ENTRY_SEP = '\u241E';

    /** 旧分隔符（XML 非法，仅为兼容已经落盘的数据而保留读取能力）。 */
    private static final char LEGACY_NAME_SEP = (char) 1;
    private static final char LEGACY_ENTRY_SEP = (char) 2;

    private CustomPresets() {
    }

    /** 一个自定义预设。 */
    public static final class Entry {
        /** 显示名（可含任意字符，包括中文与空格）。 */
        public final String name;
        /** 快照串（{@link SettingsField#snapshot} 的产出）。 */
        public final String snapshot;

        Entry(String name, String snapshot) {
            this.name = name;
            this.snapshot = snapshot;
        }
    }

    /**
     * 解析整张表。<b>永不抛异常</b>，坏条目忽略。
     *
     * <p>重名条目只保留<b>第一个</b>：后面那个无法被下拉选中（按名字定位），
     * 留着只会让 {@link #put} 删除时误删。
     */
    public static List<Entry> parse(String all) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        // 兼容新旧两套分隔符：旧值里可能是 U+0002 / U+0001（XML 非法，见类注释）。
        // 换分隔符不能让已经落盘的用户数据报废。
        //
        // 【不能用「整串全局判断用哪套」】—— 第二轮审查 S-3/S-4 实测：
        // 一条数据里可能同时含新旧分隔符（用户 A 用旧版存过、又用新版存过），
        // 全局判定会让另一半被吞掉（实测 2 条只读出 1 条）。
        // 正确做法：【逐条】切分，两种条目分隔符都认。
        List<Entry> out = new ArrayList<>();
        for (String raw : splitOnAny(all, ENTRY_SEP, LEGACY_ENTRY_SEP)) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            // 逐条找名称分隔符。
            //
            // 【第二轮审查 F2：迁移边界，medium】
            // 0.9.9 首版（没有 sanitizeName 的那版）落的数据，名字里可能天然含 U+241F
            // （用户从网页粘贴的符号）。如果无脑「先找新分隔符」，会把
            //   A<U+241F>B<U+0001>a=1     （名字"A␟B"，旧分隔符）
            // 在中间的 U+241F 处切开 → 名字变 "A"、快照变 "B<U+0001>a=1" → 一个字段都还原不出。
            //
            // 正解：**旧分隔符存在时优先按旧的切**（旧格式里 U+0001 才是权威名分隔符，
            // U+241F 只是一段普通文本）；只有不含旧分隔符时才用新的。
            int sep;
            int legacyAt = raw.indexOf(LEGACY_NAME_SEP);
            if (legacyAt > 0) {
                sep = legacyAt;
            } else {
                sep = raw.indexOf(NAME_SEP);
            }
            if (sep <= 0) {
                continue;                       // 缺分隔符 / 名称为空 → 坏条目，忽略
            }
            String name = raw.substring(0, sep);
            String snap = raw.substring(sep + 1);
            if (snap.isEmpty() || find(out, name) != null) {
                continue;                       // 空快照 / 重名 → 忽略后者
            }
            out.add(new Entry(name, snap));
        }
        return out;
    }

    /** 序列化整张表（{@link #parse} 的逆运算）。 */
    public static String format(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (e == null || e.name == null || e.name.isEmpty() || e.snapshot == null
                    || e.snapshot.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(ENTRY_SEP);
            }
            // 【不要在这里净化名称】第二轮审查 S-5/S-11b 实测：
            // format 静默改写名字会导致「内存名 ≠ 落盘名」——
            // put("X") 之后 has("X") 变 false，用户点了新建却发现下拉里叫「X 」，
            // 按原名字再也取不回来（前导空格被 trim 掉）。
            //
            // 正确做法：**入口处拒绝**非法名（见 put/rename），format 只做序列化。
            // 这里的防御只处理「理论上不该出现」的残留分隔符：直接跳过该条目，
            // 而不是悄悄改名 —— 静默改数据比丢一条更糟。
            // 入口已经用 isValidName 拦过，正常路径不会走到这里。
            // 但万一某个调用方漏了校验，宁可【跳过这一条】也不能让它切碎整张表；
            // 同时不静默 —— 留一行日志，便于日后定位（第二轮审查 F5）。
            if (!isValidName(e.name)) {
                java.util.logging.Logger.getLogger("neovide-cursor")
                        .warning("跳过非法预设名（含 XML 非法码点或分隔符），长度=" + e.name.length());
                continue;
            }
            sb.append(e.name).append(NAME_SEP).append(e.snapshot);
        }
        return sb.toString();
    }

    /** 按名字取快照；不存在返回 {@code null}。 */
    public static String get(String all, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Entry e : parse(all)) {
            if (e.name.equals(name)) {
                return e.snapshot;
            }
        }
        return null;
    }

    /** 是否存在该名字的预设。 */
    public static boolean has(String all, String name) {
        return get(all, name) != null;
    }

    /**
     * 名字是否可用作预设名。
     *
     * <p><b>为什么要「入口拒绝」而不是「出口净化」（第二轮审查 S-5/S-11b 的教训）</b>：
     * 最初我在 {@link #format} 里把非法字符替换成空格并 trim()。
     * 实测后果是<b>内存名 ≠ 落盘名</b>：
     * <pre>
     *   put("X")  之后 has("X") == false     （落盘成了「X 」）
     *   put(" A") 之后 get(" A") == null     （前导空格被 trim 掉）
     * </pre>
     * 用户点了「新建」，下拉里却出现一个改过名的预设，按原名字再也取不回来。
     * <b>静默改数据比明确报错更糟</b>，所以改为入口校验。
     *
     * <p>不接受的只有两类：空名（含全空白）、含分隔符（会切碎存储格式）。
     * 其余一切照收 —— 中文、空格、分号、等号、emoji 都可以。
     */
    public static boolean isValidName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // ① 四个分隔符（会切碎存储格式）
            if (c == NAME_SEP || c == ENTRY_SEP
                    || c == LEGACY_NAME_SEP || c == LEGACY_ENTRY_SEP) {
                return false;
            }
            // ② XML 1.0 非法码点（会在落盘时被平台静默丢弃，见 isXmlLegal）
            if (!isXmlLegal(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这个码点能否安全地写进 XML 属性值。
     *
     * <p><b>为什么必须逐字符查，而不是只管那 4 个分隔符</b>
     * （第二轮审查 F1，high）：F2 的根因是「用了 XML 非法字符做分隔符」，
     * 但那只修了分隔符本身。用户还可能把任意控制字符<b>粘进名字里</b>：
     * <pre>
     *   名字 = "A\u000BB"      (垂直制表符)
     *   落盘 → "AB"            (平台静默吞掉 U+000B)
     *   回读 → animCustomName 悬空 → 预设在下拉里再也选不中
     * </pre>
     * VSepDiag 实测丢在 {@code XmlSerializer.serialize()} 这一层，
     * 与当年 U+0001 完全同源。
     *
     * <p>合法范围按 XML 1.0 规范：
     * {@code 0x09 | 0x0A | 0x0D | 0x20-0xD7FF | 0xE000-0xFFFD | 0x10000-0x10FFFF}
     * （Java 的 char 是 UTF-16 单元，代理对由两个 char 组成，
     * 但 {@code Character.isSurrogate} 的**孤立**代理是非法的，必须单独挡。）
     */
    static boolean isXmlLegal(char c) {
        if (c == 0x09 || c == 0x0A || c == 0x0D) {
            return true;                       // 制表 / 换行 / 回车：XML 允许
        }
        if (c < 0x20) {
            return false;                      // 其余 C0 控制符：非法
        }
        if (c >= 0x20 && c <= 0xD7FF) {
            return true;
        }
        if (c >= 0xE000 && c <= 0xFFFD) {
            return true;                       // 含 U+FFFE/U+FFFF 之外的全部
        }
        // 0xD800-0xDFFF 是代理区：孤立代理非法；成对代理由 String 层面处理
        return false;
    }

    /** 名字不可用的原因（给 UI 提示用）；合法时返回 {@code null}。 */
    public static String nameError(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "名字不能为空";
        }
        if (!isValidName(name)) {
            return "名字里不能包含分隔控制符";
        }
        return null;
    }

    /**
     * 新增<b>或替换</b>一个预设（按名字）。
     *
     * <p>重名时原位替换、不改变顺序 —— 这样「改了参数再保存」不会让下拉里的项跳位置。
     */
    public static String put(String all, String name, String snapshot) {
        // 入口校验：非法名直接拒绝（不静默改名，见 isValidName 的注释）
        if (!isValidName(name) || snapshot == null || snapshot.isEmpty()) {
            return all == null ? "" : all;
        }
        List<Entry> list = new ArrayList<>(parse(all));
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.set(i, new Entry(name, snapshot));
                return format(list);
            }
        }
        list.add(new Entry(name, snapshot));
        return format(list);
    }

    /**
     * 改名。目标名已存在时<b>拒绝</b>（返回原串）—— 静默覆盖别人的预设是数据丢失。
     *
     * <p>返回 {@code null} 表示「改名失败」，调用方据此提示用户；返回原串表示「没必要改」。
     */
    public static String rename(String all, String from, String to) {
        if (from == null || to == null || from.isEmpty() || to.isEmpty() || from.equals(to)) {
            return all == null ? "" : all;
        }
        // 入口校验：非法目标名直接失败（返回 null = 调用方提示用户）
        if (!isValidName(to)) {
            return null;
        }
        if (has(all, to)) {
            return null;                        // 目标名已被占用 → 失败
        }
        List<Entry> list = new ArrayList<>(parse(all));
        boolean hit = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(from)) {
                list.set(i, new Entry(to, list.get(i).snapshot));
                hit = true;
                break;
            }
        }
        return hit ? format(list) : (all == null ? "" : all);
    }

    /** 删除（幂等）。 */
    public static String remove(String all, String name) {
        if (name == null || name.isEmpty()) {
            return all == null ? "" : all;
        }
        List<Entry> list = new ArrayList<>(parse(all));
        list.removeIf(e -> e.name.equals(name));
        return format(list);
    }

    /**
     * 生成一个不与现有预设重名的名字。
     *
     * <p>规则：第一次用 {@code base} 本身；之后 {@code base 2}、{@code base 3}…
     * （与 Windows 复制文件、IntelliJ 复制配色方案的命名习惯一致）。
     *
     * <p>为什么从 2 开始：{@code base 1} 读起来像「第一份」，而用户刚刚才创建第一份。
     */
    public static String uniqueName(String all, String base) {
        String b = (base == null || base.isEmpty()) ? "自定义" : base;
        if (!has(all, b)) {
            return b;
        }
        for (int i = 2; i < 10000; i++) {
            String candidate = b + " " + i;
            if (!has(all, candidate)) {
                return candidate;
            }
        }
        return b + " " + System.currentTimeMillis();
    }

    /** 最多允许多少个自定义预设（防止下拉框长到不可用）。 */
    public static final int MAX_PRESETS = 50;

    /** 是否还能再加。 */
    public static boolean canAdd(String all) {
        return parse(all).size() < MAX_PRESETS;
    }

    /** 按任意一个分隔符切分（两种都认）。 */
    private static List<String> splitOnAny(String all, char... seps) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        outer:
        for (int i = 0; i < all.length(); i++) {
            char c = all.charAt(i);
            for (char s : seps) {
                if (c == s) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue outer;
                }
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static Entry find(List<Entry> list, String name) {
        for (Entry e : list) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }
}
