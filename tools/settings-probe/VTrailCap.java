import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
