import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.VisualPosition;

import javax.swing.JComponent;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V-MULTICURSOR-FIX：多光标三异常的根因守门探针。
 *
 * <h2>它守的是什么 bug（2026-11-02，用户实机）</h2>
 *
 * 现象 A：列选多行按住右键，走到中文注释行（本文件 sample 的第 4 行）时，<b>那一行的光标整个不见了</b>；
 * 现象 B：继续走到 {@code private List<...>} 行（第 5 行）附近，<b>那一行的光标停在原地、辉光一直亮着</b>；
 * 现象 C：多光标只剩孤立一个时，它会跟着已消失的光标一起亮。
 *
 * <h2>根因（已用平台字节码核实，见 plans/multicursor-fix.md §1.2）</h2>
 *
 * 1. 261 平台 {@code SelectionModel.getBlockSelectionStarts()} 的契约是
 *    「<b>每个 caret 一项</b>」（数组长度 = caret 数），不是「块选区每行一项」。
 * 2. 本机 virtual space 关闭，行尾按 → 会把 caret <b>移到下一行行首</b>
 *    （{@code CaretImpl.moveCaretRelatively} 的 wrap 分支）。
 *    于是最短的中文注释行先回绕到 List 行行首 → <b>同一行出现两个 caret</b>。
 * 3. 生产把「行号」当 trail key（{@code blockTrailKey = -1000000 - lineNo}）——
 *    两个 caret 同行 → key 塌成一个 → 两个槽位共用同一条 {@code SpringTrail}：
 *    - 先处理的槽位把 trail 抢走 → 另一行没有 trail → <b>光标消失（A）</b>；
 *    - 两个槽位每帧先后 {@code setTarget} 到两个不同位置 → 四角永远不收敛、
 *      {@code lastMoveNanos} 每帧刷新 → <b>卡住 + 辉光常亮（B/C）</b>。
 *
 * <h2>本探针怎么复现</h2>
 *
 * 用可变假 Editor 驱动<b>真实</b>的 {@code CaretAnimator.refreshTarget()} 与
 * {@code tickSpring()}（与 VInlay 同一脚手架思路），假平台严格照上面的契约实现：
 * <ul>
 *   <li>{@code getBlockSelectionStarts/Ends} 按 caret 返回（per-caret）；</li>
 *   <li>每帧右移：行尾 → 下一行行首；同 offset 的 caret 合并（平台 merge）。</li>
 * </ul>
 *
 * <h2>断言（T-1 / T-2b / T-4 修复前实测为红；其余为规范与回归守门）</h2>
 * <ul>
 *   <li>T-1 key 稳定：同一 caret 的 trail 实例在左右各 20 帧内不变；</li>
 *   <li>T-2 收敛：回绕后停手 60 帧，每条 trail 的 {@code maxOffset} 归零；
 *       <b>注意</b>：修复前它也是绿的（两次 setTarget 都发生在物理推进之前，
 *       形状会用最后一次目标收敛）—— 现象 B 的有效判据是 T-2b；</li>
 *   <li>T-2b 熄灭：停手 60 帧后所有四边形的 {@code quadGlow} 必须为 0
 *       （共享 trail 每帧刷新 lastMoveNanos → 保持期永不过期）；</li>
 *   <li>T-3 辉光隔离：非相邻行一静一动，静止行的 quadGlow 必须为 0（回归守门）；</li>
 *   <li>T-4 数量守恒：每帧 {@code trails.size() == carets.size()}、槽位 trail 两两不同、
 *       {@code quadCount == carets.size()}；</li>
 *   <li>T-5 单光标回归：1 条 trail、1 个 quad、目标跟随；</li>
 *   <li>T-6 有选区：两个方向相反的 caret，各自目标必须落在自己的 lead（活动端）。</li>
 * </ul>
 */
public final class VMultiCursorFix {

    private static int pass = 0;
    private static int fail = 0;

    /** 用户报告里的示例文件（行尾两个空格对行宽判定有影响，保留）。 */
    private static final String[] LINES = {
            "public class Map {  ",
            "    private int x,y;  ",
            "    private int width;  ",
            "    private int height;  ",
            "    //游戏地图块的容器  ",
            "    private List<MapTile> tiles = new ArrayList<>();  ",
            "}"
    };

