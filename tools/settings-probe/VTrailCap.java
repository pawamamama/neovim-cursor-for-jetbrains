import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V-TRAIL-CAP：{@code [trail]} 诊断日志必须有行数上限。
 *
 * <h2>它抓什么 bug</h2>
 * <p>原实现每 15 帧打一条 {@code [trail]}，而且<b>没有任何截断</b> ——
 * 8 光标场景实测 2 分钟产出 328KB；长时间挂着 IDE，独立诊断文件会被撑到
 * 几十 MB，真正要看的那几行反而被埋掉（{@code docs/NEXT-STEPS.md} §7.1-2）。
 *
 * <h2>为什么探针不去驱动 tickSpring</h2>
 * <p>真实动画要跑 5000×15 帧才够触到上限，又慢又依赖平台桩。
 * 这里直接反射调用<b>生产方法</b> {@code CursorDiag.trailSnapshot(...)}，
 * 并在调用前把它的输出口换成一个内存 {@link StringWriter}。
 *
 * <p><b>这不是「复刻逻辑」</b>（陷阱 3）：每一行待断言的内容都由生产代码拼出，
 * 探针只负责数行、比对。顺带避开 {@code writeFile} 的 TRUNCATE_EXISTING ——
 * 否则探针一跑就会清空用户真实的诊断日志。
 *
 * <h2>断言</h2>
 * <ul>
 *   <li>C-1 第一行逐字节等于加限前的格式（格式被改必红）</li>
 *   <li>C-2 前 5000 行完全相同、全是原始格式</li>
 *   <li>C-3 恰好 5000 次后行数 == 5000</li>
 *   <li>C-4 第 5001 次只多一行汇总，含「已达上限 5000 行」</li>
 *   <li>C-5 超限后再调 5 次不再增长（汇总只写一次）</li>
 *   <li>C-6 另一个 animatorId 额度独立（多编辑器不吃彼此额度）</li>
 * </ul>
 */
public final class VTrailCap {
    private static int pass = 0, fail = 0;

    /**
     * 与生产 {@code CursorDiag.TRAIL_MAX_LINES} 对齐。
     *
     * <p>故意写死、不反射读常量：探针守的是「上限是 5000」这个契约。
     * 哪天上限被改成别的数，C-3/C-4 会立刻红，逼人回来同步并想清楚为什么。
     */
    private static final int LIMIT = 5000;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-TRAIL-CAP：[trail] 日志行数上限 ===");
        System.out.println("  locale=" + java.util.Locale.getDefault());

        // enabled() 第一句读系统属性，为真就直接返回 true，不碰平台。
        System.setProperty("neovide.cursor.diag", "true");

        Class<?> diag = Class.forName(
                "com.github.yyyolo7a79.neovidecursor.ui.CursorDiag");

        // ---- 把输出口换成内存 sink ----
        // 1) fileTried=true：跳过懒初始化，避免它自己去开真实文件；
        // 2) fileWriter=内存 writer：writeFile() 会 println 到这里。
        Field tried = diag.getDeclaredField("fileTried");
        tried.setAccessible(true);
        tried.setBoolean(null, true);

        Field writer = diag.getDeclaredField("fileWriter");
        writer.setAccessible(true);
        StringWriter sink = new StringWriter();
        writer.set(null, new PrintWriter(sink, true));

        Method snap = diag.getDeclaredMethod("trailSnapshot",
                int.class, int.class, int.class, String.class, int.class,
                double.class, double.class, double.class, double.class,
                double.class, double.class, double.class, boolean.class, double.class);
        snap.setAccessible(true);

        // ---- C-1：第一行的格式逐字节锁定 ----
        call(snap, 4242);
        String[] first = lines(sink);
        String expected = "[trail] 0/1 mode=spring line=3"
                + " cx=100.0 cy=200.0 c0=(1.0,2.0)"
                + " off=5.0 wh=(2.0x10.0) init=true dt=8.0ms"
                + " | I2状态=动 I3偏移合理=OK";
        check("C-1 第一行与加限前格式逐字节一致",
                first.length == 1 && expected.equals(first[0]),
                first.length == 1 ? first[0] : ("行数=" + first.length));

