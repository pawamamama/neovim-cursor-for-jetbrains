import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorBundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V-I18N —— 界面文案资源包的守门探针（2026-11-01）。
 *
 * <p>守四件事：
 * <ol>
 *   <li>root（英文）与 {@code _zh_CN} 的<b>键集合完全一致</b> —— 少一条中文就会在中文界面显示 key；</li>
 *   <li>生产 UI 源码里出现的每个 {@code settings.* / info.* / action.*} 键都真实存在
 *       （拼错、改了 key 没改 properties，都会在这里红）；</li>
 *   <li>面板源码里<b>不再有中文 UI 字面量</b>（AC-001）—— 只扫 UI 调用点，
 *       日志/注释/异常 message 不在范围内；</li>
 *   <li>两套 locale 真能取到不同的值（防止「中文系统上英文界面也变中文」那类回退 bug）。</li>
 * </ol>
 *
 * <p><b>两套 locale 用纯 {@link ResourceBundle} 验</b>：生产门面跟随当前界面语言，
 * 一次只能验一个。加载控制与生产门面使用同一个 {@code getNoFallbackControl}，
 * 读的是同一份 properties。另外 I-10 直接调生产门面、在同一个 JVM 里切换默认
 * locale，专门证明「生产门面真的会跟随 locale」（删掉 CONTROL 会当场变红）。
 */
public final class VI18n {

    private static final String PATH = "messages.NeovideCursorBundle";

    private static final ResourceBundle.Control CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static final Path REPO = Paths.get(System.getProperty("neovide.repo", "."));

    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    /** MessageFormat 占位符下标（{@code {0}} / {@code {0,number}}）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[,}]");

    /** 界面里用到的 key 字面量（含 RESTORE_KEY 这种常量赋值）。 */
    private static final Pattern KEY_LITERAL =
            Pattern.compile("\"((?:settings|info|action)\\.[A-Za-z0-9_.]+)\"");

    /** UI 调用点里带中文的字面量：只扫这些形态，日志不在内。 */
    private static final Pattern UI_CHINESE = Pattern.compile(
            "(?s)(?:\\.row|\\.group|rowComment|addTab"
                    + "|new\\s+(?:javax\\.swing\\.)?J(?:Button|Label|CheckBox)"
                    + "|\\.label|\\.comboBox|setText|setToolTipText|link"
                    + "|Messages\\.show\\w*)\\s*\\([^;]{0,300}?\"[^\"]*\\p{IsHan}[^\"]*\"");

