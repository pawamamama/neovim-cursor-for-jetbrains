import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V-DOC：把「文档里的状态数字」纳入门禁。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>这个项目反复栽在同一件事上：<b>同一份事实有多处副本，且没有东西检查它们是否一致</b>。
 * 已知的三次翻车：{@code NEXT-STEPS} 写着「探针 72/82 通过」（数字来自一份从未执行的脚本）、
 * {@code CLAUDE.md} 写着 v0.9.98（实际已 0.9.102）、2026-10-28 三个 docs 提交在
 * 「写死 hash → 写过期 → 再改」之间空转了三轮。
 *
 * <p>所以这里不写一份新的《文档规范》（那会变成第四个副本），而是<b>把规则变成会红的探针</b>：
 *
 * <ol>
 *   <li><b>D-1</b> 三处状态块声明的版本号必须一致；</li>
 *   <li><b>D-2</b> 三处声明的 {@code V*.java} 数量必须一致，且等于磁盘实测；</li>
 *   <li><b>D-3</b> 三份文档里不得出现写死的 7~40 位十六进制串（历史证据类白名单除外）。</li>
 * </ol>
 *
 * <p><b>为什么 D-1 只查"互相一致"而不查"等于真实版本"</b>：真实版本在 jar 的
 * {@code META-INF/plugin.xml} 里，而探针没有（也不该有）依赖本机 AppData 的路径。
 * 三处一致 + D-2 与磁盘实测对齐，已经能挡住绝大多数漂移；版本号本身靠发布流程保证。
 *
 * <h2>运行前提</h2>
 *
 * <p>需要仓库根：优先读系统属性 {@code neovide.repo}（由 {@code build-v-probes.ps1} 传入），
 * 否则从 {@code user.dir} 向上最多 4 层找 {@code CLAUDE.md}。**找不到就 FAIL，不静默跳过**
 * —— 「探针没跑」和「探针跑了并通过」必须能区分。
 */
public final class VDocCheck {

    private static int pass;
    private static int fail;

    /** 允许出现的写死 hash（历史证据引用，例如 BUG-LOG 里 {@code git show 8fcd895}）。 */
    private static final String[] HISTORICAL_HASH_ALLOWED = {
            // 空 = 三份状态文档里目前一个都不该有。以后要放行历史引用时加在这里并写清原因。
    };

    private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{7,40}\\b");

    public static void main(String[] args) throws IOException {
        System.out.println("=== V-DOC：状态数字三处一致 + 不写死 hash ===");

        Path root = locateRepoRoot();
        if (root == null) {
            check("D-0 必须能定位仓库根（找不到说明门禁的 cwd 不对，而不是文档没问题）",
                    false, "未找到 CLAUDE.md；请用 -Dneovide.repo=<仓库根> 指定");
            finish();
            return;
        }
        Path claude = root.resolve("CLAUDE.md");
        Path start = root.resolve("START-HERE.md");
        Path next = root.resolve("docs/NEXT-STEPS.md");
        check("D-0 三份状态文档必须都存在", Files.isRegularFile(claude)
                        && Files.isRegularFile(start) && Files.isRegularFile(next),
                root.toString());
        if (fail > 0) {
            finish();
            return;
        }

        // ---------------- D-1：版本号三处一致 ----------------
        // 【必须 MULTILINE】三处都是"文件里某一行"，不加 MULTILINE 时 ^ 只匹配全文开头，
        // 于是 CLAUDE.md 永远取到 null —— 自检时就是这么漏掉的（D-1 报 CLAUDE.md=null）。
        int ml = Pattern.MULTILINE;
        String vClaude = firstMatch(read(claude),
                Pattern.compile("^版本\\s+v(\\d+\\.\\d+\\.\\d+)", ml));
        String vStart = firstMatch(read(start), Pattern.compile("版本：v(\\d+\\.\\d+\\.\\d+)"));
        String vNext = firstMatch(read(next),
                Pattern.compile("\\|\\s*版本\\s*\\|\\s*\\*\\*v(\\d+\\.\\d+\\.\\d+)\\*\\*"));
        boolean versionAgree = vClaude != null && vClaude.equals(vStart) && vClaude.equals(vNext);
        check("D-1 三处状态块的版本号必须一致",
                versionAgree,
                "CLAUDE.md=" + vClaude + " / START-HERE.md=" + vStart + " / NEXT-STEPS.md=" + vNext);

        // ---------------- D-2：探针数量三处一致，且等于实测 ----------------
        long actual = countProbes(root.resolve("tools/settings-probe"));
        String cClaude = firstMatch(read(claude),
                Pattern.compile("^探针\\s+(\\d+)\\s*个\\s*V\\*", ml));
        // 【全角括号】START-HERE 用的是 "（94 个 V*.java）"，只认半角会取到 null
        String cStart = firstMatch(read(start),
                Pattern.compile("[（(](\\d+)\\s*个\\s*V\\*\\.java[）)]"));
        String cNext = firstMatch(read(next),
                Pattern.compile("\\|\\s*探针文件\\s*\\|\\s*\\*\\*(\\d+)\\s*个"));
        boolean countAgree = String.valueOf(actual).equals(cClaude)
                && String.valueOf(actual).equals(cStart)
                && String.valueOf(actual).equals(cNext);
        check("D-2 三处声明的 V*.java 数量必须一致，且等于磁盘实测",
                countAgree,
                "实测=" + actual + " / CLAUDE.md=" + cClaude + " / START-HERE.md=" + cStart
                        + " / NEXT-STEPS.md=" + cNext);

        // ---------------- D-3：不写死 hash ----------------
        List<String> offenders = new ArrayList<>();
        scanHex(claude, root, offenders);
        scanHex(start, root, offenders);
        scanHex(next, root, offenders);
        check("D-3 三份状态文档不得写死 7~40 位十六进制串（历史证据白名单除外）",
                offenders.isEmpty(),
                offenders.isEmpty() ? "无" : String.join("; ", offenders));

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

    /** 返回第一个捕获组；找不到返回 null（调用方据此报 FAIL，而不是当成"没问题"）。 */
    private static String firstMatch(String text, Pattern p) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static long countProbes(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return -1;
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith("V") && n.endsWith(".java");
            }).count();
        }
    }

    private static void scanHex(Path file, Path root, List<String> out) throws IOException {
        String[] lines = read(file).split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            Matcher m = HEX.matcher(lower);
            while (m.find()) {
                String hex = m.group();
                // 【排除紧跟冒号的十六进制】自检实测误报：CLAUDE.md 里那句
                // "error:0A000126 SSL unexpected eof" 是 OpenSSL 错误码，不是提交号。
                // 正常写 hash 时前面不会紧贴冒号（一般是反引号/空格/"见"）。
                if (m.start() > 0 && lower.charAt(m.start() - 1) == ':') {
                    continue;
                }
                if (!allowed(hex)) {
                    out.add(root.relativize(file) + ":" + (i + 1) + " → " + hex);
                }
            }
        }
    }

    private static boolean allowed(String hex) {
        for (String a : HISTORICAL_HASH_ALLOWED) {
            if (a.equals(hex)) {
                return true;
            }
        }
        return false;
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
        System.out.printf("=== V-DOC 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-DOC RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-DOC RESULT: ALL PASS");
        System.exit(0);
    }

    private VDocCheck() {
    }
}
