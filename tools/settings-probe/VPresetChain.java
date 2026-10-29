import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;

import java.util.List;

/**
 * V-PRESET-CHAIN：自定义预设「父链」的纯逻辑守门探针。
 *
 * <p>2026-10-29 重构把预设从「平铺列表」改成「带父引用的树」。这套语义有九条容易写错，
 * 每一条都对应一个真实的用户可见后果：
 *
 * <ol>
 *   <li><b>派生必须记父</b> —— 不记的话「恢复默认值」又退回到猜，按钮会指错预设；</li>
 *   <li><b>自定义可以派生自定义</b>（用户要求「个人自定义也能派生副本」）；</li>
 *   <li><b>恢复目标 = 最近存在的祖先</b>，继承父的<b>当前</b>快照（用户拍板）；</li>
 *   <li><b>删除父要让子接管祖父</b> —— 用户原话「父子自定义删除了 子自定义不能恢复默认值
 *       只能恢复到祖父内置预设」；</li>
 *   <li><b>改名要同步所有子节点的引用</b> —— 漏了就是断链；</li>
 *   <li><b>旧两段式条目必须还能读</b>（用户要求「以后有用户用，他更新我们必须保住他的预设」）；</li>
 *   <li><b>旧数据没有父 → 没有默认值</b>，绝不能偷偷回退到某个内置预设；</li>
 *   <li><b>环检测</b> —— 手改数据造出的环不能让恢复路径死循环；</li>
 *   <li><b>往返序列化不改内容</b> —— 否则每次打开面板都会悄悄改写用户配置。</li>
 * </ol>
 */
public final class VPresetChain {
    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== V-PRESET-CHAIN：自定义预设父链 ===");

        // ---------- C-1 派生记父：内置 → b<下标> ----------
        String t = CustomPresets.put("", "基础 副本", CustomPresets.builtinParent(0), "a=1");
        CustomPresets.Entry e = CustomPresets.getEntry(t, "基础 副本");
        check("C-1 从内置预设派生，父引用 = b0",
                e != null && "b0".equals(e.parent), "parent=" + (e == null ? "<null>" : e.parent));

        // ---------- C-2 自定义派生自定义：父引用 = c<名字> ----------
        t = CustomPresets.put(t, "二级副本", CustomPresets.customParent("基础 副本"), "a=2");
        CustomPresets.Entry e2 = CustomPresets.getEntry(t, "二级副本");
        check("C-2 从自定义预设派生，父引用 = c基础 副本",
                e2 != null && "c基础 副本".equals(e2.parent),
                "parent=" + (e2 == null ? "<null>" : e2.parent));

        // ---------- C-3 恢复目标：自定义父 → 继承它的当前快照 ----------
        CustomPresets.Base b = CustomPresets.resolveBase(t, "二级副本");
        check("C-3 「二级副本」的恢复目标 = 自定义「基础 副本」（继承语义）",
                b.exists() && b.custom != null && "基础 副本".equals(b.custom.name),
                "base=" + (b.custom == null ? "<无>" : b.custom.name));

        // ---------- C-4 父被删除 → 子接管祖父 ----------
        String t2 = CustomPresets.remove(t, "基础 副本");
        CustomPresets.Base b2 = CustomPresets.resolveBase(t2, "二级副本");
        check("C-4 删除父后，「二级副本」恢复到祖父（内置 b0）",
                b2.exists() && b2.builtinIdx == 0,
                "builtinIdx=" + b2.builtinIdx
                        + " custom=" + (b2.custom == null ? "<无>" : b2.custom.name));

        // ---------- C-5 改名同步子引用 ----------
        String t3 = CustomPresets.put("", "A", CustomPresets.builtinParent(1), "x=1");
        t3 = CustomPresets.put(t3, "B", CustomPresets.customParent("A"), "x=2");
        t3 = CustomPresets.rename(t3, "A", "AA");
        CustomPresets.Entry eb = CustomPresets.getEntry(t3, "B");
        check("C-5 改名 A→AA 后，子预设 B 的父引用同步为 cAA",
                eb != null && "cAA".equals(eb.parent),
                "parent=" + (eb == null ? "<null>" : eb.parent));

        // ---------- C-6 旧两段式必须还能读 ----------
        String legacy = "老预设" + CustomPresets.NAME_SEP + "slopeTicks=1;trailSize=1.0";
        List<CustomPresets.Entry> parsed = CustomPresets.parse(legacy);
        check("C-6 旧两段式条目仍能解析（名称 + 快照，父为空）",
                parsed.size() == 1
                        && "老预设".equals(parsed.get(0).name)
                        && "slopeTicks=1;trailSize=1.0".equals(parsed.get(0).snapshot)
                        && parsed.get(0).parent.isEmpty(),
                "size=" + parsed.size() + " parent=\""
                        + (parsed.isEmpty() ? "" : parsed.get(0).parent) + "\"");

        // ---------- C-7 旧数据无父 → 没有恢复目标（按钮置灰，不许猜） ----------
        CustomPresets.Base b3 = CustomPresets.resolveBase(legacy, "老预设");
        check("C-7 旧数据没有父引用 → 恢复目标为空（绝不回退到某个内置预设）",
                !b3.exists(), "exists=" + b3.exists());

        // ---------- C-8 环检测：父指向自己 ----------
        //
        // 【为什么不用「A 的父是 B、B 的父是 A」】resolveBase 找到第一个**存在**的祖先
        // 就返回，根本不会再往上走 —— 那种结构下 A 的目标就是 B，属于正常父链，
        // 断言「无目标」反而是错的。真正能走进环的是「父指向自己」这种自引用。
        String selfLoop = CustomPresets.put("", "自环",
                CustomPresets.customParent("自环"), "v=1");
        CustomPresets.Base bl = CustomPresets.resolveBase(selfLoop, "自环");
        check("C-8 父引用指向自己时安全返回「无目标」（不死循环、不抛异常）",
                !bl.exists(), "exists=" + bl.exists());

        // ---------- C-9 往返序列化不改内容 ----------
        String round = CustomPresets.format(CustomPresets.parse(t));
        check("C-9 解析→序列化 往返后内容不变（含父引用）",
                t.equals(round), "before=" + brief(t) + " after=" + brief(round));

        // ---------- C-10 颜色快照往返 ----------
        String colorSnap = com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                .colorSnapshot("#7DD3FC", "#38BDF8");
        String[] back = com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                .parseColorSnapshot(colorSnap);
        check("C-10 颜色快照生成→解析 往返一致（tail / shadow 两个 HEX）",
                back != null && "#7DD3FC".equals(back[0]) && "#38BDF8".equals(back[1]),
                "snap=" + colorSnap + " back=" + (back == null ? "<null>" : back[0] + "/" + back[1]));

        // ---------- C-11 坏颜色快照返回 null（绝不写半套颜色） ----------
        String[] bad1 = com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                .parseColorSnapshot("tailColor=#123456");
        String[] bad2 = com.github.yyyolo7a79.neovidecursor.settings.CursorPresets
                .parseColorSnapshot(null);
        check("C-11 缺项 / null 的颜色快照一律返回 null",
                bad1 == null && bad2 == null,
                "bad1=" + (bad1 == null ? "null" : "non-null")
                        + " bad2=" + (bad2 == null ? "null" : "non-null"));

        finish();
    }

    private static String brief(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 70 ? s : s.substring(0, 70) + "…(" + s.length() + ")";
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== V-PRESET-CHAIN 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-PRESET-CHAIN RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-PRESET-CHAIN RESULT: ALL PASS");
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