    private static final String[] UI_SOURCES = {
            "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/settings/NeovideCursorConfigurable.java",
            "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/settings/PluginInfoBar.java",
            "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/TogglePaintDiagAction.java",
    };

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws IOException {
        System.out.println("=== V-I18N：界面文案资源包 / 键契约 / 零中文 UI 字面量 ===");

        // 先证明「验的是这一份代码」：探针编译产物必须位于 neovide.repo 下。
        // worktree 里漏传 -Repo 时，脚本默认指向主仓库 —— 探针会拿主仓库源码
        // 去验 worktree 的字节码，I-6..I-9 全绿但验错了对象（CLAUDE.md 陷阱 4 的同类）。
        Path codeRoot = codeLocation();
        Path repoRoot = REPO.toAbsolutePath().normalize();
        boolean repoMatches = codeRoot != null && codeRoot.startsWith(repoRoot);
        check("I-0 探针编译产物位于 neovide.repo 下（防 worktree 验错代码）",
                repoMatches, "repo=" + repoRoot + " code=" + codeRoot);
        if (!repoMatches) {
            summary();
            return;
        }

        ResourceBundle en;
        ResourceBundle zh;
        try {
            en = ResourceBundle.getBundle(PATH, Locale.ENGLISH,
                    VI18n.class.getClassLoader(), CONTROL);
            zh = ResourceBundle.getBundle(PATH, Locale.SIMPLIFIED_CHINESE,
                    VI18n.class.getClassLoader(), CONTROL);
        } catch (Throwable t) {
            check("I-1/2 两套资源包都能加载", false, t.toString());
            summary();
            return;
        }
        check("I-1 英文 root 资源包可加载（" + en.keySet().size() + " 个键）", true, "ok");
        check("I-2 中文 _zh_CN 资源包可加载（" + zh.keySet().size() + " 个键）", true, "ok");

        Set<String> enKeys = new TreeSet<>(en.keySet());
        Set<String> zhKeys = new TreeSet<>(zh.keySet());
        Set<String> missingZh = new TreeSet<>(enKeys);
        missingZh.removeAll(zhKeys);
        Set<String> extraZh = new TreeSet<>(zhKeys);
        extraZh.removeAll(enKeys);
        check("I-3 两套键集合完全一致",
                missingZh.isEmpty() && extraZh.isEmpty(),
                (missingZh.isEmpty() ? "" : "中文缺 " + missingZh + "；")
                        + (extraZh.isEmpty() ? "" : "中文多 " + extraZh));

        // I-3b：同一个键的中英文占位符下标集合必须一致。
        // 漏写一个 {2} 时 MessageFormat 不报错，只是那个数字永远不出现。
        List<String> placeholderMismatch = new ArrayList<>();
        for (String key : enKeys) {
            if (!zhKeys.contains(key)) {
                continue;
            }
            Set<Integer> enPh = placeholders(en.getString(key));
            Set<Integer> zhPh = placeholders(zh.getString(key));
            if (!enPh.equals(zhPh)) {
                placeholderMismatch.add(key + " en=" + enPh + " zh=" + zhPh);
            }
        }
        check("I-3b 同一键的中英文占位符下标集合一致",
                placeholderMismatch.isEmpty(), placeholderMismatch.toString());

        String tabEn = en.getString("settings.tab.master");
        String tabZh = zh.getString("settings.tab.master");
        check("I-4 同一键在两套 locale 下取值不同（无默认 locale 回退）",
                !tabEn.equals(tabZh), "en=" + tabEn + " / zh=" + tabZh);
        check("I-5 中文值确实是中文", HAN.matcher(tabZh).find(), tabZh);

        // ---------- 源码里的 key 字面量必须真实存在 ----------
        Set<String> used = new TreeSet<>();
        List<String> unreadable = new ArrayList<>();
        for (String rel : UI_SOURCES) {
            Path file = REPO.resolve(rel);
            if (!Files.isRegularFile(file)) {
                unreadable.add(rel);
                continue;
            }
            Matcher m = KEY_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                String key = m.group(1);
                // 动态 key 的前缀（如 "settings.preset.anim." + index）不是完整 key，
                // 它的真实键由 animPresetDisplayName/colorPresetDisplayName 拼出来。
                if (!key.endsWith(".")) {
                    used.add(key);
                }
            }
        }
        check("I-6 UI 源码文件齐全", unreadable.isEmpty(), "缺：" + unreadable);

        Set<String> missingUsedEn = new TreeSet<>();
        Set<String> missingUsedZh = new TreeSet<>();
        for (String key : used) {
            if (!enKeys.contains(key)) {
                missingUsedEn.add(key);
            }
            if (!zhKeys.contains(key)) {
                missingUsedZh.add(key);
            }
        }
        check("I-7 源码引用的每个 key 在英文包中存在（共 " + used.size() + " 个）",
                missingUsedEn.isEmpty(), "缺：" + missingUsedEn);
        check("I-8 源码引用的每个 key 在中文包中存在",
                missingUsedZh.isEmpty(), "缺：" + missingUsedZh);