    private static final int CHAR_W = 8;
    private static final int LINE_H = 19;
    private static final int BASE_X = 50;
    private static final int BASE_Y = 100;

    private static int[] lineStart;
    private static int[] lineEnd;
    private static int textLength;

    public static void main(String[] a) throws Exception {
        V0Env.install();
        initDocument();

        System.out.println("=== V-MULTICURSOR-FIX：多光标 key 塌陷 / 拖尾收敛 / 辉光隔离 ===");

        scenarioS1();
        scenarioS2();
        scenarioS3();
        scenarioS4();
        scenarioS5();

        System.out.println();
        System.out.printf("=== V-MULTICURSOR-FIX 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-MULTICURSOR-FIX RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-MULTICURSOR-FIX RESULT: ALL PASS");
        System.exit(0);
    }

    // ================================================================ S1

    /**
     * S1：用户原场景 —— 6 个 caret 放在第 0..5 行行首，右移 20 帧再左移 20 帧（AC-004）。
     *
     * <p>第 4 行（17 列）是最短行：第 17 次右移到达行尾，第 18 次回绕到第 5 行行首。
     * 修复前：两个槽位共用 key(line5) → T-1/T-4 红；左移时回绕回上一行行尾，
     * 同样必须保持 key 稳定（否则「按左键退回它又出现/消失」会换一行复发）。
     */
    private static void scenarioS1() throws Exception {
        System.out.println();
        System.out.println("--- S1：6 行 caret 连续右移（用户原场景）---");

        Harness h = new Harness();
        for (int line = 0; line <= 5; line++) {
            h.addCaret(lineStart[line]);
        }

        Map<Caret, Object> lastTrail = new LinkedHashMap<>();
        boolean keyUnstable = false;
        String unstableDetail = "";
        boolean countViolated = false;
        String countDetail = "";

        // 右移 20 帧 + 左移 20 帧（AC-004：两个方向必须对称）
        for (int frame = 1; frame <= 40; frame++) {
            if (frame <= 20) {
                h.moveAllRight();
            } else {
                h.moveAllLeft();
            }
            h.frame(true);

            List<Caret> carets = h.snapshotCarets();
            List<Object> active = h.activeTrails();
            Map<Object, Object> trails = h.trails();

            // T-4：数量守恒（每个 caret 一条 trail；activeTrails 里不能有重复对象）
            int distinctActive = distinctIdentities(active);
            int quadCount = h.panel().quadCount();
            if (trails.size() != carets.size()
                    || active.size() != carets.size()
                    || distinctActive != carets.size()
                    || quadCount != carets.size()) {
                countViolated = true;
                if (countDetail.isEmpty()) {
                    countDetail = "frame=" + frame + " carets=" + carets.size()
                            + " trails=" + trails.size()
                            + " active=" + active.size()
                            + " distinctActive=" + distinctActive
                            + " quads=" + quadCount;
                }
            }

            // T-1：同一 caret 的 trail 实例跨帧稳定（首次见到只记录）
            for (int i = 0; i < carets.size() && i < active.size(); i++) {
                Caret c = carets.get(i);
                Object trail = active.get(i);
                Object prev = lastTrail.get(c);
                if (prev != null && prev != trail) {
                    keyUnstable = true;
                    if (unstableDetail.isEmpty()) {
                        unstableDetail = "frame=" + frame + " line=" + h.lineOf(c)
                                + " 从 " + nameOf(prev) + " 变为 " + nameOf(trail);
                    }
                }
                lastTrail.put(c, trail);
            }
            // 已消失的 caret 不再比较
            lastTrail.keySet().retainAll(carets);
        }

        check("T-1 同一 caret 的 trail 实例跨左右各 20 帧不变（行号 key 不在回绕时塌陷）",
                !keyUnstable, keyUnstable ? unstableDetail : "40 帧全部稳定");
        check("T-4 每帧 trails.size()==carets.size()、槽位 trail 两两不同、quadCount 一致",
                !countViolated, countViolated ? countDetail : "40 帧全部守恒");

        h.dispose();
    }

    // ================================================================ S2