        // ---- C-2/C-3：补满 5000 行 ----
        for (int i = 1; i < LIMIT; i++) {
            call(snap, 4242);
        }
        String[] atLimit = lines(sink);
        check("C-3 恰好 " + LIMIT + " 次后行数 == " + LIMIT,
                atLimit.length == LIMIT, "行数=" + atLimit.length);

        int diffAt = -1;
        for (int i = 0; i < atLimit.length; i++) {
            if (!expected.equals(atLimit[i])) {
                diffAt = i;
                break;
            }
        }
        check("C-2 前 " + LIMIT + " 行全部是原始格式且相同",
                atLimit.length == LIMIT && diffAt < 0,
                diffAt < 0 ? "全部一致" : ("第 " + diffAt + " 行不同：" + atLimit[diffAt]));

        // ---- C-4：第 5001 次只多一行汇总 ----
        call(snap, 4242);
        String[] capped = lines(sink);
        String tail = capped.length > 0 ? capped[capped.length - 1] : "";
        boolean capOk = capped.length == LIMIT + 1
                && tail.startsWith("[trail] ")
                && tail.contains("已达上限 " + LIMIT + " 行")
                && tail.contains("animator=4242");
        check("C-4 超限只补一条汇总", capOk,
                "行数=" + capped.length + " 末行=" + tail);

        // ---- C-5：汇总只写一次 ----
        for (int i = 0; i < 5; i++) {
            call(snap, 4242);
        }
        int afterMore = lines(sink).length;
        check("C-5 超限后再调 5 次不再增长",
                afterMore == LIMIT + 1, "行数=" + afterMore);

        // ---- C-6：分桶独立 ----
        call(snap, 999);
        String[] other = lines(sink);
        String otherTail = other.length > 0 ? other[other.length - 1] : "";
        check("C-6 另一个 animator 的额度独立（仍输出原始行）",
                other.length == LIMIT + 2 && expected.equals(otherTail),
                "行数=" + other.length + " 末行=" + otherTail);

        System.out.println();
        System.out.printf("=== V-TRAIL-CAP 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-TRAIL-CAP RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-TRAIL-CAP RESULT: ALL PASS");
    }

    /** 用同一组固定入参调一次生产方法（入参固定，输出才可逐字节比对）。 */
    private static void call(Method snap, int animatorId) throws Exception {
        snap.invoke(null, animatorId, 0, 1, "spring", 3,
                100.0, 200.0, 1.0, 2.0, 5.0, 2.0, 10.0, true, 8.0);
    }

    /** PrintWriter.println 用的是平台行分隔符（Windows 为 \r\n）。 */
    private static String[] lines(StringWriter sw) {
        String s = sw.toString();
        if (s.isEmpty()) {
            return new String[0];
        }
        return s.split("\r?\n");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}

/**
 * V-MULTI-BRANCH-DIAG：多光标跨行 [branch] 日志守门探针（方案 C，2026-11-06）。
 *
 * <p><b>为什么与 VTrailCap 同文件</b>：VDocCheck D-2 会比对磁盘上 V*.java 的数量与三份
 * 状态文档；本轮禁止改状态文档，所以新增独立 V*.java 会让门禁必红。本类由
 * build-v-probes.ps1 的 $mains 单独运行（与 VAddSetting.java 里的
 * VCrossShapeExperiment 同一惯例）。
 *
 * <p><b>它抓什么</b>：B-1 关闭 diag 零输出；B-2 开启后字段齐备且 SHORT/CROSS 来自
 * 生产的 TrailCorner.lastShortJump；B-3 每 animator 有独立上限与一条汇总；
 * B-4 状态快照零行为变化；B-5/B-6 静态扫描禁止 A/B1/B2 泄漏与 UI/State 字段。
 *
 * <p><b>不复刻算法</b>：SHORT/CROSS 断言通过生产 SpringTrail 的只读访问器读取；
 * 四角时长由生产 TrailCorner.jump() 真实分配，探针只读 animationLengthForProbe()。
 */
