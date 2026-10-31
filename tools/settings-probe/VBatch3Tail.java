import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

/**
 * V-BATCH3-TAIL —— 第 3 批收尾（5 条 MEDIUM）的守门探针（2026-10-31）。
 *
 * <p>本批全部是内部质量改进，正常使用零观感变化；因此断言全部落在
 * <b>生产真在调用的方法</b>上，不写任何「复制品算法」。
 *
 * <ul>
 *   <li><b>T-1（P-M-9）</b>：两个抑制标志的<b>嵌套</b>语义 —— 外层已置 true 时，
 *       一条真实回灌路径跑完后，标志必须仍是 true（旧写法在内层 finally 里硬置 false，
 *       会把外层清掉）。单层调用观察不到这个差异，故必须走中间态入口。</li>
 *   <li><b>T-2（P-M-4）</b>：检查更新失败时按钮必须有可见文案（不能复位成「检查更新」），
 *       超时 / 404 / 版本相同 / 有新版本四种结果话术正确。</li>
 *   <li><b>T-3（U-M-6）</b>：连续「到期但投不出去」的退避曲线（前 10 轮 1ms → 指数到 32ms）、
 *       日志节流、计数是 loop 局部变量。</li>
 *   <li><b>T-4（U-M-8）</b>：块选区 key 在行号查询失败时沿用上一帧、首见才 offset 兜底；
 *       生产真的调用它（结构断言）。</li>
 *   <li><b>T-5（P-M-1）</b>：apply 中途抛出后 State（含预设 5 件套与 paintDiag）逐项回滚、
 *       pending 保留、baseline 未前移、有提示、重试成功。</li>
 * </ul>
 */
public final class VBatch3Tail {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-BATCH3-TAIL：第 3 批收尾（MEDIUM）===");

        suppressionNesting();
        pluginInfoOutcome();
        schedulerBusyBackoff();
        blockKeySticky();
        applyRollback();