    /**
     * S2：收敛性 —— 第 4 行 caret 回绕到第 5 行行首后，两个 caret 继续右移；
     * 然后**停手**，再空跑 60 帧，要求每条 trail 的 {@code maxOffset} 归零。
     *
     * <p>为什么用「停手后」而不是「移动中」：移动中的弹簧对匀速目标本来就有稳态滞后
     * （lag ≈ 速度 × animationLength），移动中的偏移量无法区分「正常的拖尾」与
     * 「目标在两个位置之间来回跳」。
     *
     * <p>修复前：两个槽位共用一条 trail，慢路径每帧先后 setTarget(A)、setTarget(B)，
     * 帧尾缓存的是 B 的位置；主光标却是 A —— 顶层快速路径永远不命中，于是**停手后
     * 仍然每帧交替写两个目标**，maxOffset 永不收敛（这就是用户看到的「停住 + 辉光常亮」）。
     * 修复后：每条 trail 只跟自己的 caret，停手后一个动画周期内归零。
     */
    private static void scenarioS2() throws Exception {
        System.out.println();
        System.out.println("--- S2：回绕后停手 60 帧，每条 trail 的 maxOffset 必须归零 ---");

        Harness h = new Harness();
        // 第 4 行最后一列（下一帧就回绕到第 5 行行首）
        h.addCaret(lineEnd[4] - 1);
        // 第 5 行第 5 列（模拟继续移动的 List 行光标）
        h.addCaret(lineStart[5] + 5);

        // 阶段 1：连续移动 20 帧（每帧都像真实按键一样触发 forceResync）
        for (int frame = 1; frame <= 20; frame++) {
            h.moveAllRight();
            h.frame(true);
        }

        // 阶段 2：停手 —— 不再有任何 caret 事件（forceResync=false），只让物理继续推进。
        // 60 帧 × 8ms = 480ms > 最长 animationLength(0.1s)，足够收敛。
        for (int frame = 1; frame <= 60; frame++) {
            h.frame(false);
        }

        double maxSeen = 0;
        String detail = "";
        for (Object trail : h.activeTrails()) {
            double off = maxOffsetOf(trail);
            if (off > maxSeen) {
                maxSeen = off;
                detail = "maxOffset=" + String.format("%.3f", off)
                        + "（停手 60 帧后仍未归零）";
            }
        }

        check("T-2 停手 60 帧后每条 trail 的 maxOffset < 0.5（目标不再被交替写入）",
                maxSeen < 0.5,
                maxSeen < 0.5
                        ? String.format("最大残余 %.3fpx", maxSeen)
                        : detail);

        // T-2b：形状收敛 ≠ 辉光熄灭。
        //
        // 这是现象 B 的**直接**观测量：共享 trail 的两个槽位每帧都调用 setTarget，
        // 而「目标是否变化」的判据是「和上一次写入的比较」—— A、B 交替写时，
        // 两次都返回 true → glowStates.lastMoveNanos 每帧被刷新 →
        // 保持期永远不过期 → **光标静止后辉光仍然满亮度**。
        // 只断言 maxOffset 抓不到它（物理推进发生在两次写入之后，用的是最后一次目标，
        // 所以形状会收敛、光却不会熄）。
        double maxGlow = 0;
        int quads = h.panel().quadCount();
        for (int i = 0; i < quads; i++) {
            maxGlow = Math.max(maxGlow, h.quadGlow(i));
        }
        check("T-2b 停手 60 帧后所有四边形 quadGlow == 0（辉光必须熄灭）",
                maxGlow == 0.0,
                String.format("最大 quadGlow=%.3f（%d 个四边形）", maxGlow, quads));

        h.dispose();
    }

    // ================================================================ S3

