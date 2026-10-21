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
 *   customAnimPresets = 名称[U+0001]快照;[U+0001]...
 *   快照              = key=value;key=value;...   （{@link SettingsField#snapshot} 产出）
 * </pre>
 *
 * <p>分隔符用 {@code U+0001}（ASCII SOH）：
 * <ul>
 *   <li>用户不可能从面板键入它，所以名称<b>不需要任何转义</b>——
 *       中文、空格、{@code ;}、{@code =} 都能原样保存；</li>
 *   <li>它不会出现在 XML 的 CDATA 边界问题里（XML 1.0 允许 U+0001 以外的控制字符，
 *       而平台 {@code XmlSerializer} 用属性值转义，U+0001 会被写成字符引用）；</li>
 *   <li>比「用 {@code ;} 分隔 + 名称里转义 {@code ;}」简单得多 ——
 *       转义规则一旦有 bug 就是「名称乱码」这种用户可见的破坏。</li>
 * </ul>
 *
 * <p><b>解析绝不抛异常</b>：坏条目整条忽略，其余照常。
 * 手改坏一个字符不该让整页设置打不开。
 */
public final class CustomPresets {

    /** 名称与快照之间的分隔符（ASCII SOH，用户无法从面板键入）。 */
    public static final char NAME_SEP = (char) 1;

    /**
     * 条目之间的分隔符（ASCII STX）。
     *
     * <p><b>为什么条目分隔符不能用 {@code ;}</b>：快照内部本来就用 {@code ;} 分隔键值对，
     * 而<b>名称允许含 {@code ;}</b>（用户可能就想叫「A;B」）。两者共用 {@code ;} 时，
     * 一个带分号的名称会把条目切碎 —— 我第一版就是这么写的，探针 C-6/C-8/C-10 当场抓到。
     * 所以条目用 {@code U+0002}，名称里出现不了它（用户无法从面板键入）。
     */
    public static final char ENTRY_SEP = (char) 2;

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
        List<Entry> out = new ArrayList<>();
        for (String raw : all.split(String.valueOf(ENTRY_SEP))) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            int sep = raw.indexOf(NAME_SEP);
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

    private static Entry find(List<Entry> list, String name) {
        for (Entry e : list) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }
}