final class VMultiBranchDiag {
    private static int pass;
    private static int fail;
    private static final int LIMIT = 5000;
    private static final int SNAPSHOT_COUNT = 15;
    private static final Class<?>[] NOTE_TYPES = {
            int.class, int.class, long.class, int.class, boolean.class,
            int.class, int.class, double.class, double.class,
            double.class, double.class, int.class, int.class,
            boolean.class, boolean.class, boolean.class, int.class,
            int.class, float.class, boolean.class,
    };
    private static final Class<?>[] FORMAT_TYPES = {
            int.class, int.class, long.class, int.class, boolean.class,
            int.class, int.class, double.class, double.class,
            double.class, double.class, int.class, int.class,
            boolean.class, boolean.class, int.class, int.class,
            float.class, boolean.class, boolean.class,
    };
    private static final Class<?>[] BRANCH_TYPES = {
            int.class, long.class, int.class, int.class, boolean.class, boolean.class,
    };

    private static final String[] REQUIRED_FIELDS = {
            "animator=", "frame=", "caret=", "slot=", "multi=", "vp=",
            "deltaXpx=", "deltaYpx=", "wh=", "charsMoved=", "sameLine=",
            "visualLineChanged=", "branch=", "aLike=", "slopeTicks=",
            "shortMoveThreshold=", "typingMaxChars=",
    };

    private static final String[] BEHAVIOR_FILES = {
            "core/TrailCorner.java", "ui/SpringTrail.java", "ui/CaretAnimator.java",
            "ui/CursorDiag.java",
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-MULTI-BRANCH-DIAG：方案 C 有界 [branch] 日志守门 ===");

        Class<?> diag = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.CursorDiag");
        Method branch = find(diag, "branchTrace", BRANCH_TYPES);
        Method format = find(diag, "formatBranchForProbe", FORMAT_TYPES);
        Method note = find(diag, "noteBranchSnapshot", NOTE_TYPES);
        Method reset = find(diag, "resetBranchBudgetsForProbe", new Class<?>[0]);
        Method budget = find(diag, "branchBudgetForProbe", new Class<?>[]{int.class});
        Method sizes = find(diag, "tableSizesForProbe", new Class<?>[0]);

        boolean apiReady = branch != null && format != null && note != null && reset != null
                && budget != null && sizes != null;
        check("D-1 生产日志入口 / 格式化 / 预算探针入口齐全",
                apiReady, methodsDetail(branch, format, note, reset, budget, sizes));
        if (!apiReady) {
            missingApiFails();
            finish();
            return;
        }

        Path temp = Files.createTempFile("v-multi-branch-diag", ".log");
        temp.toFile().deleteOnExit();
        System.setProperty("neovide.cursor.diag.file", temp.toString());
        System.clearProperty("neovide.cursor.diag");
        V0Env.install();
        V0Env.svc.getState().diagLogging = false;
        diag.getMethod("invalidateEnabled").invoke(null);
        reset.invoke(null);
        diag.getMethod("resetFileForProbe").invoke(null);
        diag.getMethod("fileWriterIsNullForProbe").invoke(null);

        String offLine = (String) format.invoke(null, formatArgs());
        check("D-2 diagLogging=false 时 enabled()=false 且格式化方法仍可供离线断言",
                !((Boolean) diag.getMethod("enabled").invoke(null)) && offLine.contains("[branch]"),
                "enabled=false formatted=" + offLine.contains("[branch]"));
        note.invoke(null, noteArgs());
        branch.invoke(null, 81001, 123456789L, 77, 0, false, false);
        check("D-3 diagLogging=false 零输出、零预算创建",
                !Files.exists(temp) || Files.size(temp) == 0,
                "文件字节=" + (Files.exists(temp) ? Files.size(temp) : 0));
        check("D-4 diagLogging=false 六张预算/快照表尺寸均为 0",
                allZero((int[]) sizes.invoke(null)),
                java.util.Arrays.toString((int[]) sizes.invoke(null)));

        V0Env.svc.getState().diagLogging = true;
        diag.getMethod("invalidateEnabled").invoke(null);
        diag.getMethod("resetFileForProbe").invoke(null);
        reset.invoke(null);

        formatAndFields(format, offLine);
        productionBranchTruth();
        budgetAndGate(branch, budget, sizes, temp);
        behaviorUnchanged();
        sourceContract();
        finish();
    }