    /**
     * S3：辉光隔离 —— 第 0 行持续移动、第 4 行完全静止（不相邻 → 不同组）。
     *
     * <p>组内（相邻行）共享显示亮度是用户明确要的；但<b>跨组必须隔离</b>。
     * 静止行的 quadGlow 必须为 0，否则就是「孤立光标跟着别的光标一起亮」（现象 C）。
     */
    private static void scenarioS3() throws Exception {
        System.out.println();
        System.out.println("--- S3：非相邻行一静一动，静止行不得发光 ---");

        Harness h = new Harness();
        h.addCaret(lineStart[0]);   // 移动
        h.addCaret(lineStart[4]);   // 静止（行 0 与行 4 中间隔了 1..3，不同组）

        for (int frame = 1; frame <= 40; frame++) {
            // 只移动第一个 caret
            h.moveFirstRight();
            h.frame(true);
        }

        List<Object> active = h.activeTrails();
        double movingGlow = active.size() > 0 ? h.quadGlow(0) : -1;
        double stillGlow = active.size() > 1 ? h.quadGlow(1) : -1;

        check("T-3 移动行 quadGlow > 0（对照组，证明场景有效）",
                movingGlow > 0.1,
                String.format("movingGlow=%.3f", movingGlow));
        check("T-3 非相邻静止行 quadGlow == 0（不许跟着亮）",
                stillGlow == 0.0,
                String.format("stillGlow=%.3f", stillGlow));

        h.dispose();
    }

    // ================================================================ S4

    /** S4：单光标回归 —— 1 条 trail、1 个 quad、目标跟随。 */
    private static void scenarioS4() throws Exception {
        System.out.println();
        System.out.println("--- S4：单光标回归 ---");

        Harness h = new Harness();
        h.addCaret(lineStart[0]);
        h.frame(true);

        List<Object> first = h.activeTrails();
        boolean oneTrail = h.trails().size() == 1 && first.size() == 1;
        boolean oneQuad = h.panel().quadCount() == 1;
        // 槽位数为 0 时也要给出干净的 FAIL，而不是 IndexOutOfBounds（变异测试要求）
        double cx0 = first.isEmpty() ? Double.NaN : h.centerXOf(first.get(0));

        for (int i = 0; i < 10; i++) {
            h.moveFirstRight();
            h.frame(true);
        }
        List<Object> last = h.activeTrails();
        double cx1 = last.isEmpty() ? Double.NaN : h.centerXOf(last.get(0));

        check("T-5 单光标：1 条 trail、1 个 quad、中心 x 跟随右移",
                oneTrail && oneQuad && cx1 > cx0 + CHAR_W * 5,
                String.format("trail=%b quad=%b cx %.1f → %.1f", oneTrail, oneQuad, cx0, cx1));

        h.dispose();
    }

    // ================================================================ S5

    /**
     * S5：<b>有选区的 caret</b> —— 目标点必须落在该 caret 自己的活动端（lead）。
     *
     * <p>为什么必须有这一条（code-reviewer MEDIUM）：没有选区时
     * {@code selectionStart == selectionEnd == offset}，旧实现（用主光标方向
     * 统一选 blockStarts / blockEnds）与新实现（各自 caret 的 lead）**完全等价**，
     * 前面四条断言抓不到目标点改坏。只有「两个 caret 的选区方向相反」时才有差异：
     *
     * <ul>
     *   <li>caret A：向右选（lead = 右端）；</li>
     *   <li>caret B：向左选（lead = 左端）；</li>
     *   <li>旧实现用<b>主光标</b>（A）的方向决定整组 → B 会被画到右端（锚点侧）；</li>
     *   <li>新实现逐 caret 取 lead → B 画在左端。</li>
     * </ul>
     */
    private static void scenarioS5() throws Exception {
        System.out.println();
        System.out.println("--- S5：有选区时目标点必须逐 caret 取 lead（活动端）---");

        Harness h = new Harness();
        // A：第 0 行 col 2..8，lead = 8（向右选）
        h.addCaretWithSelection(lineStart[0] + 8, lineStart[0] + 2, lineStart[0] + 8);
        // B：第 1 行 col 4..10，lead = 4（向左选）
        h.addCaretWithSelection(lineStart[1] + 4, lineStart[1] + 4, lineStart[1] + 10);

        h.frame(true);

        List<Object> active = h.activeTrails();
        // 槽位顺序 = caret 顺序 = 按 offset 排序：A（第 0 行）在前，B（第 1 行）在后
        boolean enough = active.size() == 2;
        double cxA = enough ? h.centerXOf(active.get(0)) : Double.NaN;
        double cxB = enough ? h.centerXOf(active.get(1)) : Double.NaN;
        double w = enough ? h.widthOf(active.get(0)) : 0;

        double expectA = BASE_X + 8 * CHAR_W + w / 2.0;
        double expectB = BASE_X + 4 * CHAR_W + w / 2.0;

        check("T-6 向右选的 caret 对准 lead（右端 col 8）",
                enough && Math.abs(cxA - expectA) < 0.6,
                String.format("cxA=%.1f 期望 %.1f", cxA, expectA));
        check("T-6 向左选的 caret 对准 lead（左端 col 4，不是锚点 col 10）",
                enough && Math.abs(cxB - expectB) < 0.6,
                String.format("cxB=%.1f 期望 %.1f（若为 %.1f 说明用了 selectionEnd）",
                        cxB, expectB, BASE_X + 10 * CHAR_W + w / 2.0));

        h.dispose();
    }

