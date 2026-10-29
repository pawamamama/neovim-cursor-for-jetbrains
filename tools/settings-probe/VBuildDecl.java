import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V-BUILD：守「打包时注入的兼容区间」。
 *
 * <h2>它抓的是什么 bug</h2>
 *
 * <p>插件的兼容区间写在 {@code <idea-version since-build="..." until-build="..." />} 里，
 * 由 {@code build/offline-build.ps1} 在打包时注入。这两端各自漏掉的后果都不是「报错」，
 * 而是<b>静默装上了但坏</b>：
 *
 * <ul>
 *   <li>缺 {@code since-build} → 2024.2 以前的 IDE 也放行，然后撞
 *       {@code --release 21} 的字节码锁（JBR 17 读不了 class 65）；</li>
 *   <li>缺 {@code until-build} → 未来所有 IDE 全放行。JetBrains 一旦移除我们用到的
 *       平台 API，用户升级 IDE 后看到的是 {@code NoSuchMethodError} ——
 *       现象像「IDE 坏了」，用户根本不会想到是插件。</li>
 * </ul>
 *
 * <p>这两个坑的共同点是：<b>没有任何东西会因此变红</b>。所以规则得变成探针。
 *
 * <h2>为什么断言「模板」而不是断言「某个 jar」</h2>
 *
 * <p>探针不该依赖本机 AppData 里的产物路径（换台机器就没法跑），
 * 而 {@code offline-build.ps1} 的注入模板是<b>唯一真源</b> —— 所有 jar 都是它生出来的。
 * 守住真源，比守住某一次产物更有意义。
 *
 * <h2>断言清单</h2>
 *
 * <ol>
 *   <li><b>B-0</b> 能定位仓库根，且 {@code build/offline-build.ps1} 存在（找不到就 FAIL，不静默跳过）；</li>
 *   <li><b>B-1</b> 注入模板（含 {@code <idea-version} 的那一行）里有 {@code since-build}；</li>
 *   <li><b>B-2</b> 同一行里有 {@code until-build} —— <b>变异测试主目标</b>，删掉它必须红；</li>
 *   <li><b>B-3</b> 两个值都能解析出来且非空（模板里写的是 {@code $变量} 时，回参数块取默认值）；</li>
 *   <li><b>B-4</b> {@code until-build} 的主版本号 {@code >=} {@code since-build} 的主版本号（防止抄反）；</li>
 *   <li><b>B-5</b> {@code until-build} 必须写成「大版本.*」而不是精确 build 号
 *       （写 {@code 261.25134.95} 会把 2026.1.4 一起拒掉）；</li>
 *   <li><b>B-6</b> {@code build/install.ps1} 的自检段也校验 {@code until-build}
 *       （防止「打包脚本加了、安装自检又退回只打 since-build」这种半截改动）。</li>
 * </ol>
 *
 * <h2>运行前提</h2>
 *
 * <p>需要仓库根：优先读系统属性 {@code neovide.repo}（由 {@code build-v-probes.ps1} 传入），
 * 否则从 {@code user.dir} 向上最多 4 层找 {@code CLAUDE.md}。做法与 {@code VDocCheck} 一致。
 */
public final class VBuildDecl {

    private static int pass;
    private static int fail;

    /** 打包脚本与安装脚本，相对仓库根。 */
    private static final String BUILD_PS1 = "build/offline-build.ps1";
    private static final String INSTALL_PS1 = "build/install.ps1";

    public static void main(String[] args) throws IOException {
        System.out.println("=== V-BUILD：打包注入的兼容区间（since-build / until-build）===");

        Path root = locateRepoRoot();
        if (root == null) {
            check("B-0 必须能定位仓库根（找不到说明门禁的 cwd 不对，而不是构建声明没问题）",
                    false, "未找到 CLAUDE.md；请用 -Dneovide.repo=<仓库根> 指定");
            finish();
            return;
        }
        Path buildPs1 = root.resolve(BUILD_PS1);
        Path installPs1 = root.resolve(INSTALL_PS1);
        check("B-0 仓库根可定位，且 build/offline-build.ps1 存在",
                Files.isRegularFile(buildPs1), root + " → " + BUILD_PS1);
        if (fail > 0) {
            finish();
            return;
        }

        String ps1 = read(buildPs1);

        // ---------------- B-1 / B-2：注入模板里必须有这两个属性 ----------------
        // 【必须取"含 <idea-version 的那一行"】不能对全文 grep 关键字：
        // 参数名 $UntilBuild、注释里提到 until-build 都不代表它真的被注入。
        String template = findIdeaVersionLine(ps1);
        boolean hasSince = template != null && attr(template, "since-build") != null;
        boolean hasUntil = template != null && attr(template, "until-build") != null;
        check("B-1 注入模板（<idea-version> 那一行）含 since-build",
                hasSince, template == null ? "未找到含 <idea-version 且含 since-build 的行" : trim(template));
        check("B-2 注入模板（<idea-version> 那一行）含 until-build（变异测试主目标）",
                hasUntil, hasUntil ? trim(template)
                        : "缺失 → 未来所有 IDE 全放行，API 一移除就是 NoSuchMethodError");

        // ---------------- B-3：两个值都要解析出来且非空 ----------------
        String since = resolve(attr(template, "since-build"), ps1);
        String until = resolve(attr(template, "until-build"), ps1);
        boolean nonEmpty = notBlank(since) && notBlank(until);
        check("B-3 since-build 与 until-build 的值都能解析且非空",
                nonEmpty, "since=[" + since + "] until=[" + until + "]");
        if (!nonEmpty) {
            finish();
            return;
        }

        // ---------------- B-4：上界不得小于下界 ----------------
        Integer sinceMajor = major(since);
        Integer untilMajor = major(until);
        boolean ordered = sinceMajor != null && untilMajor != null && untilMajor >= sinceMajor;
        check("B-4 until-build 的主版本号 >= since-build 的主版本号（防止抄反）",
                ordered, "since=" + since + " 主版本=" + sinceMajor
                        + " / until=" + until + " 主版本=" + untilMajor);

        // ---------------- B-5：上界只锁大版本 ----------------
        // 为什么：until-build="261.25134.95" 会把 2026.1 后续小版本（261.25135.x）一起拒掉，
        // 而 261.x 内部平台 API 是兼容的 —— 这会把「兼容」误伤成「不兼容」。
        boolean coarse = until.endsWith(".*");
        check("B-5 until-build 必须写成「大版本.*」（精确 build 号会误伤同大版本的后续小版本）",
                coarse, "until=" + until + (coarse ? "" : " → 应以 .* 结尾"));

        // ---------------- B-6：安装脚本的自检段也要校验 until-build ----------------
        if (Files.isRegularFile(installPs1)) {
            String install = read(installPs1);
            // 判据是「真的在读它」的形态：属性名带引号出现在一个列表里
            // （脚本里写的是 @('since-build', 'until-build') 这种）。
            // 只在注释里提一句不算 —— 那正是"半截改动"的样子。
            boolean installChecksIt = install.contains("'until-build'") || install.contains("\"until-build\"");
            check("B-6 build/install.ps1 的自检段必须校验 until-build（缺失即报错，不是只打印 since-build）",
                    installChecksIt, installChecksIt ? "已校验" : INSTALL_PS1 + " 里找不到对 until-build 的校验");
        } else {
            check("B-6 build/install.ps1 必须存在", false, INSTALL_PS1 + " 不存在");
        }

        finish();
    }

