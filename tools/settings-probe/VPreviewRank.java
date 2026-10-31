import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V-PREVIEW-RANK —— P-M-8 的守门探针（2026-10-31）。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>预览窗 {@code AnimationPreview} 里有<b>第二份</b>「四角 rank 排序」实现，
 * 与生产 {@code SpringTrail.assignRanks()} 各写一份。这已经出过一次事：
 * 0.9.77 修了生产（把「比它小的有几个」的计数法换成「稳定排序 + 索引 tiebreak」），
 * 预览没跟上 → 生产变斜、预览仍是矩形（用户原话「动画预览没有变斜」）。
 *
 * <p>本轮（白名单只允许改 {@code ui/settings/} 两个文件）不能把算法抽到 core 共用，
 * 所以**改为断言两者结果一致**：喂同一份对齐度，两边的 rank 排列必须逐角相同。
 * 判据来自陷阱 3 的验真方法 —— 把生产排序改坏，这里必须红。
 *
 * <h2>为什么不自己算一份 rank 来比对</h2>
 *
 * <p>探针只读生产吐出来的两个数字：四角对齐度（{@code alignmentScratch}）
 * 与四角时长（{@code animationLengthForProbe()}）。<b>不做任何排序</b> ——
 * 排序的唯一实现仍然是被测的那两份。探针一旦自己写排序，就变成「拿第三份实现验前两份」，
 * 三条都错的时候还会一起绿。
 *
 * <h2>rank 怎么从时长解出来</h2>
 *
 * <p>{@code TrailCorner.jump()} 的短跳分支把 rank 落成
 * {@code animationLength = shortAnimationLength × trailFactors[rank]}。
 * <b>默认配置下 {@code trailFactors} 并不严格递减</b>（默认 slopeTicks=0 时是
 * {@code [0.86, 0.86, 0.32, 0.32]}，有并列 —— 见 CLAUDE.md §4.1），
 * 并列时两个角时长相同、rank 解不出来。所以这里<b>必须</b>显式设成四个互异值
 * （{@link #DISTINCT_FACTORS}）：那时的映射才是「谁最长谁是 rank 0」。
 * 删掉那一行会让五个场景全部 `hasTie` → 全红，不是「可以简化」。
 *
 * <h2>它守不到的地方（诚实声明）</h2>
 *
 * <p>只比「排序」这一步，喂的是<b>生产算出来的对齐度</b>。而「四角顺序表」也是两份拷贝
 * （{@code AnimationPreview} 的 {@code rel} vs {@code SpringTrail.CORNER_RELATIVE}）——
 * 若有人把预览那份的顺序改掉，rank 数字不变但落到别的角上，本探针<b>发现不了</b>。
 * 把算法抽到 core 共用（审计建议的方向）才能一次性收掉这两份拷贝，
 * 本轮白名单不允许，已登记在 `plans/batch3-fix2.md` §七。
 */
public final class VPreviewRank {

    private static int pass;
    private static int fail;

    /** 四角时长必须互异才解得开 rank —— 用一组明确递减的系数，语义与默认配置相同。 */
    private static final float[] DISTINCT_FACTORS = {1.0f, 0.7f, 0.4f, 0.2f};

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-PREVIEW-RANK：预览 rank 必须与生产 rank 一致（P-M-8）===");

        // 短跳场景（同一行、纵向位移 <= 行高的一半）—— 只有这条分支把 rank 线性落进时长。
        scenario("R-1 同一行右移一个字符", 8.0, 20.0, 100.0, 100.0, 108.0, 100.0);
        scenario("R-2 同一行左移一个字符", 8.0, 20.0, 100.0, 100.0, 92.0, 100.0);
        scenario("R-3 小斜跳（右下）", 8.0, 20.0, 100.0, 100.0, 108.0, 104.0);
        scenario("R-4 小斜跳（左上）", 8.0, 20.0, 100.0, 100.0, 92.0, 96.0);
        scenario("R-5 单像素右移", 8.0, 20.0, 100.0, 100.0, 101.0, 100.0);

        // R-6：预览的 rank 本身仍必须是 0..n-1 的排列（并列必须被 tiebreak 拆开）
        double[] tied = {-0.7071, 0.7071, 0.7071, -0.7071};
        int[] r = AnimationPreview.ranksForAlignment(tied);
        check("R-6 对齐度并列时 rank 仍是 0/1/2/3 各一次（索引 tiebreak 生效）",
                isPermutation(r, 4), "ranks=" + java.util.Arrays.toString(r));

        finish();
    }

    /**
     * 一个移动场景：生产驱动 {@code SpringTrail} 拿到真 rank，预览拿同一份对齐度算 rank。
     */
    private static void scenario(String name, double w, double h,
                                 double x0, double y0, double x1, double y1) throws Exception {
        NeovideConfig cfg = new NeovideConfig();
        cfg.trailFactors = DISTINCT_FACTORS.clone();
        cfg.shortAnimationLength = 0.1f;

        int[] prod = productionRanks(cfg, w, h, x0, y0, x1, y1);
        double[] alignment = lastAlignment;
        int[] preview = AnimationPreview.ranksForAlignment(alignment);

        boolean same = prod != null && preview != null
                && java.util.Arrays.equals(prod, preview);
        check(name + "：预览 rank == 生产 rank",
                same,
                "生产=" + java.util.Arrays.toString(prod)
                        + " 预览=" + java.util.Arrays.toString(preview)
                        + " 对齐度=" + java.util.Arrays.toString(alignment));
    }

    /** 最近一次生产侧读到的四角对齐度（同一个场景直接喂给预览）。 */
    private static double[] lastAlignment;

    /**
     * 驱动生产 {@code SpringTrail}，返回每个角的 rank（rank 0 = 时长最长）。
     *
     * <p>为什么用反射：{@code SpringTrail} 是包私有 final 类，与 {@code VMulti} /
     * {@code VMCDiff} 的做法一致。这里只调用它的既有方法、只读它的既有字段，
     * 不改任何状态机。
     */
    private static int[] productionRanks(NeovideConfig cfg, double w, double h,
                                         double x0, double y0, double x1, double y1) throws Exception {
        Class<?> st = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        Constructor<?> ctor = st.getDeclaredConstructor(NeovideConfig.class);
        ctor.setAccessible(true);
        Object trail = ctor.newInstance(cfg);

        Method setTarget = st.getDeclaredMethod("setTarget",
                double.class, double.class, double.class, double.class);
        setTarget.setAccessible(true);
        Method applyCharsMoved = st.getDeclaredMethod("applyCharsMoved", int.class);
        applyCharsMoved.setAccessible(true);
        Method update = st.getDeclaredMethod("update", double.class);
        update.setAccessible(true);

        // 第一次 setTarget = 首次定位（直接吸附，previousDest 落成起点）
        setTarget.invoke(trail, x0, y0, w, h);
        // 第二次 setTarget = 真正的跳动，update 才会触发 assignRanks
        setTarget.invoke(trail, x1, y1, w, h);
        applyCharsMoved.invoke(trail, 1);      // 短跳分支：rank 线性落进时长
        update.invoke(trail, 0.0);             // 触发 assignRanks()

        Field alignmentField = st.getDeclaredField("alignmentScratch");
        alignmentField.setAccessible(true);
        lastAlignment = ((double[]) alignmentField.get(trail)).clone();

        Field cornersField = st.getDeclaredField("corners");
        cornersField.setAccessible(true);
        TrailCorner[] corners = (TrailCorner[]) cornersField.get(trail);

        double[] len = new double[corners.length];
        for (int i = 0; i < corners.length; i++) {
            len[i] = corners[i].animationLengthForProbe();
        }

        // 时长降序 → rank 0 最长。若有两个角时长相同，说明没走短跳分支（跨行三档会并列），
        // 这时 rank 无法从时长解出 —— 返回 null 让断言红，而不是猜一个值。
        int[] rank = new int[corners.length];
        for (int i = 0; i < len.length; i++) {
            int r = 0;
            for (int j = 0; j < len.length; j++) {
                if (len[j] > len[i]) {
                    r++;
                } else if (len[j] == len[i] && j < i) {
                    r++;                        // 并列无法区分 → 让下面的校验报出来
                }
            }
            rank[i] = r;
        }
        return hasTie(len) ? null : rank;
    }

    private static boolean hasTie(double[] a) {
        for (int i = 0; i < a.length; i++) {
            for (int j = i + 1; j < a.length; j++) {
                if (a[i] == a[j]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPermutation(int[] a, int n) {
        if (a == null || a.length != n) {
            return false;
        }
        boolean[] seen = new boolean[n];
        for (int v : a) {
            if (v < 0 || v >= n || seen[v]) {
                return false;
            }
            seen[v] = true;
        }
        return true;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-PREVIEW-RANK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-PREVIEW-RANK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PREVIEW-RANK RESULT: ALL PASS");
        System.exit(0);
    }
}