    // ================================================================ Harness

    /** 一个可变的假编辑器 + 真实 CaretAnimator。 */
    private static final class Harness {
        final List<Slot> slots = new ArrayList<>();
        final CaretAnimator animator;
        final CaretTrailPanel panel;
        final Field forceResyncField;
        final Method refreshMethod;
        final Method tickSpringMethod;
        final Field trailsField;
        final Field activeTrailsField;

        /**
         * 探针用的<b>模拟时钟</b>（纳秒），每帧 +8ms。
         *
         * <p>为什么不能直接用 {@code System.nanoTime()}：headless 探针跑一个「8ms 帧」
         * 只要 ~0.3ms 墙钟时间，60 帧总共才 ~19ms —— 而辉光保持期是 50ms 墙钟，
         * 于是「停手 480ms（模拟）」里的保持期永远不过期，亮度看起来永远不降。
         * 生产里墙钟与 dt 同步，探针必须自己让时钟同步推进。
         */
        long clockNanos = System.nanoTime();

        Harness() throws Exception {
            Document doc = fakeDocument();
            CaretModel model = fakeCaretModel();
            SelectionModel selection = fakeSelectionModel();
            Editor editor = fakeEditor(doc, model, selection);

            Constructor<CaretAnimator> ctor =
                    CaretAnimator.class.getDeclaredConstructor(Editor.class, NeovideConfig.class);
            ctor.setAccessible(true);
            animator = ctor.newInstance(editor, NeovideCaretManager.getConfig());

            detachFromScheduler();

            Field panelField = CaretAnimator.class.getDeclaredField("panel");
            panelField.setAccessible(true);
            panel = (CaretTrailPanel) panelField.get(animator);

            forceResyncField = CaretAnimator.class.getDeclaredField("forceResync");
            forceResyncField.setAccessible(true);
            refreshMethod = CaretAnimator.class.getDeclaredMethod("refreshTarget", long.class);
            refreshMethod.setAccessible(true);
            tickSpringMethod = CaretAnimator.class.getDeclaredMethod(
                    "tickSpring", double.class, long.class);
            tickSpringMethod.setAccessible(true);

            trailsField = CaretAnimator.class.getDeclaredField("trails");
            trailsField.setAccessible(true);
            activeTrailsField = CaretAnimator.class.getDeclaredField("activeTrails");
            activeTrailsField.setAccessible(true);
        }

        // ---- caret 管理 ----

        void addCaret(int offset) {
            Slot s = new Slot(offset, offset, offset);
            slots.add(s);
            sortSlots();
        }

        /**
         * 加一个<b>带选区</b>的 caret。{@code caretOffset} 是活动端（lead）；
         * {@code selStart/selEnd} 是选区的两个端点（min/max）。
         */
        void addCaretWithSelection(int caretOffset, int selStart, int selEnd) {
            Slot s = new Slot(caretOffset, selStart, selEnd);
            slots.add(s);
            sortSlots();
        }

        void sortSlots() {
            slots.sort(Comparator.comparingInt(s -> s.offset));
        }

        /** 平台 merge：同一 offset 的 caret 合并成一个。 */
        void mergeSameOffset() {
            for (int i = slots.size() - 1; i > 0; i--) {
                if (slots.get(i).offset == slots.get(i - 1).offset) {
                    slots.remove(i);
                }
            }
        }

        void moveAllRight() {
            for (Slot s : slots) {
                shift(s, rightOf(s.offset));
            }
            mergeSameOffset();
            sortSlots();
        }

