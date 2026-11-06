package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;

/**
 * 单个光标的弹簧拖尾状态 —— 四角 + 目标点 + 快速路径缓存。
 *
 * <p><b>为什么需要它</b>：多光标时每个 {@code Caret} 各有一份独立的四角弹簧与目标点。
 * 改造前这些状态内联在 {@code CaretAnimator} 的字段里，只能表达一个光标。
 *
 * <p><b>与上游 v0.5.1 的差异（四处改进，均有明确依据）</b>：
 * <ol>
 *   <li><b>新增 {@link #lastSeenFrame}</b> —— 上游用
 *       {@code trails.size() > activeTrails.size()} 判断清理时机，
 *       而官方文档明确「两个 caret 落到同一视觉位置时平台会合并成一个」，
 *       合并时一增一减、数量不变，清理不触发，死 {@code Caret} 条目滞留。
 *       本类用帧号标记，配合无条件清除，不依赖数量比较。</li>
 *   <li><b>{@link #startFrom} 由调用方按像素距离选源</b> —— 上游取「遍历顺序的
 *       前一个」，Alt+点击散点选时会让新光标从远处飞入。</li>
 *   <li><b>缓存键含文档修改戳</b> —— 只比 (行, 列) 会在第三方输入法上屏后误判
 *       「没移动」（列号不变但像素位置已变）。这是上游 issue #6 的修复，保留。</li>
 *   <li><b>上一帧视觉行逐光标独立</b> —— A1（2026-11-06）起，分支判据是
 *       「同一屏幕视觉行 → SHORT，视觉行变化 → CROSS」。多光标时每个光标
 *       各记一份上一帧视觉行；若共用，光标 A 的行号会把光标 B 的判定带错。</li>
 * </ol>
 *
 * <p><b>线程约束</b>：全部方法只在 EDT 上调用（与 {@code CaretAnimator} 一致）。
 */
final class SpringTrail {

    /** 四个角点的相对位置（左上 → 右上 → 右下 → 左下，顺时针） */
    private static final double[][] CORNER_RELATIVE = {
            {-0.5, -0.5},
            {0.5, -0.5},
            {0.5, 0.5},
            {-0.5, 0.5},
    };

    final TrailCorner[] corners;

    /** 光标矩形中心点与尺寸（contentComponent 坐标系） */
    double centerX;
    double centerY;
    double width;
    double height;

    /**
     * 上次换算坐标时的视觉位置 + 文档修改戳（{@code lastLine} 同时是 A1 的
     * 「上一帧视觉行」，见 {@link #semanticCharsMoved(boolean, int, int)}）。
     *
     * <p>只看 (行, 列) 不够：文字变了但列号恰好没变时（第三方输入法把光标放进
     * 拼音音节内部，上屏后列号相同但像素位置已变），会误判「没移动」、
     * 覆盖层停在半个字宽处。加上修改戳后，文档一变就强制重算。
     */
    private int lastLine = Integer.MIN_VALUE;
    private int lastColumn = Integer.MIN_VALUE;
    private long lastStamp = Long.MIN_VALUE;

    // 【A1 2026-11-06 删除 lastCaretOffset / updateCharsMoved】
    //
    // 旧判据是「|offset 差| <= typingMaxChars」，它把同行 Tab/End/Ctrl+方向/鼠标大跳
    // 按字符数误判成跨行；多光标则恒传 -1 走 16px 像素兜底。A1 统一为屏幕视觉行：
    // 同行 → 0，视觉行变化 → Integer.MAX_VALUE。判据唯一来源见 semanticCharsMoved。

    /** 目标点刚变化，下一帧需要重新分配角点 rank */
    private boolean jumped = false;

    /** 是否已摆放到位（首次定位直接吸附，不做入场动画） */
    private boolean initialized = false;

    /** 本帧四角相对目标的最大偏移（像素），用于驱动辉光强度 */
    private double maxOffset = 0;

    /**
     * 最后一次被 {@code CaretAnimator} 遍历到的帧号。
     *
     * <p>用于帧号标记-清除：平台会在光标合并时悄悄移除 {@code Caret} 对象，
     * 而 {@code IdentityHashMap} 按引用判等，旧条目永远不会再被访问到。
     * 每帧遍历时打标，回收时删掉「帧号 ≠ 当前帧」的条目 —— 不依赖数量比较，
     * 因此在「一增一减数量不变」的合并场景下同样正确。
     */
    long lastSeenFrame = -1L;