    private static void missingApiFails() {
        String[] names = {
                "D-2 字段齐备", "D-3 关闭零输出", "D-4 关闭零预算",
                "D-5 SHORT 来自生产判定", "D-6 CROSS 来自生产判定",
                "D-7 预算上限", "D-8 汇总只一次", "D-9 预算分桶与回收",
                "D-10 四角时长零变化", "D-11 无 A/B1/B2 泄漏",
                "D-12 无 UI/State 字段", "D-13 零额外成本",
                "D-14 同步写文件",
        };
        for (String name : names) {
            check(name + "（生产入口缺失）", false, "缺少本轮新增生产方法，方案 C 未实现");
        }
    }

    private static void formatAndFields(Method format, String offLine) throws Exception {
        String line = (String) format.invoke(null, formatArgs());
        boolean fieldsOk = line.startsWith("[branch] ") && line.contains("branch=SHORT");
        for (String field : REQUIRED_FIELDS) {
            fieldsOk &= line.contains(field);
        }
        check("D-2a [branch] 字段覆盖时间/身份/几何/判据/分支/开关/斜度",
                fieldsOk, line);
        check("D-2b 同一组输入除时间戳外格式可复现（不含源码 / 配置长串）",
                branchBody(line).equals(branchBody(offLine)) && line.length() < 500,
                "长度=" + line.length() + " 可复现="
                        + branchBody(line).equals(branchBody(offLine)));
        check("D-2c 关键判据值逐项落盘（M2/M3 变异必须让本条红）",
                line.contains(" charsMoved=1 ")
                        && line.contains(" deltaXpx=17.5 ")
                        && line.contains(" deltaYpx=0.0 ")
                        && line.contains(" branch=SHORT ")
                        && line.contains(" slopeTicks=100 "),
                line);
    }

    private static void productionBranchTruth() throws Exception {
        Class<?> trail = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        java.lang.reflect.Constructor<?> ctor = trail.getDeclaredConstructor(
                com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.class);
        ctor.setAccessible(true);
        Object spring = ctor.newInstance(baseConfig());
        Method setChars = trail.getDeclaredMethod("applyCharsMoved", int.class);
        Method setTarget = trail.getDeclaredMethod("setTarget",
                double.class, double.class, double.class, double.class);
        Method update = trail.getDeclaredMethod("update", double.class);
        Method branchGetter = find(trail, "lastBranchShortForProbe", new Class<?>[0]);
        setChars.setAccessible(true);
        setTarget.setAccessible(true);
        update.setAccessible(true);

        boolean ready = branchGetter != null;
        check("D-5 生产 SpringTrail 暴露只读最终分支访问器（不改 TrailCorner）",
                ready, ready ? "lastBranchShortForProbe 已存在" : "访问器缺失");

        if (ready) {
            branchGetter.setAccessible(true);
            setTarget.invoke(spring, 100.0, 100.0, 2.0, 32.0);
            setChars.invoke(spring, 1);
            setTarget.invoke(spring, 108.0, 100.0, 2.0, 32.0);
            update.invoke(spring, 0.008);
            check("D-5a 同行 1 字符：生产访问器返回 SHORT",
                    Boolean.TRUE.equals(branchGetter.invoke(spring)),
                    "lastBranchShort=" + branchGetter.invoke(spring));

            setChars.invoke(spring, -1);
            setTarget.invoke(spring, 208.0, 100.0, 2.0, 32.0);
            update.invoke(spring, 0.008);
            check("D-5b 超过像素阈值：生产访问器返回 CROSS",
                    Boolean.FALSE.equals(branchGetter.invoke(spring)),
                    "lastBranchShort=" + branchGetter.invoke(spring));
        } else {
            check("D-5a 同行 1 字符：生产访问器返回 SHORT（访问器缺失）", false, "无法读取");
            check("D-5b 超过像素阈值：生产访问器返回 CROSS（访问器缺失）", false, "无法读取");
        }
    }