        /** 平台左移语义：行首 ← 回到上一行行尾（AC-004 的对称方向）。 */
        void moveAllLeft() {
            for (Slot s : slots) {
                shift(s, leftOf(s.offset));
            }
            mergeSameOffset();
            sortSlots();
        }

        void moveFirstRight() {
            if (slots.isEmpty()) {
                return;
            }
            shift(slots.get(0), rightOf(slots.get(0).offset));
            mergeSameOffset();
            sortSlots();
        }

        /** 无选区 caret 整体平移时，offset 与选区两端一起动（保持零宽）。 */
        private void shift(Slot s, int newOffset) {
            int delta = newOffset - s.offset;
            s.offset = newOffset;
            s.selStart += delta;
            s.selEnd += delta;
        }

        /** 平台语义（virtual space=false）：行尾 → 下一行行首；最后一行停在行尾。 */
        static int rightOf(int offset) {
            int line = VMultiCursorFix.lineOf(offset);
            if (offset < lineEnd[line]) {
                return offset + 1;
            }
            if (line < LINES.length - 1) {
                return lineStart[line + 1];
            }
            return offset;
        }

        /** 平台语义（virtual space=false）：行首 → 上一行行尾；第一行停在行首。 */
        static int leftOf(int offset) {
            int line = VMultiCursorFix.lineOf(offset);
            if (offset > lineStart[line]) {
                return offset - 1;
            }
            if (line > 0) {
                return lineEnd[line - 1];
            }
            return offset;
        }

        // ---- 帧驱动 ----

        /**
         * @param realKeyEvent true = 模拟一次真实按键：平台会先触发 caretPositionChanged
         *                     （生产里它把 forceResync 置 true），再走慢路径。
         */
        void frame(boolean realKeyEvent) throws Exception {
            if (realKeyEvent) {
                forceResyncField.setBoolean(animator, true);
            }
            clockNanos += 8_000_000L;          // 模拟 8ms 一帧（与 dt 同步）
            refreshMethod.invoke(animator, clockNanos);
            tickSpringMethod.invoke(animator, 0.008, clockNanos);
        }

        // ---- 读取生产状态 ----

