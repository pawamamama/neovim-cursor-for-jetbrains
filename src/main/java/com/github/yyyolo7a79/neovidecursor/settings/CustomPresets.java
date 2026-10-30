package com.github.yyyolo7a79.neovidecursor.settings;

import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义预设的<b>纯函数层</b>：不持有业务状态、不碰任何单例，可在离线探针里独立驱动
 * （日志走平台 {@link Logger}，所以运行时需要 idea-libs 在 classpath 上 ——
 * 与 {@code NeovideCursorSettings} / {@code SettingsField} 的既有写法一致）。
 *
 * <p><b>唯一的副作用是写日志</b>（M-10，2026-10-30）。{@link Logger} 属于平台而不是本类的
 * 状态，所以「无状态」这个性质仍然成立 —— 同一份输入永远得到同一份输出。
 *
 * <p>为什么必须有这条日志：坏条目 / 重名条目在 {@link #parse} 里是<b>静默跳过</b>的，
 * 而<b>下一次任何 put / remove / rename 后的 {@link #format} 都会把磁盘上的它一起抹掉</b>。
 * 也就是说「丢一条」是一次性的、不可逆的；没有日志，用户只会看到「我的预设不见了」。
 *
 * <h2>模型（2026-10-29 重构：从「平铺列表」改成「带父链的树」）</h2>
 *
 * <p>旧模型只有 {@code 名称␟快照} 两段，预设之间没有任何关系。后果是
 * 「恢复默认值」根本不知道这个副本派生自谁 —— 只能去读一个全局可变指针
 * （{@code animPresetBase}），而那个指针会被「选内置预设」改写。
 * 实测症状：选中「基础 副本」后，按钮写着「恢复为「缎带」默认值」。
 *
 * <p>新模型给每个条目补一段<b>父引用</b>：
 *
 * <pre>
 *   条目        = 名称 ␟ 父引用 ␝ 快照
 *   父引用      = ""            无父（旧数据 / 用户手工新建）
 *               | "b&lt;下标&gt;"     内置预设（如 b0 = 基础）
 *               | "c&lt;名字&gt;"     自定义预设（如 c基础 副本）
 * </pre>
 *
 * <p><b>「恢复默认值」= 沿父链向上找最近一个还存在的祖先</b>：
 * <ul>
 *   <li>找到内置 → 写该内置预设的常量；</li>
 *   <li>找到自定义 → 写它<b>当前</b>的快照（用户 2026-10-29 拍板：继承语义）；</li>
 *   <li>父被删了 → 删除时已把子节点接到祖父（见 {@link #remove}）；</li>
 *   <li>一路都没有 → 返回空 {@link Base}，调用方据此把按钮置灰（绝不说谎）。</li>
 * </ul>
 *
 * <h2>为什么父引用用「名字」而不是下标</h2>
 *
 * <p>自定义预设会增删，下标不稳定。名字在很长的生命周期内是稳定的（改名走
 * {@link #rename}，它会把所有引用一起改掉）。内置预设是编译期常量表，下标永远不变，
 * 所以用 {@code b<下标>} 最直接。
 *
 * <h2>存储格式与分隔符（这条踩过大坑，务必别改回去）</h2>
 *
 * <pre>
 *   customAnimPresets = 名称␟父␝快照 ␞ 名称␟父␝快照
 *   快照               = key=value;key=value;...   （{@link SettingsField#snapshot} 产出）
 * </pre>
 *
 * <p><b>U+241F / U+241E 的来历</b>：最初用的是 U+0001(SOH) / U+0002(STX)，
 * 理由是「用户不可能从键盘键入」。但那是<b>键盘视角</b>，不是<b>存储视角</b> ——
 * <b>U+0001/U+0002 是 XML 1.0 非法码点</b>，而 IDEA 的 {@code XmlSerializer}
 * 把它们<b>静默丢弃</b>、也不会写成字符引用。
 *
 * <p>实测后果（2026-10-22 用真实平台类复现）：
 * <pre>
 *   内存：  测试预设[U+0001]enabled=1;trailMode=SPRING;...
 *   XML ：  测试预设enabled=1;trailMode=SPRING;...     &lt;- 分隔符消失，名字与内容黏连
 *   回读：  解析出 0 条预设                              &lt;- 用户建的自定义预设【全部消失】
 * </pre>
 *
 * <p>所以三个分隔符都必须落在 XML 1.0 合法区间内：
 * <ul>
 *   <li>{@link #NAME_SEP} = U+241F（SYMBOL FOR UNIT SEPARATOR）</li>
 *   <li>{@link #PARENT_SEP} = U+241D（SYMBOL FOR GROUP SEPARATOR）—— 2026-10-29 新增</li>
 *   <li>{@link #ENTRY_SEP} = U+241E（SYMBOL FOR RECORD SEPARATOR）</li>
 * </ul>
 * 它们本身就是「可见形式的控制字符」符号，语义恰好对应，且用户不可能从面板键入。
 *
 * <p><b>兼容旧值</b>：{@link #parse} 同时接受旧分隔符（U+0001/U+0002）与旧的两段式条目
 * （没有父引用）。这样已经落盘的用户数据不会因为换格式而报废 —— 用户 2026-10-29
 * 明确要求「以后有用户用，他更新我们必须保住他的预设」。
 *
 * <p><b>解析绝不抛异常</b>：坏条目整条忽略，其余照常。
 * 手改坏一个字符不该让整页设置打不开。
 */
public final class CustomPresets {

    /**
     * 名称与父引用之间的分隔符 = U+241F（SYMBOL FOR UNIT SEPARATOR）。
     *
     * <p><b>必须是 XML 1.0 合法码点</b>。U+0001 曾是这里，被平台静默丢弃
     * → 自定义预设重启后全消失。详见类注释。
     */
    public static final char NAME_SEP = '␟';

    /**
     * 父引用与快照之间的分隔符 = U+241D（SYMBOL FOR GROUP SEPARATOR）。
     *
     * <p>2026-10-29 引入。为什么不复用 {@link #NAME_SEP}：父引用里的自定义名字
     * 本身可以含除分隔符外的任意字符，若「名称/父/快照」共用同一个字符，
     * 解析就得靠「出现第几次」来数，一处数错整条就废。加一个独立字符后，
     * 歧义面只剩「旧两段式 vs 新三段式」一种，且能在一次 indexOf 内判完。
     *
     * <p>U+241D 同样是 XML 1.0 合法码点，理由见类注释。
     */
    public static final char PARENT_SEP = '␝';

    /**
     * 条目之间的分隔符 = U+241E（SYMBOL FOR RECORD SEPARATOR）。
     *
     * <p><b>为什么条目分隔符不能用 {@code ;}</b>：快照内部本来就用 {@code ;} 分隔键值对，
     * 而<b>名称允许含 {@code ;}</b>。两者共用 {@code ;} 时，一个带分号的名称会把条目切碎
     * （探针 C-6/C-8/C-10 当场抓到过）。
     */
    public static final char ENTRY_SEP = '␞';

    /** 旧分隔符（XML 非法，仅为兼容已经落盘的数据而保留读取能力）。 */
    private static final char LEGACY_NAME_SEP = (char) 1;
    private static final char LEGACY_ENTRY_SEP = (char) 2;

    /** 内置父引用的前缀。 */
    private static final String PARENT_BUILTIN = "b";
    /** 自定义父引用的前缀。 */
    private static final String PARENT_CUSTOM = "c";

    /** 父链最大步数 —— 防环上限。超过它一定是有环或数据损坏。 */
    private static final int MAX_CHAIN = 64;

    // ==================== 丢弃日志（M-10，2026-10-30）====================

    private static final Logger LOG = Logger.getInstance(CustomPresets.class);

    /**
     * 「已报过的丢弃组」上限。
     *
     * <p>为什么需要上限：{@link #parse} 不是一次性调用 —— 菜单每次重建、每次
     * {@link #get} 都会跑一遍。同一条坏数据留在磁盘上，每次都会触发一次跳过；
     * 不设上限就是把 idea.log 刷成一片，等于从「没有日志」变成「日志没用」。
     * 这与 {@code CursorDiag} 的预算表是同一套思路。
     */
    private static final int PARSE_REPORTED_MAX = 16;

    /**
     * 单次 {@link #parse} 里逐条打印的上限。
     *
     * <p>与 {@link #PARSE_REPORTED_MAX} 是两道不同的闸门：
     * 这一道防「一次解析遇到 50 条坏数据 → 一瞬间刷 50 行」，
     * 那一道防「同一条坏数据每次解析都再报一遍」。
     * 少任何一道，坏数据都会把 idea.log 刷成一片。
     */
    private static final int PARSE_WARN_LIMIT = 5;

    /** 已报过的（原因 + 名字）。同一组只报一次。 */
    private static final Set<String> PARSE_REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * 已报过的「汇总」组合（{@code 坏条数/重名数}）。同一组只报一次。
     *
     * <p><b>为什么汇总也需要去重</b>：{@link #parse} 在<b>读路径</b>上被高频调用 ——
     * 菜单每次弹出（{@code AnimPresetGroup.update}）、面板每次刷新（{@code canAdd}）都会跑。
     * 只要磁盘上躺着一条坏条目，不加这道闸门就会「每弹一次菜单刷一行同样的汇总」，
     * 正是本批要消灭的日志洪水。
     *
     * <p>键里带 {@code 坏条数/重名数} 而不是固定串：数量变化时值得再报一次
     * （用户删掉一条坏的之后还剩几条，是排查时的有效信息）。
     */
    private static final Set<String> PARSE_SUMMARY_REPORTED = ConcurrentHashMap.newKeySet();

    /** 逐条记录达到上限后只补一条汇总，用这个标志保证只补一次。 */
    private static volatile boolean parseCapLogged;

    /**
     * 记录一次「跳过了一条」。
     *
     * <p><b>绝不打印快照正文</b>：快照可能很长，而且里面是用户的配置值。
     * 只打名字、原因、条目长度 —— 足够定位是哪一条，又不会淹掉日志。
     */
    private static boolean reportDropped(String reason, String name, int length,
                                         boolean allowDetail) {
        try {
            if (PARSE_REPORTED.size() >= PARSE_REPORTED_MAX) {
                if (!parseCapLogged) {
                    parseCapLogged = true;
                    LOG.warn("neovide-cursor: 自定义预设的丢弃记录已达上限 "
                            + PARSE_REPORTED_MAX + " 组，后续不再逐条记录");
                }
                return false;
            }
            // 【登记无条件，打印才受预算约束】
            //
            // 反过来做（预算用完了就不登记）会造成一个隐蔽的循环：第一次解析只登记
            // 前 5 条，第二次解析这 5 条被去重、预算又落到第 6~10 条…… 于是同一条
            // 坏数据会被「每弹一次菜单多报 5 条」，一共报 10 轮才停。登记了却不打印
            // 只是少一条明细，总量由汇总那条交代，这才是「有界」的正确含义。
            //
            // 键用截断后的名字：否则手改 XML 塞进几 MB 的名字时，这个集合会各自
            // 驻留整份原文（最多 16 份，进程内永不释放）。
            String shown = cut(name, NAME_LOG_MAX);
            if (!PARSE_REPORTED.add(reason + '\u0000' + shown)) {
                return false;                       // 同一组已登记过
            }
            if (!allowDetail) {
                return false;                       // 本次解析的逐条预算已用完（只登记不打印）
            }
            LOG.warn("neovide-cursor: 跳过一条" + reason + "的自定义预设（名字=" + shown
                    + "，条目长度=" + length + "）。它会在下次保存时从磁盘上永久消失");
            return true;
        } catch (Throwable ignored) {
            // parse 的契约是「永不抛异常」（见类注释），日志失败不得反过来破坏它
            return false;
        }
    }

    /** 从一条坏条目里尽力取出名字（第一个名称分隔符之前的部分）。取不到返回 {@code (取不到)}。 */
    private static String bestEffortName(String raw) {
        int sep = raw.indexOf(LEGACY_NAME_SEP);
        if (sep <= 0) {
            sep = raw.indexOf(NAME_SEP);
        }
        return sep <= 0 ? "(取不到)" : raw.substring(0, sep);
    }

    /** 日志里字符串的最大展示长度。按 UTF-16 code unit 计，与项目里 charsMoved 的口径一致。 */
    private static final int NAME_LOG_MAX = 24;

    /**
     * 把字符串截断到 {@code max} 个 char，超出时补上原长。
     *
     * <p>可能切在代理对中间（得到一个未配对代理）。后果<b>仅限日志显示</b>：
     * 平台的 UTF-8 编码器会把它替换成 {@code ?}，不会抛异常。为这点收益去用
     * {@code offsetByCodePoints} 不划算，故只在此说明。
     */
    private static String cut(String s, int max) {
        if (s == null) {
            return "(null)";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(共 " + s.length() + " 字符)";
    }

    private CustomPresets() {
    }

    /** 一个自定义预设。 */
    public static final class Entry {
        /** 显示名（可含任意字符，包括中文与空格）。 */
        public final String name;

        /**
         * 父引用：{@code ""} 无父 / {@code "b<下标>"} 内置 / {@code "c<名字>"} 自定义。
         *
         * <p>永远不为 {@code null}（旧数据解析成空串），调用方不必判空。
         */
        public final String parent;

        /** 快照串（{@link SettingsField#snapshot} 的产出）。 */
        public final String snapshot;

        Entry(String name, String parent, String snapshot) {
            this.name = name;
            this.parent = parent == null ? "" : parent;
            this.snapshot = snapshot;
        }

        @Override
        public String toString() {
            return name + "（parent=" + (parent.isEmpty() ? "-" : parent) + "）";
        }
    }

    /**
     * 「恢复默认值」解析出来的目标。
     *
     * <p>恰好二选一：{@link #builtinIdx} &gt;= 0 表示内置预设；
     * {@link #custom} 非 null 表示自定义预设。两者都空表示<b>没有可恢复的祖先</b> ——
     * 调用方必须把按钮置灰，而不是随便挑一个内置预设（那正是本次重构要消灭的撒谎）。
     */
    public static final class Base {
        /** 内置预设下标；-1 表示不是内置。 */
        public final int builtinIdx;
        /** 自定义预设条目；null 表示不是自定义。 */
        public final Entry custom;

        private Base(int builtinIdx, Entry custom) {
            this.builtinIdx = builtinIdx;
            this.custom = custom;
        }

        /** 是否真的解析到了目标。 */
        public boolean exists() {
            return builtinIdx >= 0 || custom != null;
        }
    }

    /** 构造内置父引用。 */
    public static String builtinParent(int idx) {
        return PARENT_BUILTIN + idx;
    }

    /**
     * 直接构造一个「目标是内置预设」的 {@link Base}。
     *
     * <p>给「当前选中的本来就是内置预设」这一种情况用 —— 那时不需要查任何父链，
     * 它的默认值就是它自己。把这条路径也收进 {@link Base}，调用方就只需要面对
     * 一种返回类型，不必在 UI 里写两套分支。
     */
    public static Base builtinBase(int idx) {
        return new Base(idx, null);
    }

    /** 构造自定义父引用。 */
    public static String customParent(String name) {
        return PARENT_CUSTOM + name;
    }

    /** 这个父引用是不是内置。 */
    public static boolean isBuiltinParent(String parent) {
        return parent != null && parent.length() > 1 && parent.charAt(0) == PARENT_BUILTIN.charAt(0);
    }

    /** 这个父引用是不是自定义。 */
    public static boolean isCustomParent(String parent) {
        return parent != null && parent.length() > 1 && parent.charAt(0) == PARENT_CUSTOM.charAt(0);
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
        int bad = 0;
        int dup = 0;
        int detailBudget = PARSE_WARN_LIMIT;
        for (String raw : splitOnAny(all, ENTRY_SEP, LEGACY_ENTRY_SEP)) {
            if (raw == null || raw.isEmpty()) {
                continue;                       // 相邻分隔符产生的空段，不是坏数据
            }
            Entry e = parseOne(raw);
            if (e == null) {
                bad++;
                if (reportDropped("坏", bestEffortName(raw), raw.length(), detailBudget > 0)) {
                    detailBudget--;
                }
                continue;
            }
            if (find(out, e.name) != null) {
                dup++;
                if (reportDropped("重名", e.name, raw.length(), detailBudget > 0)) {
                    detailBudget--;
                }
                continue;                       // 重名 → 保留先出现的那个
            }
            out.add(e);
        }
        if (bad + dup > 0) {
            // 逐条上限之外必须有一条「总量」：坏表很大时用户只看得到前几条，
            // 却不知道一共丢了多少。这一条就是那个总数。
            //
            // 但它同样必须去重 —— parse 在读路径上会被菜单/面板反复调用，
            // 磁盘上只要留着一条坏数据，不去重就是「每弹一次菜单刷一行」。
            try {
                String key = bad + "/" + dup;
                if (PARSE_SUMMARY_REPORTED.size() < PARSE_REPORTED_MAX
                        && PARSE_SUMMARY_REPORTED.add(key)) {
                    LOG.warn("neovide-cursor: 自定义预设表解析完成：共跳过 " + bad
                            + " 条坏条目、" + dup + " 条重名条目（当前有效 "
                            + out.size() + " 条）");
                }
            } catch (Throwable ignored) {
                // 同上：日志不得让 parse 抛异常
            }
        }
        return out;
    }

    /**
     * 解析单条。三种形态都认：
     * <pre>
     *   名称 ␟ 父 ␝ 快照      新格式（2026-10-29）
     *   名称 ␟ 快照           旧格式（0.9.9~0.9.104），父 = 空
     *   名称 [U+0001] 快照    更旧的格式（分隔符 XML 非法），父 = 空
     * </pre>
     */
    private static Entry parseOne(String raw) {
        // 先找名称分隔符：旧分隔符存在时优先按旧的切（旧格式里 U+0001 才是权威名分隔符，
        // U+241F 只是一段普通文本）。这是第二轮审查 F2 的结论，别改回去。
        int sep;
        int legacyAt = raw.indexOf(LEGACY_NAME_SEP);
        if (legacyAt > 0) {
            sep = legacyAt;
        } else {
            sep = raw.indexOf(NAME_SEP);
        }
        if (sep <= 0) {
            return null;                        // 缺分隔符 / 名称为空 → 坏条目
        }
        String name = raw.substring(0, sep);
        String rest = raw.substring(sep + 1);

        // rest 里若含 PARENT_SEP，就是新格式：父␝快照。
        // 只在【第一个】 PARENT_SEP 处切 —— 快照内部不含它（键值都由字段表产出），
        // 即使含了也只会落到快照里，不影响父引用的正确性。
        int pAt = rest.indexOf(PARENT_SEP);
        String parent;
        String snap;
        if (pAt >= 0) {
            parent = rest.substring(0, pAt);
            snap = rest.substring(pAt + 1);
        } else {
            parent = "";                        // 旧两段式：无父
            snap = rest;
        }
        if (snap.isEmpty()) {
            return null;
        }
        return new Entry(name, parent, snap);
    }

    /** 序列化整张表（{@link #parse} 的逆运算）。 */
    public static String format(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int in = entries.size();
        int written = 0;
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
            if (!isValidName(e.name)) {
                java.util.logging.Logger.getLogger("neovide-cursor")
                        .warning("跳过非法预设名（含 XML 非法码点或分隔符），长度=" + e.name.length());
                continue;
            }
            sb.append(e.name).append(NAME_SEP);
            // 旧格式（无父）继续输出两段式：不给旧数据平白加一个空父段，
            // 免得每次读写都在用户配置里制造无意义的 diff。
            if (e.parent != null && !e.parent.isEmpty()) {
                sb.append(e.parent).append(PARENT_SEP);
            }
            sb.append(e.snapshot);
            written++;
        }
        if (written < in) {
            // 【M-10，2026-10-30】这一条是「不可逆」的直接证据：parse 阶段只是跳过，
            // 而这里一旦少写，磁盘上那条就永久没了。所以必须留痕。
            // 注：上面那条 java.util.logging 是既有的 L-7 债务，属第 3 批，本次不动它。
            try {
                LOG.warn("neovide-cursor: 序列化自定义预设时丢弃 " + (in - written)
                        + " 条（名称非法或快照为空），这些条目会从磁盘上永久消失");
            } catch (Throwable ignored) {
                // format 也被工具链直接调用，日志失败不得让它抛异常
            }
        }
        return sb.toString();
    }

    /** 按名字取快照；不存在返回 {@code null}。 */
    public static String get(String all, String name) {
        Entry e = getEntry(all, name);
        return e == null ? null : e.snapshot;
    }

    /** 按名字取整个条目；不存在返回 {@code null}。 */
    public static Entry getEntry(String all, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Entry e : parse(all)) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** 是否存在该名字的预设。 */
    public static boolean has(String all, String name) {
        return getEntry(all, name) != null;
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
            // ① 五个分隔符（会切碎存储格式）
            if (c == NAME_SEP || c == PARENT_SEP || c == ENTRY_SEP
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
     * <p><b>为什么必须逐字符查，而不是只管那几个分隔符</b>（第二轮审查 F1，high）：
     * F2 的根因是「用了 XML 非法字符做分隔符」，但那只修了分隔符本身。
     * 用户还可能把任意控制字符<b>粘进名字里</b>：
     * <pre>
     *   名字 = "A\u000BB"      (垂直制表符)
     *   落盘 → "AB"            (平台静默吞掉 U+000B)
     *   回读 → animCustomName 悬空 → 预设在下拉里再也选不中
     * </pre>
     * VSepDiag 实测丢在 {@code XmlSerializer.serialize()} 这一层，与当年 U+0001 完全同源。
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
     * 新增<b>或替换</b>一个预设（按名字），同时更新父引用。
     *
     * <p>重名时原位替换、不改变顺序 —— 这样「改了参数再保存」不会让下拉里的项跳位置。
     */
    public static String put(String all, String name, String parent, String snapshot) {
        // 入口校验：非法名直接拒绝（不静默改名，见 isValidName 的注释）
        if (!isValidName(name) || snapshot == null || snapshot.isEmpty()) {
            return all == null ? "" : all;
        }
        // 【2026-10-29 审查 M5】悬空父引用降级为空父。
        //
        // 调用方在「当前站在某个自定义预设上」时会传 customParent(当前名)，
        // 而那个名字可能已被删除、或被手改 XML 改坏。直接写进去就是一条永久悬空引用 ——
        // 新预设的「恢复默认值」会永远置灰，且用户无从知道为什么。
        // 降级成空父后表现同样是置灰，但它是「明确的没有父」，不是断链。
        String safeParent = parent == null ? "" : parent;
        if (isCustomParent(safeParent) && !has(all, safeParent.substring(1))) {
            safeParent = "";
        }
        List<Entry> list = new ArrayList<>(parse(all));
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.set(i, new Entry(name, safeParent, snapshot));
                return format(list);
            }
        }
        list.add(new Entry(name, safeParent, snapshot));
        return format(list);
    }

    /**
     * 新增/替换，但<b>保留已有的父引用</b>（没有则空）。
     *
     * <p>这是最常用的重载：用户在副本上拖滑块时，快照要更新，但「我是谁的孩子」
     * 不该被一次编辑改掉。
     */
    public static String put(String all, String name, String snapshot) {
        Entry old = getEntry(all, name);
        return put(all, name, old == null ? "" : old.parent, snapshot);
    }

    /**
     * 改名。目标名已存在时<b>拒绝</b>（返回原串）—— 静默覆盖别人的预设是数据丢失。
     *
     * <p><b>必须同时改写所有子节点的父引用</b>，否则改名后：
     * <pre>
     *   子.parent = "c旧名"  → 旧名已不存在 → 子的「恢复默认值」一路找不到祖先
     * </pre>
     * 这是父链模型里最容易漏的一环。
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
        List<Entry> out = new ArrayList<>();
        boolean hit = false;
        String oldRef = customParent(from);
        String newRef = customParent(to);
        for (Entry e : parse(all)) {
            String name = e.name;
            String parent = e.parent;
            if (name.equals(from)) {
                name = to;
                hit = true;
            }
            // 子节点：父引用指向旧名 → 跟着改
            if (oldRef.equals(parent)) {
                parent = newRef;
            }
            out.add(new Entry(name, parent, e.snapshot));
        }
        return hit ? format(out) : (all == null ? "" : all);
    }

    /**
     * 删除（幂等）。
     *
     * <p><b>删除必须「接管子节点」</b>（用户 2026-10-29 要求）：
     * 「父子自定义删除了，子自定义不能恢复默认值，只能恢复到祖父内置预设」。
     *
     * <p>做法：把所有 {@code parent == "c<被删名>"} 的条目改成被删者自己的父引用。
     * 这样子的「恢复默认值」会直接落到祖父（内置或更上一层的自定义），
     * 不需要在恢复路径上再写一遍「找不到就往上」的兜底 —— 一次改数据，
     * 比每次恢复都做一次链式查找更可靠（也更容易测）。
     */
    public static String remove(String all, String name) {
        if (name == null || name.isEmpty()) {
            return all == null ? "" : all;
        }
        Entry victim = getEntry(all, name);
        if (victim == null) {
            return all == null ? "" : all;
        }
        String victimRef = customParent(name);
        List<Entry> out = new ArrayList<>();
        for (Entry e : parse(all)) {
            if (e.name.equals(name)) {
                continue;                       // 自己被删掉
            }
            String parent = e.parent;
            if (victimRef.equals(parent)) {
                parent = victim.parent;         // 子节点上移，接到祖父
            }
            out.add(new Entry(e.name, parent, e.snapshot));
        }
        return format(out);
    }

    /**
     * 解析「恢复默认值」的目标 —— 沿父链向上找<b>最近一个还存在的</b>祖先。
     *
     * <p>返回的 {@link Base} 要么 {@link Base#exists() exists()}，要么全空。
     * 全空表示「这条链上没有任何可恢复的东西」（旧数据、手工清空、或环），
     * 调用方必须把按钮置灰 —— <b>绝不回退到某个内置预设</b>，那是本次重构要消灭的撒谎。
     *
     * <p>防环：最多走 {@link #MAX_CHAIN} 步，且记录已访问名字。
     */
    public static Base resolveBase(String all, String name) {
        Entry self = getEntry(all, name);
        if (self == null) {
            return new Base(-1, null);
        }
        String parent = self.parent;
        Set<String> seen = new HashSet<>();
        seen.add(self.name);
        for (int step = 0; step < MAX_CHAIN; step++) {
            if (parent == null || parent.isEmpty()) {
                return new Base(-1, null);      // 无父（旧数据）→ 没有默认值
            }
            if (isBuiltinParent(parent)) {
                int idx;
                try {
                    idx = Integer.parseInt(parent.substring(1));
                } catch (NumberFormatException ex) {
                    return new Base(-1, null);  // 坏引用 → 当作没有
                }
                // 【不在这里校验上界】动画内置表 3 项、颜色内置表 6 项，
                // 而本类是两套预设共用的纯函数层 —— 把某个表的具体长度写死在这里，
                // 颜色一加进来就会误判动画的下标。上界由调用方按自己的表判。
                return idx < 0 ? new Base(-1, null) : new Base(idx, null);
            }
            if (!isCustomParent(parent)) {
                return new Base(-1, null);      // 认不出的引用 → 当作没有
            }
            String ancestorName = parent.substring(1);
            if (!seen.add(ancestorName)) {
                return new Base(-1, null);      // 环 → 放弃，绝不死循环
            }
            Entry ancestor = getEntry(all, ancestorName);
            if (ancestor != null) {
                return new Base(-1, ancestor);  // 找到最近存在的自定义祖先
            }
            // 祖先已被删除（remove 已接管，但手改数据 / 更早版本的数据仍可能出现）
            // → 这条链断了，没有可恢复的目标。
            return new Base(-1, null);
        }
        return new Base(-1, null);
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