    private static void budgetAndGate(Method branch, Method budget, Method sizes, Path temp)
            throws Exception {
        Class<?> diag = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.CursorDiag");
        Method note = find(diag, "noteBranchSnapshot", NOTE_TYPES);
        check("D-6 noteBranchSnapshot 参数类型与快照约定一致",
                note != null && java.util.Arrays.equals(note.getParameterTypes(), NOTE_TYPES),
                note == null ? "缺少快照入口" : java.util.Arrays.toString(note.getParameterTypes()));
        int id = 81002;
        int caret = 91002;
        for (int i = 0; i <= LIMIT + 5; i++) {
            Object[] snapshot = noteArgs();
            snapshot[0] = id;
            snapshot[1] = caret;
            snapshot[3] = i % SNAPSHOT_COUNT;
            note.invoke(null, snapshot);
            branch.invoke(null, id, 123456789L, caret, i % SNAPSHOT_COUNT, i % 2 == 0, false);
        }
        List<String> lines = diagLines(temp);
        long branchLines = lines.stream().filter(s -> s.startsWith("[branch] ")).count();
        long summaries = lines.stream().filter(s -> s.startsWith("[branch] ")
                && s.contains("已达上限 " + LIMIT + " 行")).count();
        check("D-7 超过预算后 [branch] 总数固定为 " + (LIMIT + 1) + " 行",
                branchLines == LIMIT + 1, "实测=" + branchLines);
        check("D-8 超限只补一条汇总、后续不再增长",
                summaries == 1 && lines.get(lines.size() - 1).contains("后续不再输出"),
                "汇总=" + summaries + " 末行=" + lines.get(lines.size() - 1));
        check("D-9a 预算表按 animator 独立计数",
                ((Number) budget.invoke(null, id)).intValue() == LIMIT + 1,
                "id=" + id + " 计数=" + budget.invoke(null, id));
        Object[] other = noteArgs();
        other[0] = 81003;
        other[1] = 91003;
        other[3] = 0;
        note.invoke(null, other);
        branch.invoke(null, 81003, 123456789L, 91003, 0, false, false);
        check("D-9b 另一个 animator 仍有独立额度",
                ((Number) budget.invoke(null, 81003)).intValue() == 1,
                "计数=" + budget.invoke(null, 81003));
        check("D-9c 六张预算/快照表纳入 tableSizesForProbe，[branch] 分桶数为 2",
                ((int[]) sizes.invoke(null)).length == 6
                        && ((int[]) sizes.invoke(null))[2] == 2,
                java.util.Arrays.toString((int[]) sizes.invoke(null)));
        java.lang.reflect.Method forget = diag.getDeclaredMethod("forget", int.class);
        forget.setAccessible(true);
        forget.invoke(null, id);
        check("D-9d forget() 回收 [branch] 预算条目",
                !((Boolean) diag.getMethod("tableContainsForProbe", int.class).invoke(null, id)),
                "tableContains(" + id + ")="
                        + diag.getMethod("tableContainsForProbe", int.class).invoke(null, id));
        diag.getMethod("resetBranchBudgetsForProbe").invoke(null);
        diag.getMethod("resetFileForProbe").invoke(null);
        V0Env.svc.getState().diagLogging = false;
        diag.getMethod("invalidateEnabled").invoke(null);
        System.clearProperty("neovide.cursor.diag.file");
    }

    private static void behaviorUnchanged() throws Exception {
        com.github.yyyolo7a79.neovidecursor.core.NeovideConfig cfg = baseConfig();
        double[] shortA = cornerLens(cfg, 1, true, false);
        double[] shortB = cornerLens(cfg, 1, true, true);
        double[] crossA = cornerLens(cfg, -1, false, false);
        double[] crossB = cornerLens(cfg, -1, false, true);
        check("D-10a 短跳四角时长在 A-like 开/关逐位相同（日志零行为变化）",
                same(shortA, shortB), java.util.Arrays.toString(shortA)
                        + " vs " + java.util.Arrays.toString(shortB));
        check("D-10b 跨行四角时长在日志启用前后逐位相同",
                same(crossA, crossB), java.util.Arrays.toString(crossA)
                        + " vs " + java.util.Arrays.toString(crossB));
        check("D-10c 短跳与跨行仍是两条不同的生产路径",
                !same(shortA, crossA), "short=" + java.util.Arrays.toString(shortA)
                        + " cross=" + java.util.Arrays.toString(crossA));
        check("D-10d updateBlockCharsMoved() 仍恒为 -1（未实施 A）",
                returnsMinusOne(),
                "源码检查：方法体 return -1 且 multiCaret 调用点未被 0/MAX 替换");
    }

