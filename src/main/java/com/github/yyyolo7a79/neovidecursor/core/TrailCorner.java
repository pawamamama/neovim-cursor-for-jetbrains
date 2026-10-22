package com.github.yyyolo7a79.neovidecursor.core;

/**
 * 拖尾角点 —— 光标矩形的四个角之一，每个角独立挂一组 X/Y 阻尼弹簧。
 *
 * <p><b>这是「拖尾」效果的核心机制。</b>光标移动时，按「运动方向与角点方向的夹角」
 * 给四个角分配不同的滞后系数：位于运动前方的角快速跟上（甚至瞬间硬吸附），
 * 位于后方的角缓慢跟随。于是原本规整的矩形被"拉长"成不规则四边形，
 * 视觉上就形成了拖尾 —— 不是叠加残影，而是光标自身被拉伸变形。
 *
 * <p>移植自 VS Code 版 {@code neovide-cursor.js} 的 {@code Corner} 类（第 103-208 行）。
 */
public class TrailCorner {

    // 【曾有一个 MIN_ANIMATION_FRAMES = 6.0 的低帧率补偿，已移除】
    //
    // 它强制每个角的动画时长至少为 6×dt。本意是防止短动画被
    // DampedSpring.update() 开头的 `animationLength <= dt` 短路吃掉，
    // 但副作用是：短距离移动时四角的时长被抬到同一值（0.015~0.05s 全变成
    // 约 0.06s），梯度消失，光标只是整体平移、不再被拉伸。
    //
    // 原版在 60fps 下最短的角本就一帧到位，这是设计行为而非缺陷。
    // 上游 README 踩坑 #20 记录了同一个错误。

    /** 角点相对光标矩形的归一化位置，例如 (-0.5, -0.5) 表示左上角 */
    private final double relativeX;
    private final double relativeY;

    /** 角点方向的单位向量，用于计算方向对齐度 */
    private final double relativeNormX;
    private final double relativeNormY;

    /** 当前实际坐标 */
    private double currentX;
    private double currentY;

    /** 上一次的目标坐标（初始设为极远，保证首帧一定触发位移重算） */
    private double previousDestX = -1e5;
    private double previousDestY = -1e5;

    /** X / Y 两个方向各挂一个独立弹簧 */
    private final DampedSpring springX;
    private final DampedSpring springY;

    private final NeovideConfig config;

    /**
     * 本次移动跨越的<b>字符个数</b>（由 {@code CaretAnimator} 每帧用
     * {@code |CaretModel.getOffset() 差值|} 给出）。
     *
     * <p>负数表示「调用方尚未提供」，此时 {@link #jump} 退回旧的归一化判定。
     *
     * <p><b>为什么用字符偏移而不是像素</b>：偏移量是编辑器的原生坐标，
     * 直接给出「移动了几个字符」，与字体、字号、全角半角、连字全部无关 ——
     * 中/英/日/韩/阿拉伯/emoji 一视同仁。早期的像素反推方案在中文上反复失准，
     * 详见 {@link #jump} 的说明。
     */
    private int charsMoved = -1;

    public TrailCorner(double relativeX, double relativeY, NeovideConfig config) {
        this.relativeX = relativeX;
        this.relativeY = relativeY;
        this.config = config;

        // 归一化方向向量：长度为 1，只保留方向信息
        double len = Math.hypot(relativeX, relativeY);
        this.relativeNormX = len != 0 ? relativeX / len : 0;
        this.relativeNormY = len != 0 ? relativeY / len : 0;

        this.springX = new DampedSpring(config.animationLength);
        this.springY = new DampedSpring(config.animationLength);
    }

    /**
     * 设置本次移动跨越的字符个数（由 {@code CaretAnimator} 在 jump 前写入）。
     *
     * <p>传负数表示未知，判定会退回兼容分支。
     */
    public void setCharsMoved(int charsMoved) {
        this.charsMoved = charsMoved;
    }

    // ==================== 目标位置计算 ====================

    private double destX(double centerX, double width) {
        return centerX + relativeX * width;
    }

    private double destY(double centerY, double height) {
        return centerY + relativeY * height;
    }