        // 动态 key（settings.preset.anim.<下标> / color.<下标>）不在源码字面量里，
        // 单独按 CursorPresets 的长度逐个校验：新增内置预设忘配 key 会在这里红，
        // 否则 containsKey 会静默回退到数据层中文名（英文界面出现中文）。
        List<String> missingPresetKeys = new ArrayList<>();
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            String key = "settings.preset.anim." + i;
            if (!enKeys.contains(key) || !zhKeys.contains(key)) {
                missingPresetKeys.add(key);
            }
        }
        for (int i = 0; i < CursorPresets.COLOR_PRESETS.length; i++) {
            String key = "settings.preset.color." + i;
            if (!enKeys.contains(key) || !zhKeys.contains(key)) {
                missingPresetKeys.add(key);
            }
        }
        check("I-8b 每个内置预设都有中英文显示名 key",
                missingPresetKeys.isEmpty(), missingPresetKeys.toString());

        // ---------- 面板源码零中文 UI 字面量 ----------
        List<String> offenders = new ArrayList<>();
        for (String rel : UI_SOURCES) {
            Path file = REPO.resolve(rel);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = UI_CHINESE.matcher(text);
            while (m.find()) {
                String snippet = m.group().replaceAll("\\s+", " ");
                if (snippet.length() > 120) {
                    snippet = snippet.substring(0, 120) + "…";
                }
                offenders.add(file.getFileName() + ": " + snippet);
            }
        }
        check("I-9 面板 UI 调用点零中文字面量（AC-001）",
                offenders.isEmpty(), offenders.isEmpty() ? "ok" : offenders.toString());

        // I-10：直接调生产门面，证明它真的会随默认 locale 切换。
        // 若生产门面丢掉 getNoFallbackControl，在中文系统上请求英语会拿到 _zh_CN，
        // 这条当场变红（探针自己加载的 en/zh 不会受影响，所以必须走生产门面）。
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            String viaEn = NeovideCursorBundle.message("settings.tab.master");
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            String viaZh = NeovideCursorBundle.message("settings.tab.master");
            check("I-10 生产门面随默认 locale 切换（删 CONTROL 会红）",
                    viaEn.equals(en.getString("settings.tab.master"))
                            && viaZh.equals(zh.getString("settings.tab.master")),
                    "en=" + viaEn + " / zh=" + viaZh);
        } finally {
            Locale.setDefault(saved);
        }

        summary();
    }

    /** 探针类文件所在目录（编译产物），用于证明验的是当前工作树的代码。 */
    private static Path codeLocation() {
        try {
            return Paths.get(VI18n.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 抽出一段文案里的 MessageFormat 占位符下标集合。 */
    private static Set<Integer> placeholders(String s) {
        Set<Integer> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    /**
     * 把 {@code //} 与 {@code /* *\/} 注释替换成空格（保留换行与字符串字面量）。
     *
     * <p>为什么必须剥：面板里大量中文注释，原正则的 {@code [^;]{0,300}?} 窗口
     * 会把「调用点之后某条注释里的中文」误当成「调用点里的中文字符串」。
     * 只做这一件事，不试图解析 Java 语法。
     */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean line = false;
        boolean block = false;
        boolean str = false;
        boolean chr = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            char d = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                i++;
            } else if (block) {
                if (c == '*' && d == '/') {
                    out.append("  ");
                    i += 2;
                    block = false;
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (str || chr) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    out.append(d);
                    i += 2;
                } else {
                    if (str && c == '"') {
                        str = false;
                    } else if (chr && c == '\'') {
                        chr = false;
                    }
                    i++;
                }
            } else if (c == '/' && d == '/') {
                out.append("  ");
                i += 2;
                line = true;
            } else if (c == '/' && d == '*') {
                out.append("  ");
                i += 2;
                block = true;
            } else {
                out.append(c);
                if (c == '"') {
                    str = true;
                } else if (c == '\'') {
                    chr = true;
                }
                i++;
            }
        }
        return out.toString();
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

    private static void summary() {
        System.out.println();
        System.out.printf("=== V-I18N 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-I18N RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-I18N RESULT: ALL PASS");
        System.exit(0);
    }
}