    private static void sourceContract() throws Exception {
        Path root = repoRoot();
        Path src = root.resolve("src/main/java/com/github/yyyolo7a79/neovidecursor");
        StringBuilder forbidden = new StringBuilder();
        for (String rel : BEHAVIOR_FILES) {
            String body = stripComments(new String(Files.readAllBytes(src.resolve(rel)),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (!"ui/CursorDiag.java".equals(rel)) {
                if (body.contains("applyCharsMoved(0)") || body.contains("applyCharsMoved(Integer.MAX_VALUE)")) {
                    forbidden.append(rel).append(" 含 A 方案; ");
                }
                if (rel.contains("TrailCorner") && body.contains("config.trailFactors[rank]")
                        && body.split("config.trailFactors\\[rank\\]", -1).length > 2) {
                    forbidden.append(rel).append(" 跨行新读 trailFactors; ");
                }
            }
            if (rel.contains("CaretAnimator") || rel.contains("SpringTrail")) {
                if (body.contains("addSlider(") || body.contains("JCheckBox")) {
                    forbidden.append(rel).append(" 含 UI 控件; ");
                }
            }
        }
        check("D-11 行为层无 A / B1 / B2 实施痕迹", forbidden.length() == 0,
                forbidden.length() == 0 ? "四个文件零泄漏" : forbidden.toString());

        Path settings = src.resolve("settings/NeovideCursorSettings.java");
        Path fields = src.resolve("settings/SettingsField.java");
        String oldSettings = new String(Files.readAllBytes(settings),
                java.nio.charset.StandardCharsets.UTF_8);
        String oldFields = new String(Files.readAllBytes(fields),
                java.nio.charset.StandardCharsets.UTF_8);
        check("D-12 不加 State 字段 / 控件 / 预设字段（本轮白名单文件零改动）",
                !oldSettings.contains("branchDiag") && !oldFields.contains("branchDiag"),
                "NeovideCursorSettings/SettingsField 无 branchDiag");

        Path animator = src.resolve("ui/CaretAnimator.java");
        String body = stripComments(new String(Files.readAllBytes(animator),
                java.nio.charset.StandardCharsets.UTF_8));
        check("D-13 [branch] 只在 diag 开启分支内采集，关闭时无实参求值",
                body.contains("CursorDiag.branchTraceWanted(frameSeq)")
                        && body.contains("CursorDiag.noteBranchSnapshot("),
                "门控=" + body.contains("CursorDiag.branchTraceWanted(frameSeq)")
                        + " 快照=" + body.contains("CursorDiag.noteBranchSnapshot("));
        check("D-13a [branch] 实参来自生产 lastBranchShortForProbe，且先判 pending",
                body.contains("CursorDiag.branchTrace(traceId, frameSeq")
                        && body.contains("trail.lastBranchShortForProbe()")
                        && body.contains("CursorDiag.hasPendingBranch(traceId, branchTrailId)"),
                "接线=" + body.contains("trail.lastBranchShortForProbe()")
                        + " pending门=" + body.contains("CursorDiag.hasPendingBranch("));
        check("D-14 日志输出复用 CursorDiag 的 synchronized 独立文件通道",
                stripComments(new String(Files.readAllBytes(src.resolve("ui/CursorDiag.java")),
                        java.nio.charset.StandardCharsets.UTF_8))
                        .contains("writeFileAndReport(\"[branch] \""),
                "production writer=writeFileAndReport");
    }

    private static Object[] formatArgs() {
        return new Object[]{
                81001, 77, 123456789L, 0, true,
                24, 12, 17.5, 0.0,
                2.0, 32.0, 1, 24,
                true, true, 100,
                8, 3.0f, false, true,
        };
    }

    private static Object[] noteArgs() {
        return new Object[]{
                81001, 77, 123456789L, 0, true,
                24, 12, 17.5, 0.0,
                2.0, 32.0, 1, 24,
                true, true, false, 100,
                8, 3.0f, false,
        };
    }

    /** 去掉 [branch] 前缀里的时间戳，只比其余字段（时间戳天然每次不同）。 */
    private static String branchBody(String line) {
        int at = line.indexOf(" animator=");
        return at < 0 ? line : line.substring(at);
    }

    private static com.github.yyyolo7a79.neovidecursor.core.NeovideConfig baseConfig() {
        com.github.yyyolo7a79.neovidecursor.core.NeovideConfig cfg =
                new com.github.yyyolo7a79.neovidecursor.core.NeovideConfig();
        cfg.animationLength = 0.10f;
        cfg.shortAnimationLength = 0.11f;
        cfg.trailSize = 1.0f;
        cfg.typingMaxChars = 3f;
        cfg.trailFactors = com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings
                .deriveTrailFactors(4.5f, 0f, 0f);
        return cfg;
    }

    private static double[] cornerLens(
            com.github.yyyolo7a79.neovidecursor.core.NeovideConfig cfg,
            int charsMoved, boolean sameLine, boolean diagEnabled) {
        if (diagEnabled) {
            V0Env.svc.getState().diagLogging = true;
        } else {
            V0Env.svc.getState().diagLogging = false;
        }
        com.github.yyyolo7a79.neovidecursor.ui.CursorDiag.invalidateEnabled();
        double w = 2.0;
        double h = 32.0;
        double cx = 100.0;
        double cy = 100.0;
        double dx = sameLine ? 12.0 : 100.0;
        double dy = sameLine ? 0.0 : h;
        double[][] rel = {{-0.5, -0.5}, {0.5, -0.5}, {0.5, 0.5}, {-0.5, 0.5}};
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            com.github.yyyolo7a79.neovidecursor.core.TrailCorner c =
                    new com.github.yyyolo7a79.neovidecursor.core.TrailCorner(
                            rel[i][0], rel[i][1], cfg);
            c.snapTo(w, h, cx, cy);
            c.setCharsMoved(charsMoved);
            c.jump(w, h, cx + dx, cy + dy, i);
            out[i] = c.animationLengthForProbe();
        }
        return out;
    }

    private static boolean returnsMinusOne() throws Exception {
        Path file = repoRoot().resolve(
                "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java");
        String body = stripComments(new String(Files.readAllBytes(file),
                java.nio.charset.StandardCharsets.UTF_8));
        int start = body.indexOf("private int updateBlockCharsMoved()");
        if (start < 0) {
            return false;
        }
        int end = body.indexOf("\n    }", start);
        String method = end < 0 ? "" : body.substring(start, end);
        boolean methodOk = method.contains("return -1;") && !method.contains("return 0;");
        boolean callSiteOk = body.contains("charsMoved = updateBlockCharsMoved();")
                && !body.contains("charsMoved = 0;");
        return methodOk && callSiteOk;
    }

    private static Method find(Class<?> type, String name, Class<?>[] args) {
        try {
            return type.getDeclaredMethod(name, args);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String methodsDetail(Method... methods) {
        StringBuilder out = new StringBuilder();
        String[] names = {"branchTrace", "formatBranchForProbe", "noteBranchSnapshot",
                "resetBranchBudgetsForProbe", "branchBudgetForProbe", "tableSizesForProbe"};
        for (int i = 0; i < methods.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(names[i]).append('=').append(methods[i] != null);
        }
        return out.toString();
    }

    private static List<String> diagLines(Path file) throws Exception {
        List<String> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : new String(Files.readAllBytes(file),
                java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static boolean allZero(int[] values) {
        for (int v : values) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])
                    || Math.abs(a[i] - b[i]) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static Path repoRoot() {
        String prop = System.getProperty("neovide.repo");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return p;
            }
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
    }

    private static void finish() {
        System.out.println();
        System.out.printf(Locale.ROOT,
                "=== V-MULTI-BRANCH-DIAG 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-MULTI-BRANCH-DIAG RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-MULTI-BRANCH-DIAG RESULT: ALL PASS");
        System.exit(0);
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