    /**
     * 计算「角点运动方向」与「角点相对方向」的对齐度。
     *
     * @return 范围 -1 ~ 1：接近 1 表示该角正位于运动前方，接近 -1 表示位于后方
     */
    public double calculateDirectionAlignment(double width, double height,
                                              double centerX, double centerY) {
        double dx = destX(centerX, width) - currentX;
        double dy = destY(centerY, height) - currentY;
        double len = Math.hypot(dx, dy);
        if (len == 0) {
            return 0;
        }
        return (dx / len) * relativeNormX + (dy / len) * relativeNormY;
    }

    // ==================== 弹簧参数分配 ====================

    /**
     * 光标跳动时调用：根据位移大小与运动方向，为该角点选择合适的弹簧时长。
     *
     * <p>分配规则（与原 JS 版一致）：
     * <ul>
     *   <li>位移小 → 用 shortAnimationLength，动作干脆</li>
     *   <li>角点位于运动最前方 → 硬吸附，瞬间到位，避免前缘拖糊</li>
     *   <li>其余按 rank 名次取对应滞后系数，越靠后越慢</li>
     * </ul>
     *
     * <p><b>【忠实还原原版】</b>本方法的时长分配已按 VS Code 原版
     * {@code neovide-cursor.js} 的 {@code Corner.jump()}（第 149-173 行）对齐：
     *
     * <pre>
     *   isShortJump = |jumpX| ≤ 2.001 AND |jumpY| ≤ 0.001     // 纵向必须在同一行
     *   leading  = animationLength × (1 - trailSize)           // trailSize=1 时为 0
     *   trailing = animationLength
     *   rank ≥ 2 → leading
     *   rank = 1 → (leading + trailing) / 2
     *   rank = 0 → trailing
     * </pre>
     *
     * <p>原版<b>没有</b>「至少跨 N 帧」的低帧率补偿，也<b>没有</b>
     * {@code trailFactors} 四档系数 —— 那是本插件早期自创的。
     *
     * @param rank 按对齐度<b>升序</b>的名次：0 = 最靠后（最长动画、最慢），
     *             3 = 最靠前（最短动画、最跟手）。与原版一致
     */
    public void jump(double width, double height, double centerX, double centerY, int rank) {
        double destX = destX(centerX, width);
        double destY = destY(centerY, height);

        // 归一化位移（以「光标自身尺寸」为单位）—— 保留供排版调试参考
        double deltaXpx = destX - previousDestX;
        double deltaYpx = destY - previousDestY;

        double animationLength;

        // ==================== 短跳判定 ====================
        //
        // 【语义】「同一行内、移动不超过 N 个字符」→ 走短动画（有拉伸、连续）。
        //        否则走跨行分支（前缘瞬时吸附的大幅拖尾）。
        //
        // 【为什么最终用「字符偏移差」，而不是像素或字体度量】
        //
        // 这是 IntelliJ 平台的标准做法：编辑器本来就用字符偏移（offset）
        // 定位，判断「移动了几个字符」应该直接比较偏移量，而不是反推像素。
        // 前三种尝试都失败过，记录于此避免重犯：
        //
        //   1. 用光标宽归一化（原版 JS 的 |jumpX| ≤ 2.001）
        //      原版光标 8px、本插件 2px，同样移动 1 个字符 jumpX 差 4 倍，
        //      本插件永远超不过阈值 → shortAnimationLength 永不生效。
        //
        //   2. 写死像素阈值（16px）
        //      与字号强耦合。字号 20px 时中文全角约 20px，
        //      删一个中文就超出 → 误判成跳转 → 前缘时长 0 → 硬切。
        //      这是「英文不卡、删中文卡」的第一版成因。
        //
        //   3. 像素 ÷ FontMetrics.charWidth('m')
        //      'm' 是西文推进量，中文是它的 2 倍，再乘经验倍数
        //      只是把魔数换个位置，且取样位置错（取行首字符而非光标处字符）。
        //
        //   4. 像素 ÷「第0列到第1列实测宽度」
        //      取的是<b>行首</b>字符宽度。行首是窄英文、光标在中文区时，
        //      比值仍会翻倍 → 中文依旧误判。这是「中文卡、英文丝滑」的第二版成因。
        //
        // 【现方案】charsMoved 由 CaretAnimator 用
        //   |editor.getCaretModel().getOffset() - 上一帧 offset|
        // 直接给出，是<b>精确的字符个数</b>：
        //   · 中/英/日/韩/阿拉伯/emoji（代理对按 2 计）全部正确
        //   · 与字体、字号、全角半角、连字完全无关
        //   · 整数，无测量误差；getOffset() 也比两次 visualPositionToXY 便宜
        //
        // 【缺陷 2 —— 审计确认，本版修复】
        //
        // 问题：这个差值是「自上一帧以来的<b>累计</b>量」。而帧率是会变的：
        //   · 常驻刷新：每 8ms 一帧 → 每次击键都被单独看到，charsMoved = 1
        //   · 空闲降频：每 120~600ms 一帧 → 一帧内可能累积 3~5 次击键
        // 后者会算出 charsMoved = 4 之类，越过 typingMaxChars(3) → 切跨行分支
        // → 前缘时长为 0 被短路 → 拉伸暴涨约 4.5 倍。
        // 这正是「开了常驻刷新可用、不开就卡得更厉害」的机制：
        // 同一个操作，常驻时 charsMoved=1，降频时可能是 4。
        //
        // 修复：判据不再直接用「累计字符数」，而是看<b>是否沿同一方向持续推进</b>
        // ——打字/退格本质是逐字符、同方向的事件流。只要纵向在同一行、
        // 且横向位移没有超出「一次合理跳转」的像素上限，就归为短跳。
        // 这样无论一帧合并了几次击键，行为都一致。
        boolean isShortJump;
        if (charsMoved >= 0) {
            boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
            isShortJump = sameLine && charsMoved <= config.typingMaxChars;
        } else {
            // 兜底：调用方尚未提供字符偏移时，退回按归一化长度判定
            double jumpX = deltaXpx / width;
            double jumpY = deltaYpx / height;
            isShortJump = Math.abs(jumpX) <= config.shortMoveThreshold && Math.abs(jumpY) <= 0.001;
        }

        if (isShortJump) {
            // ---- 【关键修正】短跳也必须保留 rank 梯度 ----
            //
            // 原版短跳分支给四角【同一个】时长（min(animationLength, shortAnimationLength)），
            // 四角等速 → 形状变化只由起点差异决定 → 拉伸几乎看不见。
            //
            // 实测反馈：「单行输入光标快到拉伸没有表现，看着像卡了一下」。
            // 原因就在这里 —— 40ms（约 5 帧）且四角完全同速。
            //
            // 改为仍然乘 trailFactors，让后缘慢、前缘快：
            //   shortAnimationLength=0.08 时四角为 80/72/40/24ms，梯度完整保留。
            double factor = (rank < config.trailFactors.length ? config.trailFactors[rank] : 1.0f);
            animationLength = config.shortAnimationLength * clamp(factor, 0, 1);

            // ---- 【C 方案：前缘与后缘解耦】----
            //
            // 前缘两角（rank 2/3）取 max(原时长, leadingAnimationLength)；
            // 后缘两角（rank 0/1）完全不动。
            //
            // 【为什么必须解耦】结构性矛盾（第三轮审查实测）：
            //   要平滑  → shortAnimationLength 大 → 前缘帧数够
            //   要短促  → shortAnimationLength 小 → 后缘拖尾短
            // 两者共用同一个乘数，数学上不可兼得（k=8 时需 0.229s vs 0.036s）。
            //
            // 【为什么默认 0 时行为完全不变】leadingAnimationLength == 0 时
            // max(x, 0) == x，四角时长与历史版本逐位相同 → parity 默认快照不动。
            if (config.leadingAnimationLength > 0f && rank >= 2) {
                double lead = config.leadingAnimationLength;
                if (lead > animationLength) {
                    animationLength = lead;
                }
            }
        } else {
            // ---- 原版三档分配（跨行 / 大跨度）----
            //
            // 实测确认这一档效果「比认可的那版更好，因为是不规则矩形」，
            // 故严格保持原版逻辑不动。
            double leading = config.animationLength * clamp(1f - config.trailSize, 0f, 1f);
            double trailing = config.animationLength;
            if (rank >= 2) {
                animationLength = leading;
            } else if (rank == 1) {
                animationLength = (leading + trailing) / 2.0;
            } else {
                animationLength = trailing;
            }
        }

        // 【不做低帧率时长补偿】
        // 原版没有「至少跨 N 帧」的逻辑。曾加过（MIN_ANIMATION_FRAMES），
        // 结果短距离移动时四角时长被抬到同一值、梯度消失、光标只平移不拉伸。
        // 上游 README 踩坑 #20 也记录了同一个错误。忠实还原 = 不加。

        springX.setAnimationLength(animationLength);
        springY.setAnimationLength(animationLength);

        // ==================== 速度归零（关键修复） ====================
        //
        // 【问题】同一行内「删除一个字符」会先停顿一下才跟上，而「输入」很顺。
        //
        // 根因是 velocity 残留方向相反：
        //   - 输入：光标右移，残留速度也是向右 → 帮了忙，顺滑
        //   - 删除：光标左移，残留速度仍向右 → 第一帧逆着走、几乎不动，
        //           随后才被拉回来 → 用户感知为「停顿一下」
        //
        // 用闭式解模拟（animationLength=0.08、残留 vel=+500、目标位移 -8）：
        //   帧1: pos -8.000 → -4.826（实际只走了 3.2px，且方向被拽回）
        //   帧2: -2.876   帧3: -1.687  —— 前两帧明显发滞
        //
        // 而原来的守卫是 `animationLength > animationResetThreshold(90ms)` 才 reset。
        // 短跳四角时长为 80/72/40/24ms，【没有一个超过 90ms】→ 四角全都不 reset
        // → velocity 一路带着上一次移动的残余。
        //
        // 【修复】只要弹簧当前还有残余速度/位移，就归零 ——
        // 不再用「动画时长」这个与速度无关的量来当判据。
        //
        // 这不是偏离原版：原版 JS 的 Corner.jump() 结尾是【无条件】
        // `this.animationX.reset(); this.animationY.reset();`
        // 反而是本插件早期加的 animationResetThreshold 守卫引入了缺陷。
        springX.reset();
        springY.reset();
    }

