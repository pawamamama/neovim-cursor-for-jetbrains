import com.github.yyyolo7a79.neovidecursor.core.AfterimageTrail;
import com.github.yyyolo7a79.neovidecursor.core.DampedSpring;
import com.github.yyyolo7a79.neovidecursor.core.GlowIntensity;
import com.github.yyyolo7a79.neovidecursor.core.NeovideColors;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.intellij.openapi.diagnostic.Logger;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V-AUDIT-BATCH1 —— 审计报告 {@code docs/CODE-AUDIT-2026-10-30.md} 第六节「第 1 批」的守门探针。
 *
 * <h2>它抓的是什么</h2>
 *
 * <p>这一批 8 条（H-2 / H-3 / M-4 / M-10 / L-4 / L-5 / L-6 / L-8）的共同点：
 * <b>非法输入不会崩、只会「静默地做错事」</b>。三种形态：
 *
 * <ul>
 *   <li><b>静默失败</b>（H-2 / M-10 / L-4 / L-5）—— 解析不了就当没这回事，
 *       日志里一个字都没有，用户只能报「切了预设没反应」「光标黑了」；</li>
 *   <li><b>失败还宣称成功</b>（H-2 / H-3）—— 返回值、日志、UI 选中态全都说成功，
 *       而实际一半参数没变，最坏情况是<b>不可逆丢掉用户配置</b>；</li>
 *   <li><b>坏值污染物理状态</b>（M-4 / L-8）—— NaN 进了弹簧就再也出不来，
 *       {@code lineTo(NaN)} 被 Java2D 静默忽略 → 光标消失。</li>
 * </ul>
 *
 * <p>它们的共同点还有第二个：<b>正常路径全对</b>。所以「跑一遍功能」永远测不出来，
 * 必须把非法输入精确地喂进去。
 *
 * <h2>为什么能断言「日志打没打」</h2>
 *
 * <p>{@code Logger.setFactory(...)} 可以把平台的日志实现整个换掉。本探针在 {@code main}
 * 第一句装一个捕获工厂，之后所有生产类的 {@code Logger.getInstance(...)} 都会拿到
 * 本探针的 {@link Capturing}，于是「某条 warn 有没有被打过」变成可断言的事实。
 *
 * <p><b>工厂必须是第一句，不能更晚</b>：生产类普遍写成
 * {@code private static final Logger LOG = Logger.getInstance(X.class)}，它在类<b>第一次被触达</b>
 * 时就创建好了。工厂装晚了，那些实例已经是 {@code DefaultLogger}，本探针所有
 * 「必须有日志」的断言都会永久红 —— 而且看起来像「生产没打日志」，把人引向完全错误的方向。
 * {@link #a0()} 那条自检就是用来把这两种情况分开的。
 *
 * <h2>关于 {@code *ForProbe()} 访问器</h2>
 *
 * <p>A-13 / A-14 / A-23 / A-24 需要读/写生产类的私有状态（{@code DampedSpring.velocity}、
 * {@code TrailCorner.animationLength}）。这三个访问器是<b>纯新增</b>、零行为变化，
 * 与修复本身无关；变异测试会单独证明「删掉守卫会红」，不会把「访问器存在」当成修复。
 *
 * <h2>运行前提</h2>
 *
 * <p>与 {@code VDocCheck} / {@code VBuildDecl} 一致：A-22 要读仓库根下的源码，
 * 优先取系统属性 {@code neovide.repo}（由 {@code build-v-probes.ps1} 传入），
 * 否则从 {@code user.dir} 向上最多 4 层找 {@code CLAUDE.md}。找不到就 FAIL，绝不静默跳过。
 */
public final class VAuditBatch1 {

    private static int pass;
    private static int fail;

    /** 捕获到的 warn / error（category 不区分，按消息内容断言）。 */
    private static final List<String> WARNS = Collections.synchronizedList(new ArrayList<>());
    /** 捕获到的 info。用来断言「有没有宣称成功」。 */
    private static final List<String> INFOS = Collections.synchronizedList(new ArrayList<>());

    private static final String OLD_KEY_FPS = "neovide.cursor.alwaysHighFps";
    private static final String OLD_KEY_FADE = "neovide.cursor.overlayFadeOut";

    public static void main(String[] args) {
        // 【必须第一句】见类注释：生产类的 static final Logger 在类首次被触达时就创建了。
        Logger.setFactory(new CapturingFactory());

        System.out.println("=== V-AUDIT-BATCH1：审计第 1 批（8 条健壮性修复）===");

        a0();
        h2();
        h3();
        l5();
        l4();
        m4();
        m10();
        l6();
        l8();

        finish();
    }

    // ==================== A-0 机制自检 ====================

    private static void a0() {
        int m = mark();
        Logger.getInstance("vaudit-selftest").warn("selftest-marker");
        check("A-0 日志捕获机制自检（它红 = 捕获坏了，其余「必须有日志」的断言都不算数）",
                anyWarnSince(m, "selftest-marker"), "");
    }

    // ==================== H-2：恢复失败不可观测 ====================

    private static void h2() {
        System.out.println();
        System.out.println("--- H-2 自定义预设恢复失败必须留痕、且不能宣称成功 ---");

        // ---- A-1：已知键 + 非法值 ----
        NeovideCursorSettings.State st = new NeovideCursorSettings.State();
        int m1 = mark();
        boolean ok1 = SettingsField.byKey("tailOpacity").decode(st, "abc");
        check("A-1 「已知键 + 值非法」：decode 返回 false，且日志里能查到是哪个键、什么值",
                !ok1 && anyWarnSince(m1, "tailOpacity") && anyWarnSince(m1, "abc"),
                "decode=" + ok1 + " warn=" + brief(m1));

        // ---- A-2：计数契约不变（一坏一好 → 1）----
        NeovideCursorSettings.State st2 = new NeovideCursorSettings.State();
        int applied2 = SettingsField.restoreSkipping(st2, "tailOpacity=abc;trailSize=0.4");
        check("A-2 计数契约不变：一坏一好 → applied == 1（加日志不许顺手改计数）",
                applied2 == 1, "applied=" + applied2);

        // ---- A-3：超长非法值必须截断 ----
        int m3 = mark();
        SettingsField.byKey("tailOpacity").decode(new NeovideCursorSettings.State(),
                repeat('x', 400));
        String w3 = lastWarnSince(m3);
        check("A-3 超长非法值必须截断（几 KB 的值打进日志会把真正的原因淹掉）",
                w3 != null && w3.length() < 160 && !w3.contains(repeat('x', 100)),
                w3 == null ? "没有日志" : ("长度=" + w3.length()));

        // ---- A-4：整条快照全坏 ----
        freshEnv();
        NeovideCursorSettings s4 = V0Env.svc;
        s4.getState().customAnimPresets =
                CustomPresets.put("", "全坏", "tailOpacity=abc;glowLayers=zzz");
        int m4 = mark();
        int i4 = INFOS.size();
        boolean applied4 = CursorPresetApplier.applyCustomAnimPreset("全坏");
        boolean claimedSuccess = false;
        for (int i = i4; i < INFOS.size(); i++) {
            if (INFOS.get(i).contains("已应用自定义预设")) {
                claimedSuccess = true;
            }
        }
        check("A-4 整条快照全坏：返回 false、不写 animCustomName、不宣称「已应用」",
                !applied4 && "".equals(s4.getState().animCustomName) && !claimedSuccess
                        && anyWarnSince(m4, "没有任何可应用的字段"),
                "applied=" + applied4 + " name=[" + s4.getState().animCustomName
                        + "] 宣称成功=" + claimedSuccess);

        // ---- A-5：半条快照坏 ----
        freshEnv();
        NeovideCursorSettings s5 = V0Env.svc;
        String snap = SettingsField.snapshot(new NeovideCursorSettings.State());
        String[] parts = snap.split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith("tailOpacity=")) {
                parts[i] = "tailOpacity=abc";
            }
        }
        int total = SettingsField.PRESET_FIELDS.size();
        s5.getState().customAnimPresets = CustomPresets.put("", "半坏", String.join(";", parts));
        int m5 = mark();
        boolean applied5 = CursorPresetApplier.applyCustomAnimPreset("半坏");
        check("A-5 半条快照坏：仍然应用，但必须按 applied/total 记一条",
                applied5 && anyWarnSince(m5, (total - 1) + "/" + total),
                "applied=" + applied5 + " 期望记=" + (total - 1) + "/" + total
                        + " warn=" + brief(m5));
    }

    // ==================== H-3：迁移读失败不得推进版本、不得删旧键 ====================

    private static void h3() {
        System.out.println();
        System.out.println("--- H-3 读旧键失败 = 迁移未完成（绝不删键、不推进版本）---");

        freshEnv();
        V0Env.props.map.put(OLD_KEY_FPS, "false");
        V0Env.props.map.put(OLD_KEY_FADE, "false");
        V0Env.FakeProps.throwOnceOnIsValueSet = true;

        NeovideCursorSettings a6 = new NeovideCursorSettings();
        a6.noStateLoaded();
        check("A-6 读旧键抛一次异常：stateVersion 必须停在 0，两个旧键必须都还在",
                a6.getState().stateVersion == 0
                        && V0Env.props.map.containsKey(OLD_KEY_FPS)
                        && V0Env.props.map.containsKey(OLD_KEY_FADE),
                "version=" + a6.getState().stateVersion
                        + " 旧键在=" + V0Env.props.map.containsKey(OLD_KEY_FPS));

        // A-6b 故意不重置 props：它要观察 A-6 之后磁盘上还剩什么
        NeovideCursorSettings a6b = new NeovideCursorSettings();
        a6b.noStateLoaded();
        check("A-6b 紧接着再启动一次：用户存过的 false 必须还能读回来"
                        + "（不修的话 A-6 已把键删掉，这里会落回默认 true = 永久丢值）",
                !a6b.getState().alwaysHighFps,
                "alwaysHighFps=" + a6b.getState().alwaysHighFps);

        // ---- A-7：正常迁移路径不变 ----
        freshEnv();
        V0Env.props.map.put(OLD_KEY_FPS, "false");
        V0Env.props.map.put(OLD_KEY_FADE, "false");
        NeovideCursorSettings a7 = new NeovideCursorSettings();
        a7.noStateLoaded();
        check("A-7 正常迁移路径一字不变：版本 1、值取自旧键、旧键删掉",
                a7.getState().stateVersion == 1
                        && !a7.getState().alwaysHighFps
                        && !V0Env.props.map.containsKey(OLD_KEY_FPS),
                "version=" + a7.getState().stateVersion
                        + " fps=" + a7.getState().alwaysHighFps
                        + " 旧键在=" + V0Env.props.map.containsKey(OLD_KEY_FPS));
    }

    // ==================== L-5：tryGetPropertiesComponent 吞异常 ====================

    private static void l5() {
        System.out.println();
        System.out.println("--- L-5 取 PropertiesComponent 失败必须留痕 ---");

        freshEnv();
        V0Env.throwOnPropertiesLookup = 1;
        int m8 = mark();
        NeovideCursorSettings a8 = new NeovideCursorSettings();
        a8.noStateLoaded();
        check("A-8 取 PropertiesComponent 抛异常：留痕，且仍按「服务不可用」继续（版本 1）",
                anyWarnSince(m8, "PropertiesComponent") && a8.getState().stateVersion == 1,
                "warn=" + brief(m8) + " version=" + a8.getState().stateVersion);
        V0Env.throwOnPropertiesLookup = 0;
    }

    // ==================== L-4：NeovideColors 静默返回黑色 ====================

    private static void l4() {
        System.out.println();
        System.out.println("--- L-4 非法颜色串必须留痕（且有界）---");

        // A-10 先跑：它要求「合法色一条日志都不产生」，不能被 A-9 的已报集合干扰
        int m10 = mark();
        Color legal = NeovideColors.parse("#FFC0CB", 1.0f);
        check("A-10 合法颜色不产生任何 WARN（happy path 不许被日志污染）",
                legal != null && warnsSince(m10) == 0, "新增 warn=" + warnsSince(m10));

        int m9 = mark();
        Color black = NeovideColors.parse("#GGGGGG", 1.0f);
        boolean warned = anyWarnSince(m9, "#GGGGGG");
        int afterFirst = warnsSince(m9);
        NeovideColors.parse("#GGGGGG", 1.0f);
        int afterSecond = warnsSince(m9);
        check("A-9 非法颜色：仍返回黑色（行为不变）+ 首次失败留痕 + 同一串只报一次",
                Color.BLACK.equals(black) && warned && afterSecond == afterFirst,
                "warned=" + warned + " 第一次=" + afterFirst + " 第二次=" + afterSecond);

        int m11 = mark();
        for (int i = 0; i < 40; i++) {
            NeovideColors.parse("#Z" + String.format("%05d", i), 1.0f);
        }
        int n11 = warnsSince(m11);
        check("A-11 非法串记录必须有界：至少报 1 条，最多 16 条逐条 + 1 条上限汇总",
                n11 >= 1 && n11 <= 17 && anyWarnSince(m11, "已达记录上限"),
                "新增=" + n11);
    }

    // ==================== M-4：NaN 进弹簧后永不自愈 ====================

    private static void m4() {
        System.out.println();
        System.out.println("--- M-4 非有限值必须被挡住，且能自愈 ---");

        DampedSpring s12 = new DampedSpring(0.1);
        s12.setPosition(Double.NaN);
        boolean moved12 = s12.update(0.008);
        check("A-12 update：position 是 NaN 时归零并报「已静止」（否则该角点永久 NaN）",
                !moved12 && s12.getPosition() == 0.0 && !Double.isNaN(s12.getPosition()),
                "moving=" + moved12 + " pos=" + s12.getPosition());

        DampedSpring s13 = new DampedSpring(0.1);
        s13.setVelocityForProbe(Double.NaN);
        s13.setPosition(5.0);
        boolean moved13 = s13.update(0.008);
        check("A-13 update：velocity 是 NaN 时同样归零（只守 position 会被这一条抓住）",
                !moved13 && s13.getPosition() == 0.0,
                "moving=" + moved13 + " pos=" + s13.getPosition());

        DampedSpring s14 = new DampedSpring(Double.NaN);
        s14.setPosition(5.0);
        boolean moved14 = s14.update(0.008);
        boolean zeroed14 = s14.getPosition() == 0.0 && s14.getVelocityForProbe() == 0.0;
        s14.setAnimationLength(0.1);
        s14.setPosition(5.0);
        boolean recovered14 = s14.update(0.008);
        check("A-14 update：animationLength 非有限时归零；换回有限值后能正常推进（自愈）",
                !moved14 && zeroed14 && recovered14 && Double.isFinite(s14.getPosition()),
                "moving=" + moved14 + " zeroed=" + zeroed14
                        + " recovered=" + recovered14 + " pos=" + s14.getPosition());

        check("A-15 strengthFor：非有限偏移按「静止」处理（0），有限值仍按原曲线",
                GlowIntensity.strengthFor(Double.NaN) == 0f
                        && GlowIntensity.strengthFor(Double.POSITIVE_INFINITY) == 0f
                        && GlowIntensity.strengthFor(2.5) == 1f
                        && GlowIntensity.strengthFor(0.0) == 0f,
                "NaN=" + GlowIntensity.strengthFor(Double.NaN)
                        + " +Inf=" + GlowIntensity.strengthFor(Double.POSITIVE_INFINITY)
                        + " 2.5=" + GlowIntensity.strengthFor(2.5)
                        + " 0=" + GlowIntensity.strengthFor(0.0));

        AfterimageTrail trail = new AfterimageTrail(new NeovideConfig());
        trail.setLifetime(0.22);
        trail.setLifetime(Double.NaN);
        check("A-16 setLifetime：NaN 保持原值（绝不让 NaN 进 alphaOf）",
                trail.getLifetime() == 0.22, "lifetime=" + trail.getLifetime());

        trail.setLifetime(1e10);
        double capped = trail.getLifetime();
        trail.setLifetime(0.0);
        check("A-17 setLifetime：有上界（1e10 会让 cutoff 的 long 运算溢出），下界仍是 0.02",
                capped == 10.0 && trail.getLifetime() == 0.02,
                "上界后=" + capped + " 下界后=" + trail.getLifetime());
    }

    // ==================== M-10：CustomPresets.parse 静默丢弃 ====================

    private static void m10() {
        System.out.println();
        System.out.println("--- M-10 坏条目 / 重名条目被丢弃时必须留痕 ---");

        // ---- A-18：坏条目 + 好条目 ----
        int m18 = mark();
        String bad = repeat('y', 300);                     // 没有名称分隔符 → 坏条目
        String all18 = bad + CustomPresets.ENTRY_SEP
                + "好预设" + CustomPresets.NAME_SEP + "glowLayers=3";
        List<CustomPresets.Entry> e18 = CustomPresets.parse(all18);
        check("A-18 坏条目：仍然跳过（行为不变），但要记一条带长度、且绝不打印快照正文",
                e18.size() == 1 && anyWarnSince(m18, "300")
                        && !anyWarnSince(m18, repeat('y', 100)),
                "size=" + e18.size() + " warn=" + brief(m18));

        // ---- A-19：重名 ----
        int m19 = mark();
        String all19 = "甲" + CustomPresets.NAME_SEP + "glowLayers=1"
                + CustomPresets.ENTRY_SEP
                + "甲" + CustomPresets.NAME_SEP + "glowLayers=2";
        List<CustomPresets.Entry> e19 = CustomPresets.parse(all19);
        check("A-19 重名条目：只保留第一个（行为不变），但要记一条带名字的 warn",
                e19.size() == 1 && anyWarnSince(m19, "重名") && anyWarnSince(m19, "甲")
                        && "glowLayers=1".equals(e19.get(0).snapshot),
                "size=" + e19.size() + " warn=" + brief(m19));

        // ---- A-20：逐条上限 + 汇总 ----
        int m20 = mark();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                sb.append(CustomPresets.ENTRY_SEP);
            }
            // 名字各不相同且快照为空 → parseOne 返回 null（坏条目），
            // 但 bestEffortName 能取到名字，所以「同一组只报一次」不会把它们合并成一条。
            sb.append("坏").append(i).append(CustomPresets.NAME_SEP);
        }
        List<CustomPresets.Entry> e20 = CustomPresets.parse(sb.toString());
        int perItem = 0;
        int summary = 0;
        for (int i = m20; i < WARNS.size(); i++) {
            String w = WARNS.get(i);
            if (w.contains("跳过一条坏的自定义预设")) {
                perItem++;
            }
            if (w.contains("共跳过") && w.contains("50")) {
                summary++;
            }
        }
        check("A-20 50 条坏条目：逐条日志有上限（≤5），且必须有一条「共跳过 50 条」的汇总",
                e20.isEmpty() && perItem <= 5 && perItem >= 1 && summary == 1,
                "有效=" + e20.size() + " 逐条=" + perItem + " 汇总=" + summary);

        // ---- A-20b：同一条坏数据反复解析，汇总不许反复刷 ----
        //
        // parse 在读路径上被菜单/面板高频调用（AnimPresetGroup.update / canAdd）。
        // 磁盘上只要留着一条坏数据，「每次解析都打一条汇总」就是日志洪水 ——
        // 而这正是本批给逐条日志设上限的理由，汇总不能例外。
        int m20b = mark();
        CustomPresets.parse(sb.toString());
        int summaryAgain = 0;
        for (int i = m20b; i < WARNS.size(); i++) {
            if (WARNS.get(i).contains("共跳过")) {
                summaryAgain++;
            }
        }
        StringBuilder m20bDetail = new StringBuilder();
        for (int i = m20b; i < WARNS.size(); i++) {
            if (m20bDetail.length() > 0) {
                m20bDetail.append(" | ");
            }
            String w = WARNS.get(i);
            m20bDetail.append(w.length() > 60 ? w.substring(0, 60) + "…" : w);
        }
        check("A-20b 同样的坏数据再解析一次：汇总必须去重（否则菜单每弹一次刷一行）",
                summaryAgain == 0 && warnsSince(m20b) == 0,
                "新增汇总=" + summaryAgain + " 新增 warn 总数=" + warnsSince(m20b)
                        + " 明细=" + m20bDetail);

        // ---- A-21：parse 收下、format 抹掉（不可逆链路）----
        int m21 = mark();
        String illegalName = "A\u0007B";                   // U+0007 是 XML 非法码点，但不是分隔符
        List<CustomPresets.Entry> e21 = CustomPresets.parse(
                illegalName + CustomPresets.NAME_SEP + "glowLayers=3");
        String formatted = CustomPresets.format(e21);
        check("A-21 format 因名字非法丢条时必须留痕（这一丢下次写盘就永久消失）",
                e21.size() == 1 && "".equals(formatted)
                        && anyWarnSince(m21, "丢弃 1"),
                "parse=" + e21.size() + " format=[" + formatted + "] warn=" + brief(m21));
    }

    // ==================== L-6：CONFIRMED 的语义（纯注释）====================

    private static void l6() {
        System.out.println();
        System.out.println("--- L-6 CONFIRMED 的语义必须写在代码里（纯注释项只能这样守）---");

        Path root = locateRepoRoot();
        if (root == null) {
            check("A-22 必须能定位仓库根（否则无法核对注释）", false,
                    "未找到 CLAUDE.md；请用 -Dneovide.repo=<仓库根> 指定");
            return;
        }
        Path src = root.resolve(
                "src/main/java/com/github/yyyolo7a79/neovidecursor/settings/NeovideCursorSettings.java");
        if (!Files.isRegularFile(src)) {
            check("A-22 能读到 NeovideCursorSettings.java", false, src.toString());
            return;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        } catch (Exception e) {
            check("A-22 能读到 NeovideCursorSettings.java", false, String.valueOf(e));
            return;
        }
        // 【这条断言的边界，别误读】L-6 是纯注释项，没有行为可断言，只能用源码文本守。
        // 因此它只能证明「这两个词还在这份文件里」：
        //   · 谁把这句注释换个同义说法 → 红，但那不是回归（属于「过期断言」）；
        //   · 谁改了实现却没改注释 → 仍然绿（这是它抓不到的）。
        // 它防的是「注释被整段删掉、约定悄悄消失」，不是「注释与代码一致」。
        check("A-22 persistQuietly 附近必须写明「只表示没抛异常、不代表磁盘已写入」",
                text.contains("未抛异常") && text.contains("不代表"),
                "未抛异常=" + text.contains("未抛异常") + " 不代表=" + text.contains("不代表"));
    }

    // ==================== L-8：jump 兜底分母零 ====================

    private static void l8() {
        System.out.println();
        System.out.println("--- L-8 jump 兜底分支：零尺寸不得做除法 ---");

        // ---- A-23：上一帧是短跳 → 零尺寸时必须保持短跳 ----
        //
        // 【必须先 update 一次，不能只连跳两次】previousDestX/Y 是在 update() 里写的，
        // jump() 只读不写。连跳两次时它仍是初值 -1e5，于是 deltaY 巨大 → sameLine 恒 false
        // → 永远走跨行分支，这条断言就退化成「测了个寂寞」。
        NeovideConfig cfg = new NeovideConfig();
        TrailCorner c23 = new TrailCorner(-0.5, -0.5, cfg);
        c23.jump(8, 20, 100, 100, 0);                      // 初始化弹簧
        c23.update(8, 20, 104, 100, 0.008, false);         // 把 previousDest 落到真实目标
        c23.setCharsMoved(1);
        c23.jump(8, 20, 104, 100, 0);                      // 同一行 + 1 字符 → 短跳
        double shortLen = c23.animationLengthForProbe();
        int m23 = mark();
        c23.setCharsMoved(-1);                             // 退回兜底分支
        c23.jump(0, 0, 150, 100, 0);                       // 零尺寸
        double afterZero = c23.animationLengthForProbe();
        check("A-23 零尺寸 + 上一帧是短跳：必须保持短跳时长并记一次 warn（否则退化成跨行）",
                afterZero == shortLen && anyWarnSince(m23, "零尺寸"),
                "短跳=" + shortLen + " 零尺寸后=" + afterZero + " warn=" + brief(m23));

        // ---- A-24：上一帧是跨行 → 零尺寸时必须保持跨行（方向正确性，不作红绿依据）----
        TrailCorner c24 = new TrailCorner(-0.5, -0.5, new NeovideConfig());
        c24.jump(8, 20, 100, 100, 0);
        c24.update(8, 20, 104, 100, 0.008, false);         // 同上：previousDest 只在这里更新
        c24.setCharsMoved(10);                             // 超过 typingMaxChars → 跨行
        c24.jump(8, 20, 104, 100, 0);
        double longLen = c24.animationLengthForProbe();
        c24.setCharsMoved(-1);
        c24.jump(0, 0, 150, 100, 0);
        check("A-24 零尺寸 + 上一帧是跨行：保持跨行（与修复前同结果，只守方向）",
                c24.animationLengthForProbe() == longLen,
                "跨行=" + longLen + " 零尺寸后=" + c24.animationLengthForProbe());
    }

    // ==================== 环境与工具 ====================

    /**
     * 只换服务实例，不重装 Application 代理。
     *
     * <p>为什么可以这样：{@code V0Env.service()} 每次调用都读<b>静态字段</b>
     * {@code svc} / {@code props}，所以换掉这两个引用就等于换了一整套环境，
     * 不必反复 {@code ApplicationManager.setApplication(...)}。
     */
    private static void freshEnv() {
        if (V0Env.svc == null) {
            V0Env.install();
            return;
        }
        V0Env.svc = new NeovideCursorSettings();
        V0Env.props = new V0Env.FakeProps();
    }

    private static int mark() {
        return WARNS.size();
    }

    private static int warnsSince(int m) {
        return Math.max(0, WARNS.size() - m);
    }

    private static boolean anyWarnSince(int m, String needle) {
        for (int i = Math.min(m, WARNS.size()); i < WARNS.size(); i++) {
            if (WARNS.get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String lastWarnSince(int m) {
        return WARNS.size() > m ? WARNS.get(WARNS.size() - 1) : null;
    }

    private static String brief(int m) {
        if (WARNS.size() <= m) {
            return "无";
        }
        String w = WARNS.get(WARNS.size() - 1);
        return w.length() > 90 ? w.substring(0, 90) + "…" : w;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

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
        System.out.printf("=== VAUDIT-BATCH1 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("VAUDIT-BATCH1 RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("VAUDIT-BATCH1 RESULT: ALL PASS");
        System.exit(0);
    }

    // ==================== 日志捕获 ====================

    private static final class CapturingFactory implements Logger.Factory {
        @Override
        public Logger getLoggerInstance(String category) {
            return new Capturing(category);
        }
    }

    /**
     * 把日志收进内存列表。
     *
     * <p>{@link Logger} 只有 5 个抽象方法，其余都有默认实现 —— 所以这个子类很短。
     * {@code warn(String)} / {@code info(String)} 这些单参版本在父类里是
     * {@code warn(msg, null)} 的转发，因此<b>不重写也不会漏</b>。
     */
    private static final class Capturing extends Logger {
        private final String category;

        Capturing(String category) {
            this.category = category;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(String message, Throwable t) {
            // 不收集：debug 不在本探针的断言范围内
        }

        @Override
        public void info(String message, Throwable t) {
            INFOS.add(String.valueOf(message));
        }

        @Override
        public void warn(String message, Throwable t) {
            WARNS.add(String.valueOf(message));
        }

        @Override
        public void error(String message, Throwable t, String... details) {
            WARNS.add(String.valueOf(message));
        }

        @Override
        public String toString() {
            return "Capturing(" + category + ")";
        }
    }

    private VAuditBatch1() {
    }
}