    // ==================== 辅助 ====================

    private static Path locateRepoRoot() {
        String prop = System.getProperty("neovide.repo");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return p.toAbsolutePath().normalize();
            }
        }
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /**
     * 找注入模板那一行：必须是<b>真的在拼 {@code <idea-version>} 的那一行</b>。
     *
     * <p><b>为什么不能只判「含 &lt;idea-version 且含 since-build」</b>：第一版就是这么写的，
     * 结果匹配到了 {@code offline-build.ps1} 里解释本机制的<b>注释行</b>
     * （{@code # 【兼容区间必须写成一对】since-build 与 until-build 在同一个 <idea-version> 标签上，}）
     * —— 探针自己报 3 条红，而代码其实是对的。
     * <b>这是「探针复刻了错误判据」的经典案例：注释描述机制，不是机制本身。</b>
     *
     * <p>所以判据收紧成三条：不是注释行、含 {@code <idea-version}、含带等号的 {@code since-build=}。
     * 注释里那两句都是"属性名 + 中文"，没有等号，天然排除。
     *
     * <p>找不到返回 null（调用方据此报 FAIL，而不是当"没问题"）。
     */
    private static String findIdeaVersionLine(String ps1) {
        for (String line : ps1.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#")) {
                continue;                       // 注释行一律不算
            }
            if (t.contains("<idea-version") && t.contains("since-build=")) {
                return line;
            }
        }
        return null;
    }

    /**
     * 从模板行里取某个属性的原始值（可能形如 {@code $SinceBuild}）。
     *
     * <p><b>为什么正则里允许一个反引号</b>：模板是 PowerShell 双引号串，
     * 里面的双引号要转义，写成 {@code since-build=`"$SinceBuild`"}。
     * 文件里真实字符是「反引号 + 双引号」，不允许多余字符就永远匹配不上。
     *
     * <p><b>收尾也要允许反引号</b>：值的右侧同样是「反引号 + 双引号」，
     * 第一版只处理了左侧，于是正则要求「值后面紧跟双引号」，而真实文件里紧跟的是反引号 → 永远匹配不上。
     * 同时捕获组用 {@code [^"`]*}（排除双引号与反引号），避免把转义反引号吞进值里
     * （否则会得到 {@code $SinceBuild`}，回参数块查默认值时永远查不到）。
     * 值本身（{@code 261.*} / {@code 242} / {@code $SinceBuild}）从不含反引号，排除掉最省事。
     */
    private static String attr(String template, String name) {
        if (template == null) {
            return null;
        }
        Matcher m = Pattern.compile(name + "\\s*=\\s*`?\\s*\"([^\"`]*)`?\"")
                .matcher(template);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 把模板里的值解析成字面值：{@code $SinceBuild} 这种变量，回参数块的默认值里取。
     * 取不到就返回 null —— 「解析失败」绝不能变成「当作空串继续跑」。
     */
    private static String resolve(String raw, String ps1) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (!v.startsWith("$")) {
            return v;
        }
        String varName = v.substring(1).trim();
        // 形如：  [string]$SinceBuild = '242',
        Matcher m = Pattern.compile("(?m)^\\s*\\[string\\]\\$" + Pattern.quote(varName)
                + "\\s*=\\s*'([^']*)'").matcher(ps1);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 主版本号：{@code "261.*"} → 261，{@code "242"} → 242；没有前导数字返回 null。 */
    private static Integer major(String v) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(v.trim());
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "  [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  [" + detail + "]");
        }
    }

    private static void finish() {
        System.out.printf("=== V-BUILD 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-BUILD RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-BUILD RESULT: ALL PASS");
        System.exit(0);
    }

    private VBuildDecl() {
    }
}