    /**
     * 视觉分组号：相邻行（行号连续）的光标归入同一组。
     *
     * <p><b>为什么需要分组</b>：多光标时每个光标独立发光，相邻行的辉光会重叠成
     * 一团、互相串扰。分组后同组共用一个辉光强度（取组内最大值），
     * 组与组之间仍互不影响 —— 于是「1/2/3 行在一起」看起来是一个整体，
     * 「5 行独立」是另一个整体。
     */
    int groupId = 0;

    /** 本光标当前所在行号（用于分组判定） */
    int currentLine = -1;

    SpringTrail(NeovideConfig config) {
        this.config = config;
        corners = new TrailCorner[CORNER_RELATIVE.length];
        for (int i = 0; i < CORNER_RELATIVE.length; i++) {
            corners[i] = new TrailCorner(CORNER_RELATIVE[i][0], CORNER_RELATIVE[i][1], config);
        }
    }

    boolean isInitialized() {
        return initialized;
    }

    boolean isAt(int line, int column, long stamp) {
        return line == lastLine && column == lastColumn && stamp == lastStamp;
    }

    void markAt(int line, int column, long stamp) {
        lastLine = line;
        lastColumn = column;
        lastStamp = stamp;
    }

    /**
     * 上一次 {@link #markAt} 记下的文档修改戳（{@link Long#MIN_VALUE} = 从未记录）。
     *
     * <p>【BUG-016-2】{@code CaretAnimator} 用它判断「这一帧的像素坐标是不是在
     * 文档刚改过之后读的」—— 平台布局可能晚一帧，此时读到的 x/y 还是旧值。
     * 只看 {@link #isAt} 不够：行/列/戳三者相等并不能证明像素已经更新。
     */
    long lastStamp() {
        return lastStamp;
    }

    /**
     * 上一次 {@link #markAt} 记下的屏幕视觉行（{@link Integer#MIN_VALUE} = 从未记录）。
     *
     * <p>A1 分支判据的唯一状态来源：同一视觉行 → SHORT，视觉行变化 → CROSS。
     * 每个 {@code SpringTrail} 各存一份，多光标互不污染。
     */
    int lastVisualLine() {
        return lastLine;
    }

    /**
     * A1 语义的唯一判据：同一屏幕视觉行 → 0（SHORT），视觉行变化 → MAX（CROSS）。
     *
     * <p>生产 {@code CaretAnimator} 与预览 / 守门探针共用这一份，避免「预览一套、
     * 真机一套」。首帧（{@code initialized == false}）尚未建立基准行，按 CROSS 处理；
     * 首帧的 {@link #setTarget} 走吸附，不会真正执行 {@code jump()}。
     *
     * @param previousVisualLine 上一帧 {@link #markAt} 记下的视觉行
     * @param currentVisualLine  本帧跟随点（caret / 选区活动端）的视觉行
     */
    static int semanticCharsMoved(boolean initialized, int previousVisualLine,
                                  int currentVisualLine) {
        return initialized && previousVisualLine == currentVisualLine
                ? 0 : Integer.MAX_VALUE;
    }

    /** 把本次的字符移动量（A1：只有 0 / MAX 两种语义值）分发给四个角点。 */
    void applyCharsMoved(int charsMoved) {
        for (TrailCorner corner : corners) {
            corner.setCharsMoved(charsMoved);
        }
    }

    /**
     * 设备级「跨行形状实验」开关：只转发给四角，不改 {@link #assignRanks()}。
     *
     * <p>默认 false；单行短跳分支在 {@code TrailCorner.jump()} 里先走，不读该标志。
     */
    void setCrossShapeExperiment(boolean enabled) {
        for (TrailCorner corner : corners) {
            corner.setCrossShapeExperiment(enabled);
        }
    }

    /** 构造时传入的配置（assignRanks 用 trailFactors / shortAnimationLength 等算时长）。 */
    private final NeovideConfig config;

    /**
     * 让本光标从另一个光标的当前形状起飞（多光标新增时调用）。
     *
     * <p>源光标尚未就位时不做处理，交给首次 {@link #setTarget} 直接吸附。
     * <b>选哪个 source 由调用方决定</b> —— 应按像素距离取最近的，
     * 而不是遍历顺序的前一个（见类注释第 2 点）。
     */
    void startFrom(SpringTrail source) {
        if (source == null || !source.initialized) {
            return;
        }
        for (int i = 0; i < corners.length; i++) {
            corners[i].placeAt(source.corners[i].getCurrentX(),
                    source.corners[i].getCurrentY());
        }
        width = source.width;
        height = source.height;
        initialized = true;
        jumped = false;
        maxOffset = 0;
    }

