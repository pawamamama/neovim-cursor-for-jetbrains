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
        char entrySep = all.indexOf(ENTRY_SEP) >= 0 ? ENTRY_SEP
                : (all.indexOf(LEGACY_ENTRY_SEP) >= 0 ? LEGACY_ENTRY_SEP : ENTRY_SEP);
        char nameSep = all.indexOf(NAME_SEP) >= 0 ? NAME_SEP
                : (all.indexOf(LEGACY_NAME_SEP) >= 0 ? LEGACY_NAME_SEP : NAME_SEP);

        List<Entry> out = new ArrayList<>();
        for (String raw : all.split(java.util.regex.Pattern.quote(String.valueOf(entrySep)))) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            int sep = raw.indexOf(nameSep);
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
            // 名称里若混进了任一分隔符（粘贴 / 手改 XML 都可能），必须剔掉 ——
            // 否则一条坏名字会把整张表切碎（和 F2 是同一类破坏）。
            sb.append(sanitizeName(e.name)).append(NAME_SEP).append(e.snapshot);
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
     * 新增<b>或替换</b>一个预设（按名字）。
     *
     * <p>重名时原位替换、不改变顺序 —— 这样「改了参数再保存」不会让下拉里的项跳位置。
     */
    public static String put(String all, String name, String snapshot) {
        if (name == null || name.isEmpty() || snapshot == null || snapshot.isEmpty()) {
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

    /**
     * 把名称里混进来的分隔符剔掉。
     *
     * <p>为什么需要：用户可能从网页/聊天粘贴一个名字，或者手改 XML。
     * 只要名字里出现 U+241F / U+241E / 旧的 U+0001 / U+0002，
     * 解析时就会把它当成条目边界，<b>整张表被切碎</b>。
     *
     * <p>换成空格而不是直接删除：让名字仍然可读（「A[B」→「A B」而不是「AB」）。
     */
    static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == NAME_SEP || c == ENTRY_SEP
                    || c == LEGACY_NAME_SEP || c == LEGACY_ENTRY_SEP) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
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