        System.out.println();
        System.out.println("=== V-BATCH3-TAIL 结果：PASS " + pass + "，FAIL " + fail + " ===");
        if (fail == 0) {
            System.out.println("| V-BATCH3-TAIL RESULT: ALL PASS");
        } else {
            System.out.println("| V-BATCH3-TAIL RESULT: FAILED " + fail);
            System.exit(1);
        }
    }

    // ==================== T-1（P-M-9）：抑制标志嵌套 ====

    private static void suppressionNesting() {
        System.out.println();
        System.out.println("--- T-1（P-M-9）：嵌套两层后，外层仍处于抑制态 ---");

        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        boolean[] first = conf.suppressionNestingForProbe();
        boolean[] second = conf.suppressionNestingForProbe();
        check("T-1a 嵌套调用结束后 syncingControls 仍为 true（外层没被内层清掉）",
                second[0], "实际 " + second[0]);
        check("T-1b 嵌套调用结束后 suppressDerive 仍为 true",
                second[1], "实际 " + second[1]);

        // 探针自清理后，真实状态必须是 false（否则证明本探针把面板弄脏了）
        check("T-1c 探针收尾后 syncingControls 回到 false（探针不污染面板）",
                !conf.syncingForProbe(), "实际 " + conf.syncingForProbe());
        check("T-1d 探针收尾后 suppressDerive 回到 false",
                !conf.suppressDeriveForProbe(), "实际 " + conf.suppressDeriveForProbe());
        check("T-1e 两次嵌套调用的结果一致",
                first[0] == second[0] && first[1] == second[1],
                java.util.Arrays.toString(first) + " vs " + java.util.Arrays.toString(second));
    }

    // ==================== T-2（P-M-4）：检查更新失败可见 ====

    private static void pluginInfoOutcome() {
        System.out.println();
        System.out.println("--- T-2（P-M-4）：检查更新失败必须在按钮上有可见出口 ---");
        try {
            Class<?> cls = Class.forName(
                    "com.github.yyyolo7a79.neovidecursor.ui.settings.PluginInfoBar");
            java.lang.reflect.Method m = cls.getDeclaredMethod(
                    "resolveOutcome", String.class, String.class, String.class);
            m.setAccessible(true);

            Object timeout = m.invoke(null, new Object[]{null, "0.9.104", "网络超时（4 秒无响应）"});
            String timeoutText = field(timeout, "text");
            String timeoutTip = field(timeout, "tooltip");
            boolean timeoutDl = (Boolean) fieldObj(timeout, "downloadable");
            check("T-2a 超时：按钮文字必须与点击前不同且含「检查失败」",
                    !"检查更新".equals(timeoutText) && timeoutText.contains("检查失败"),
                    timeoutText);
            check("T-2b 超时：tooltip 含具体原因", timeoutTip.contains("网络超时"), timeoutTip);
            check("T-2c 超时：不进入可下载态", !timeoutDl, "downloadable=" + timeoutDl);

            Object noRelease = m.invoke(null, new Object[]{null, "0.9.104", "仓库暂无 Release"});
            check("T-2d 404：tooltip 保留「仓库暂无 Release」",
                    field(noRelease, "tooltip").contains("仓库暂无 Release"),
                    field(noRelease, "tooltip"));

            Object same = m.invoke(null, new Object[]{"v0.9.104", "0.9.104", null});
            check("T-2e 版本相同：仍显示「已是最新」",
                    field(same, "text").contains("已是最新"), field(same, "text"));

            Object newer = m.invoke(null, new Object[]{"v0.9.105", "0.9.104", null});
            check("T-2f 有新版：可下载且文字含版本号",
                    (Boolean) fieldObj(newer, "downloadable")
                            && field(newer, "text").contains("v0.9.105"),
                    field(newer, "text"));

            Object unknown = m.invoke(null, new Object[]{null, "0.9.104", null});
            check("T-2g error 为 null 时不抛且仍有「检查失败」文案",
                    field(unknown, "text").contains("检查失败"), field(unknown, "text"));
        } catch (Throwable t) {
            check("T-2 反射调用 PluginInfoBar.resolveOutcome 失败", false, t.toString());
        }
    }

    // ==================== T-3（U-M-6）：调度退避升级 ====

    private static void schedulerBusyBackoff() {
        System.out.println();
        System.out.println("--- T-3（U-M-6）：连续「到期但投不出去」必须退避升级 + 有日志 ---");
        try {
            Class<?> rs = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.RenderScheduler");
            java.lang.reflect.Method backoff =
                    rs.getDeclaredMethod("busyBackoffMs", int.class);
            backoff.setAccessible(true);
            java.lang.reflect.Method shouldLog =
                    rs.getDeclaredMethod("shouldLogBusy", int.class);
            shouldLog.setAccessible(true);

            boolean flatFirst10 = true;
            for (int i = 1; i <= 10; i++) {
                if (((Long) backoff.invoke(null, i)) != 1L) {
                    flatFirst10 = false;
                }
            }
            check("T-3a 前 10 轮保持 1ms（EDT 正常繁忙不误判为故障）", flatFirst10, "见 1..10");

            long[] expected = {2, 4, 8, 16, 32, 32, 32};
            boolean curveOk = true;
            StringBuilder got = new StringBuilder();
            for (int i = 0; i < expected.length; i++) {
                long v = (Long) backoff.invoke(null, 11 + i);
                got.append(11 + i).append("→").append(v).append(' ');
                if (v != expected[i]) {
                    curveOk = false;
                }
            }
            check("T-3b 第 11 轮起指数退避并封顶 32ms", curveOk, got.toString());
            check("T-3c 极长卡死仍封顶 32ms（不会无限增长）",
                    ((Long) backoff.invoke(null, 100000)) == 32L,
                    "100000 → " + backoff.invoke(null, 100000));

            check("T-3d 第 10 轮（开始退避）必打日志（不静默）",
                    (Boolean) shouldLog.invoke(null, 10), "10");
            check("T-3e 第 1/2 轮不打（EDT 瞬态繁忙不刷屏）",
                    !(Boolean) shouldLog.invoke(null, 1) && !(Boolean) shouldLog.invoke(null, 2),
                    "1/2");
            check("T-3f 每 100 轮一条",
                    !(Boolean) shouldLog.invoke(null, 99) && (Boolean) shouldLog.invoke(null, 100),
                    "99/100");

            // 结构：计数必须是 loop() 的局部变量，不能 static（H-1 的教训）
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    repoRoot(),
                    "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/RenderScheduler.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("T-3g 连续未投递计数是 loop 局部变量，不是 static 字段",
                    src.contains("int consecutiveNotPosted = 0;")
                            && !src.contains("static int consecutiveNotPosted"),
                    "结构断言");
        } catch (Throwable t) {
            check("T-3 反射调用 RenderScheduler 的退避函数失败", false, t.toString());
        }
    }

    // ==================== T-4（U-M-8）：块选区 key 不退化 ====

    private static void blockKeySticky() {
        System.out.println();
        System.out.println("--- T-4（U-M-8）：行号查询失败时 key 必须沿用上一帧 ---");
        try {
            Class<?> ca = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator");
            java.lang.reflect.Method resolve = ca.getDeclaredMethod(
                    "resolveBlockKey", int.class, int.class, Object.class);
            resolve.setAccessible(true);

            Object k1 = resolve.invoke(null, 5, 100, null);
            check("T-4a 行号可用 → 行号 key（-1000005）",
                    Integer.valueOf(-1000005).equals(k1), String.valueOf(k1));

            Object k2 = resolve.invoke(null, -1, 999, k1);
            check("T-4b 行号失败 + 有上一帧 → 沿用（不退化到 offset 999）",
                    k1.equals(k2), String.valueOf(k2));

            Object k3 = resolve.invoke(null, -1, 777, null);
            check("T-4c 行号失败 + 首见 → offset 兜底（777）",
                    Integer.valueOf(777).equals(k3), String.valueOf(k3));

            Object k4 = resolve.invoke(null, 6, 999, k2);
            check("T-4d 行号恢复 → 回到行号 key（不是 offset）",
                    Integer.valueOf(-1000006).equals(k4), String.valueOf(k4));

            // 连续三帧失败：key 必须完全稳定
            Object cur = k1;
            boolean stable = true;
            for (int f = 0; f < 3; f++) {
                Object next = resolve.invoke(null, -1, 999 + f, cur);
                if (!cur.equals(next)) {
                    stable = false;
                }
                cur = next;
            }
            check("T-4e 连续三帧失败 key 保持稳定（不每帧重建）", stable, String.valueOf(cur));

            // 结构：生产真的调用了它，且 catch 不再是空吞
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    repoRoot(),
                    "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("T-4f 结构：refreshTarget 调用 resolveBlockKey（防「纯函数绿、生产没用」）",
                    src.contains("key = resolveBlockKey(lineNo, blockStarts[i], prev)"),
                    "结构断言");
            check("T-4g 结构：块选区行号的 catch 接入了诊断通道",
                    src.contains("swallowedQuietly(\"块选区行号\", t)"),
                    "结构断言");
        } catch (Throwable t) {
            check("T-4 反射调用 CaretAnimator.resolveBlockKey 失败", false, t.toString());
        }
    }

    // ==================== T-5（P-M-1）：apply 中途抛出必须回滚 ====

    private static void applyRollback() {
        System.out.println();
        System.out.println("--- T-5（P-M-1）：apply 中途抛出必须回滚到调用前 ---");
        try {
            V0Env.install();
            NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
            VUi ui = VUi.build(conf);
            conf.reset();

            NeovideCursorSettings.State before = new NeovideCursorSettings.State();
            before.copyFrom(V0Env.svc.getState());
            boolean paintBefore = NeovideCaretManager.isPaintDisabled();

            // 必须走真实控件：apply() 的第一段 super.apply() 会把控件值写回 pending，
            // 直接改 pending 会被覆盖（这正是「用户编辑」的生产路径）。
            javax.swing.JSlider slider = ui.sliderByKey("animationLength");
            javax.swing.text.JTextComponent tf = ui.textFieldOf(slider);
            int origTick = VUi.valueOf(tf);
            // animationLength 的量程是 5..35（每 tick 10ms，见 VUi.SPEC[8]）。
            int newTick = (origTick == 20) ? 25 : 20;
            tf.setText(String.valueOf(newTick));
            float newVal = newTick / 100f;

            // G-1：注入故障 → apply 不抛、State 完全回滚
            conf.setApplyFaultForProbe(() -> {
                throw new RuntimeException("injected-by-VBatch3Tail");
            });
            boolean threw = false;
            try {
                conf.apply();
            } catch (Throwable t) {
                threw = true;
            }
            check("T-5a 注入故障后 apply 不把异常抛给平台", !threw, "threw=" + threw);

            java.util.List<String> diffs = new java.util.ArrayList<>();
            for (SettingsField sf : SettingsField.FIELDS) {
                if (sf.differs(before, V0Env.svc.getState())) {
                    diffs.add(sf.key);
                }
            }
            check("T-5b 故障后 State 与 apply 前逐字段一致（无半新半旧）",
                    diffs.isEmpty(), "差异字段=" + diffs);

            // 【code-reviewer M-2】预设 5 件套**不在** SettingsField.FIELDS 里（apply 里手工脏合并），
            // 只遍历 FIELDS 会漏掉它们 —— 而 P-M-1 的回滚正是靠 State.copyFrom 覆盖。
            NeovideCursorSettings.State now = V0Env.svc.getState();
            boolean metaSame = java.util.Objects.equals(before.customAnimPresets, now.customAnimPresets)
                    && java.util.Objects.equals(before.animCustomName, now.animCustomName)
                    && java.util.Objects.equals(before.customColorPresets, now.customColorPresets)
                    && java.util.Objects.equals(before.colorCustomName, now.colorCustomName)
                    && before.colorPresetBase == now.colorPresetBase;
            check("T-5b2 故障后预设 5 件套也回滚（它们不在 SettingsField.FIELDS 里）",
                    metaSame, "5 件套有差异");
            check("T-5b3 故障后 paintDiag 与调用前一致",
                    NeovideCaretManager.isPaintDisabled() == paintBefore,
                    "paintDiag 变了");

            check("T-5c 用户编辑仍保留在 pending（可重试）",
                    Math.abs(conf.pendingForProbe().animationLength - newVal) < 1e-3,
                    "pending=" + conf.pendingForProbe().animationLength);

            boolean baselineSame = true;
            for (SettingsField sf : SettingsField.FIELDS) {
                if (sf.differs(before, conf.baselineForProbe())) {
                    baselineSame = false;
                }
            }
            check("T-5d baseline 未前移（重试链路仍通）", baselineSame, "baseline 变了");

            check("T-5e 失败后 isModified() 仍为 true（应用按钮可用）",
                    conf.isModified(), "isModified=false");

            String notice = conf.lastUserNoticeForProbe();
            check("T-5f 有可见提示「应用失败」",
                    notice != null && notice.contains("应用失败"), String.valueOf(notice));

            // G-2：清掉故障后重试必须成功，且值真的落地（证明回滚不破坏正常路径）
            conf.setApplyFaultForProbe(null);
            conf.apply();
            check("T-5g 重试成功：State 采用新值（正常路径仍正常）",
                    Math.abs(V0Env.svc.getState().animationLength - newVal) < 1e-3,
                    "state=" + V0Env.svc.getState().animationLength);
            check("T-5h 重试成功后 isModified() 归 false",
                    !conf.isModified(), "isModified=true");
        } catch (Throwable t) {
            check("T-5 执行失败", false, t.toString());
        }
    }

    // ==================== 工具 ====================

    /**
     * 仓库根（code-reviewer M-1）：不能用相对路径直接读源码 —— java 进程的 cwd 是
     * 调用者目录，`build-v-probes.ps1 -Repo <worktree>` 时相对路径会读到**主仓库**，
     * 结构断言假绿。与 VBuildDecl / VDocCheck 同一约定：先看 {@code neovide.repo}。
     */
    private static String repoRoot() {
        String r = System.getProperty("neovide.repo");
        return (r != null && !r.isEmpty()) ? r : System.getProperty("user.dir", ".");
    }

    private static String field(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return String.valueOf(f.get(o));
        } catch (Throwable t) {
            return "<field-error:" + t + ">";
        }
    }

    private static Object fieldObj(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  [" + detail + "]");
        }
    }
}