    /**
     * 更新目标点。
     *
     * @return true 表示目标确实发生了变化
     */
    boolean setTarget(double cx, double cy, double w, double h) {
        if (initialized && cx == centerX && cy == centerY
                && w == width && h == height) {
            return false;
        }
        centerX = cx;
        centerY = cy;
        width = w;
        height = h;
        if (!initialized) {
            snapToTarget();
            initialized = true;
        } else {
            jumped = true;
        }
        return true;
    }

    /** 四角直接吸附到目标（静态光标 / 模式切换 / 首次定位时用） */
    void snapToTarget() {
        for (TrailCorner corner : corners) {
            corner.snapTo(width, height, centerX, centerY);
        }
        jumped = false;
        maxOffset = 0;
    }

    /**
     * 推进一帧物理。
     *
     * @return true 表示仍在运动
     */
    boolean update(double dt) {
        if (jumped) {
            assignRanks();
            jumped = false;
        }
        boolean animating = false;
        maxOffset = 0;
        for (TrailCorner corner : corners) {
            if (corner.update(width, height, centerX, centerY, dt, false)) {
                animating = true;
            }
            double offset = corner.getOffsetMagnitude();
            if (offset > maxOffset) {
                maxOffset = offset;
            }
        }
        return animating;
    }

    double maxOffset() {
        return maxOffset;
    }

    /**
     * {@link #assignRanks()} 的对齐度暂存数组（复用，避免每次调用分配数组）。
     *
     * <p>长度取 {@link #CORNER_RELATIVE} 的角点数（当前 4）。这里<b>不</b>写死 4、
     * 而是从同一张表取：将来角点表若增删，长度自动跟随，不会出现「表改了 5 个、
     * 数组还是 4 个 → assignRanks 越界」。（2026-10-31 审查指出原写法写死 4。）
     */
    private final double[] alignmentScratch = new double[CORNER_RELATIVE.length];