        List<Caret> snapshotCarets() {
            List<Caret> out = new ArrayList<>();
            for (Slot s : slots) {
                out.add(s.caret);
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> trails() {
            try {
                return new LinkedHashMap<>((Map<Object, Object>) trailsField.get(animator));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> activeTrails() {
            try {
                return new ArrayList<>((List<Object>) activeTrailsField.get(animator));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        CaretTrailPanel panel() {
            return panel;
        }

        double quadGlow(int i) {
            double[] q = panel.quadForProbe(i);
            return q == null ? -1 : q[8];
        }

        double centerXOf(Object trail) {
            try {
                Field f = trail.getClass().getDeclaredField("centerX");
                f.setAccessible(true);
                return f.getDouble(trail);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        double widthOf(Object trail) {
            try {
                Field f = trail.getClass().getDeclaredField("width");
                f.setAccessible(true);
                return f.getDouble(trail);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        int lineOf(Caret c) {
            try {
                return c.getVisualPosition().line;
            } catch (Throwable t) {
                return -1;
            }
        }

        /**
         * 真正停掉共享调度线程并排空 EDT（code-reviewer / java-reviewer M-3）。
         *
         * <p>只 {@code unregister} 不够：构造时 {@code wakeUp()} 已把 nextDueNanos
         * 置为「现在」，调度线程可能已经投递过一次 {@code runTick}。V0Env 只桩了
         * {@code Application.invokeLater}，桩不到 {@code SwingUtilities} 的静态方法 ——
         * 那次任务会真的进 EDT，与探针主线程并发写 {@code trails}(HashMap)。
         * 做法与 {@code VReadAction.detachFromScheduler} 相同：join 调度线程 + 排空 EDT。
         */
        private void detachFromScheduler() {
            try {
                Class<?> rs = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.RenderScheduler");
                Method unregister = rs.getDeclaredMethod("unregister", CaretAnimator.class);
                unregister.setAccessible(true);
                unregister.invoke(null, animator);
                Field tf = rs.getDeclaredField("thread");
                tf.setAccessible(true);
                Object t = tf.get(null);
                if (t instanceof Thread th) {
                    th.join(1000);
                }
                javax.swing.SwingUtilities.invokeAndWait(() -> { });
            } catch (Throwable ignored) {
                // 调度器没起来 / 线程已退出：不影响本探针
            }
        }

        void dispose() {
            // 不调用 animator.dispose()：它会去动共享的 EditorFactory/ColorScheme 桩，
            // 影响后续场景。本探针只读自己的 animator，调度已在构造时排空。
        }

        // ---- 假平台对象 ----

        private Document fakeDocument() {
            return (Document) Proxy.newProxyInstance(
                    VMultiCursorFix.class.getClassLoader(),
                    new Class<?>[]{Document.class},
                    (p, m, args) -> {
                        switch (m.getName()) {
                            case "getLineNumber":
                                return VMultiCursorFix.lineOf((Integer) args[0]);
                            case "getLineStartOffset":
                                return lineStart[clampLine((Integer) args[0])];
                            case "getLineEndOffset":
                                return lineEnd[clampLine((Integer) args[0])];
                            case "getTextLength":
                                return textLength;
                            case "getModificationStamp":
                                return 12345L;
                            case "toString":
                                return "FakeDocument";
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });
        }

        private CaretModel fakeCaretModel() {
            return (CaretModel) Proxy.newProxyInstance(
                    VMultiCursorFix.class.getClassLoader(),
                    new Class<?>[]{CaretModel.class},
                    (p, m, args) -> {
                        switch (m.getName()) {
                            case "getAllCarets":
                                return snapshotCarets();
                            case "getPrimaryCaret":
                                return slots.isEmpty() ? null : slots.get(0).caret;
                            case "getVisualPosition":
                                return slots.isEmpty()
                                        ? new VisualPosition(0, 0)
                                        : vpOf(slots.get(0).offset);
                            case "getOffset":
                                return slots.isEmpty() ? 0 : slots.get(0).offset;
                            case "addCaretListener":
                            case "removeCaretListener":
                                return null;
                            case "toString":
                                return "FakeCaretModel";
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });
        }

        private SelectionModel fakeSelectionModel() {
            return (SelectionModel) Proxy.newProxyInstance(
                    VMultiCursorFix.class.getClassLoader(),
                    new Class<?>[]{SelectionModel.class},
                    (p, m, args) -> {
                        switch (m.getName()) {
                            case "getBlockSelectionStarts":
                            case "getBlockSelectionEnds": {
                                // 平台契约：数组长度 = caret 数，每项是该 caret 的
                                // selectionStart / selectionEnd（无选区时就是 caret 位置）。
                                boolean starts = m.getName().endsWith("Starts");
                                int[] out = new int[slots.size()];
                                for (int i = 0; i < slots.size(); i++) {
                                    Slot s = slots.get(i);
                                    out[i] = starts ? s.selStart : s.selEnd;
                                }
                                return out;
                            }
                            case "hasSelection":
                                return false;
                            case "toString":
                                return "FakeSelectionModel";
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });
        }

        private Editor fakeEditor(Document doc, CaretModel model, SelectionModel selection) {
            JComponent content = new JComponent() { };
            content.setSize(900, 700);
            InlayModel inlayModel = (InlayModel) Proxy.newProxyInstance(
                    VMultiCursorFix.class.getClassLoader(),
                    new Class<?>[]{InlayModel.class},
                    (p, m, args) -> {
                        switch (m.getName()) {
                            case "hasBlockElements":
                                return false;
                            case "getBlockElementsInRange":
                                return new ArrayList<>();
                            case "addListener":
                            case "removeListener":
                                return null;
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });

            return (Editor) Proxy.newProxyInstance(
                    VMultiCursorFix.class.getClassLoader(),
                    new Class<?>[]{Editor.class},
                    (p, m, args) -> {
                        switch (m.getName()) {
                            case "getCaretModel":
                                return model;
                            case "getSelectionModel":
                                return selection;
                            case "getDocument":
                                return doc;
                            case "getInlayModel":
                                return inlayModel;
                            case "getContentComponent":
                                return content;
                            case "getLineHeight":
                                return LINE_H;
                            case "getEditorKind":
                                return EditorKind.MAIN_EDITOR;
                            case "getProject":
                                return null;
                            case "isDisposed":
                                return false;
                            case "visualPositionToXY": {
                                VisualPosition vp = (VisualPosition) args[0];
                                return new Point(BASE_X + vp.column * CHAR_W,
                                        BASE_Y + vp.line * LINE_H);
                            }
                            case "offsetToXY": {
                                int off = (Integer) args[0];
                                VisualPosition vp = vpOf(off);
                                return new Point(BASE_X + vp.column * CHAR_W,
                                        BASE_Y + vp.line * LINE_H);
                            }
                            case "offsetToVisualPosition":
                                return vpOf((Integer) args[0]);
                            case "toString":
                                return "FakeEditor";
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                return defaultValue(m.getReturnType());
                        }
                    });
        }

        /** 一个 caret：proxy（身份 key）+ 可变 offset + 可选选区端点。 */
        private final class Slot implements InvocationHandler {
            int offset;      // 活动端（lead）
            int selStart;    // 选区起点（min）
            int selEnd;      // 选区终点（max）
            final Caret caret;

            Slot(int offset, int selStart, int selEnd) {
                this.offset = offset;
                this.selStart = selStart;
                this.selEnd = selEnd;
                this.caret = (Caret) Proxy.newProxyInstance(
                        VMultiCursorFix.class.getClassLoader(),
                        new Class<?>[]{Caret.class},
                        this);
            }

            @Override
            public Object invoke(Object proxy, Method m, Object[] args) {
                switch (m.getName()) {
                    case "getOffset":
                        return offset;
                    case "getVisualPosition":
                        return vpOf(offset);
                    case "getLogicalPosition": {
                        VisualPosition vp = vpOf(offset);
                        return new LogicalPosition(vp.line, vp.column);
                    }
                    case "hasSelection":
                        return selStart != selEnd;
                    case "getLeadSelectionPosition":
                        return vpOf(offset);
                    case "getLeadSelectionOffset":
                        return offset;
                    case "getSelectionStart":
                        return selStart;
                    case "getSelectionEnd":
                        return selEnd;
                    case "toString":
                        return "FakeCaret@" + offset;
                    // 【关键】Caret 不重写 equals/hashCode —— 平台按引用判等。
                    // 这里必须同样按身份，否则 HashMap 会把两个 caret 当成同一个 key，
                    // 探针就会「替生产把 bug 藏掉」（纪律四-G 的假通过）。
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(m.getReturnType());
                }
            }
        }
    }

    // ================================================================ 工具

    private static void initDocument() {
        lineStart = new int[LINES.length];
        lineEnd = new int[LINES.length];
        int off = 0;
        for (int i = 0; i < LINES.length; i++) {
            lineStart[i] = off;
            off += LINES[i].length();
            lineEnd[i] = off;
            if (i < LINES.length - 1) {
                off += 1;                 // '\n'
            }
        }
        textLength = off;
    }

    private static int lineOf(int offset) {
        int o = Math.max(0, Math.min(offset, textLength));
        for (int i = LINES.length - 1; i >= 0; i--) {
            if (o >= lineStart[i]) {
                return i;
            }
        }
        return 0;
    }

    private static int clampLine(int line) {
        return Math.max(0, Math.min(line, LINES.length - 1));
    }

    private static VisualPosition vpOf(int offset) {
        int o = Math.max(0, Math.min(offset, textLength));
        int line = lineOf(o);
        return new VisualPosition(line, o - lineStart[line]);
    }

    private static double maxOffsetOf(Object trail) throws Exception {
        Method m = trail.getClass().getDeclaredMethod("maxOffset");
        m.setAccessible(true);
        return (double) m.invoke(trail);
    }

    private static int distinctIdentities(List<Object> list) {
        IdentityHashMap<Object, Boolean> ids = new IdentityHashMap<>();
        for (Object o : list) {
            ids.put(o, Boolean.TRUE);
        }
        return ids.size();
    }

    private static String nameOf(Object trail) {
        try {
            Field f = trail.getClass().getDeclaredField("currentLine");
            f.setAccessible(true);
            return "trail(line=" + f.getInt(trail) + ")@" + System.identityHashCode(trail);
        } catch (Exception e) {
            return "trail@" + System.identityHashCode(trail);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
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