    // ==================== 物理推进 ====================

    /**
     * 推进该角点的物理状态。
     *
     * @param immediate true = 立即到位（用于滚动等需要"贴死"的场景）
     * @return true 表示仍在运动，需要继续重绘
     */
    public boolean update(double width, double height, double centerX, double centerY,
                          double dt, boolean immediate) {
        double destX = destX(centerX, width);
        double destY = destY(centerY, height);

        // 目标变化时，用「目标 - 当前实际」重算弹簧的偏移基准
        if (destX != previousDestX || destY != previousDestY) {
            springX.setPosition(destX - currentX);
            springY.setPosition(destY - currentY);
            previousDestX = destX;
            previousDestY = destY;
        }

        if (immediate) {
            currentX = destX;
            currentY = destY;
            springX.reset();
            springY.reset();
            return false;
        }

        springX.update(dt);
        springY.update(dt);

        // 限制最大偏移，防止跨屏跳转等极端情况产生夸张拉伸
        double maxDistance = Math.max(width, height) * config.maxTrailDistanceFactor;
        clampSpring(springX, maxDistance);
        clampSpring(springY, maxDistance);

        currentX = destX - springX.getPosition();
        currentY = destY - springY.getPosition();

        return Math.abs(springX.getPosition()) > 0.5 || Math.abs(springY.getPosition()) > 0.5;
    }

    /** 立即把角点摆到目标位置（初始化用） */
    public void snapTo(double width, double height, double centerX, double centerY) {
        double destX = destX(centerX, width);
        double destY = destY(centerY, height);
        currentX = destX;
        currentY = destY;
        previousDestX = destX;
        previousDestY = destY;
        springX.reset();
        springY.reset();
    }

    private static void clampSpring(DampedSpring spring, double max) {
        double p = spring.getPosition();
        if (p > max) {
            spring.setPosition(max);
        } else if (p < -max) {
            spring.setPosition(-max);
        }
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ==================== 访问器 ====================

    public double getCurrentX() {
        return currentX;
    }

    public double getCurrentY() {
        return currentY;
    }

    /**
     * 当前角点相对目标的偏移量（像素）——即拖尾的"展开程度"。
     *
     * <p>完全静止时四角归位，偏移为 0；只要光标在动，偏移至少是一个字符宽或一行高。
     * 绘制辉光时据此淡入淡出：辉光是"运动中的光标"才有的效果，
     * 静止的光标不该顶着一圈光。
     */
    public double getOffsetMagnitude() {
        return Math.hypot(springX.getPosition(), springY.getPosition());
    }
}