    /**
     * 按「运动方向对齐度」给四个角点名次。
     *
     * <p>与原 JS 版一致按对齐度<b>升序</b>：rank 0 是最靠后的角，
     * 拿最长时长、最慢；越靠前 rank 越大、时长越短 ——
     * 于是前缘紧跟、后缘拖沓，矩形被拉长成拖尾形状。
     */
    private void assignRanks() {
        // 【U-L-1】对齐度也用成员数组复用 —— 同一个函数里原来有两套策略：
        // idxScratch 特意复用并配了十几行说明，alignment 却每次 new。
        // 量级确实微不足道（打字约 10 次/秒），但「同一函数两套做法」本身就是
        // 下一个改动者的陷阱：没人知道该跟哪一套。统一成复用。
        final int n = corners.length;
        double[] alignment = alignmentScratch;
        for (int i = 0; i < n; i++) {
            alignment[i] = corners[i].calculateDirectionAlignment(width, height, centerX, centerY);
        }

        // ==================== 0.9.77 修复：rank 塌缩 ====================
        //
        // 【原实现的致命缺陷】
        // 原来是「比自己小的有几个」计数：
        //     for (j) if (alignment[j] < alignment[i]) rank++;
        // 值【相等】时谁也不小于谁，于是双方拿到同一个 rank。
        //
        // 实测（V-RankCollapse，2x32 光标）：
        //   打字 →（同行）  align -0.71/0.71/0.71/-0.71  ->  rank 0 2 2 0   只有 2 档
        //   删除 ←（同行）  align  0.71/-0.71/-0.71/0.71 ->  rank 2 0 0 2   只有 2 档
        //   下一行同列      align -0.71/-0.71/0.71/0.71  ->  rank 0 0 2 2   只有 2 档
        //   上一行同列      align  0.71/0.71/-0.71/-0.71 ->  rank 2 2 0 0   只有 2 档
        //   斜跳 ↘         align -0.91/-0.41/0.91/0.41  ->  rank 0 1 3 2   4 档 OK
        //
        // 【后果】纯水平 / 纯垂直移动时，同一侧上下两角拿相同 rank →
        // 拿相同时长 → 四边形永远是【等腰梯形或矩形】，斜度恒为 0，
        // 也就是「单行打字看不到斜缎带」。
        // 斜跳因为四个对齐度天然互异，反而正常 —— 与用户观察完全吻合：
        // 「特定情况下是缎带拉伸效果但更像斜缎带」= 只在斜跳/跨行时出现。
        //
        // 【修法：排序 + 索引 tiebreak】（上游四家 JS 一致做法）
        //   .sort((a, b) => a.value === b.value ? a.index - b.index : a.value - b.value)
        // 用稳定排序给出 0/1/2/3 四个【互不相同】的 rank，
        // 并列时按角点固定顺序（TL/TR/BR/BL）拆开 —— 于是同侧上下角必然差一档，
        // 四边形出现平行四边形效果，斜缎带出来了。
        //
        // 【为什么 tiebreak 用索引而不是别的】四家上游都用索引，
        // 它保证「同样的输入永远得到同样的 rank」（确定性），
        // 且 tiebreak 方向恒定 → 斜度方向稳定（不会这帧往左斜下帧往右斜）。
        Integer[] order = idxScratch;
        if (order == null || order.length != n) {
            order = new Integer[n];
            idxScratch = order;
        }
        // 【必须每次重置为 0..n-1】
        //
        // 复用数组时如果只建一次、之后不清，order 里留着上一次的排列。
        // 稳定排序的 tiebreak 语义是「保持输入相对顺序」——
        // 输入顺序变成上一次的结果，tiebreak 就依赖历史状态。
        //
        // 实测（ZzScratchTest）：斜跳后 order = [0,1,3,2]，
        // 下一次打字时输入已是 [0,1,3,2] 而非 [0,1,2,3]。
        // 该场景下最终 rank 恰好仍收敛到 0/2/3/1（因为排序本身稳定），
        // 但这是**巧合**，不是保证：换一个对齐度组合就可能给出不同的并列拆法。
        //
        // 重置成本 = 4 次数组写入，可忽略。
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // 三路手写稳定排序（n=4，避免 Arrays.sort 的装箱 Comparator 开销）
        for (int i = 1; i < n; i++) {
            int key = order[i];
            int j = i - 1;
            while (j >= 0 && alignment[order[j]] > alignment[key]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }
        // 排序后 order[k] = 「第 k 靠后」的角点下标；k 就是它的 rank
        for (int k = 0; k < n; k++) {
            corners[order[k]].jump(width, height, centerX, centerY, k);
        }
    }

    /**
     * assignRanks 的下标暂存数组（复用，避免每次调用分配数组）。
     *
     * <p><b>为什么是 {@code Integer[]} 而不是 {@code int[]}</b>：排序时要用
     * {@link java.util.Arrays#sort(Object[], java.util.Comparator)} 或自定义
     * 比较器，它只接受对象数组。用 {@code int[]} 就得手写一套排序。
     *
     * <p>注意：这里**确实会有装箱**（{-128..127} 之外每次装箱成新对象）。
     * 但 rank 恒为 0..3，全部命中 {@code Integer} 缓存，实际零分配。
     * 所以「避免装箱」的说法不准确 —— 准确说法是「避免数组分配 + 命中装箱缓存」。
     */
    private Integer[] idxScratch;

    /**
     * 探针：当前中心点与四角是否自洽（用于断言「起飞后形状与源一致」）。
     */
    double[] cornersForProbe() {
        return new double[]{
                corners[0].getCurrentX(), corners[0].getCurrentY(),
                corners[1].getCurrentX(), corners[1].getCurrentY(),
                corners[2].getCurrentX(), corners[2].getCurrentY(),
                corners[3].getCurrentX(), corners[3].getCurrentY()
        };
    }

    /** 诊断只读：委托 {@link #lastVisualLine()}（保留旧名字给既有诊断调用）。 */
    int lastVisualLineForProbe() {
        return lastVisualLine();
    }

    /**
     * 诊断只读：四角最近一次 jump() 的真实短跳判定。
     *
     * <p>不做任何计算、不复制判据：TrailCorner.lastShortJump 是生产 jump()
     * 在 decided=true 时写入的最终值。反射失败时返回 {@code null}，由调用方跳过
     * 该行并留 {@code [error]} 证据；绝不把失败伪装成 CROSS。
     */
    Boolean lastBranchShortForProbe() {
        try {
            java.lang.reflect.Field f = TrailCorner.class.getDeclaredField("lastShortJump");
            f.setAccessible(true);
            return f.getBoolean(corners[0]);
        } catch (Throwable t) {
            CursorDiag.swallowed("SpringTrail.lastBranchShortForProbe", t);
            return null;
        }
    }
}
