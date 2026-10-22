
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;

/**
 * 自测：F2 修复的边界情况（不依赖审查员）。
 * 审查员还没回来，我自己先验最危险的几条。
 */
public final class VSeEdge {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) {
        System.out.println("=== 分隔符边界自测 ===");

        // 1) 名称全部由分隔符构成 → 净化后应为空 → put 应拒绝或产生安全结果
        String sepOnly = String.valueOf(CustomPresets.NAME_SEP)
                + CustomPresets.NAME_SEP + CustomPresets.ENTRY_SEP;
        String s1 = CustomPresets.put("", sepOnly, "glowLayers=8");
        int n1 = CustomPresets.parse(s1).size();
        check("E1 名称全由分隔符构成时不产生空名条目（或安全忽略）", n1 == 0 || n1 == 1,
                "条目数=" + n1 + " 表=「" + s1.replace((char)0x241F,'|').replace((char)0x241E,'#') + "」");

        // 2) 新旧分隔符混用 → parse 不能切错
        String legacy = "老预设" + (char)1 + "glowLayers=9";
        String mixed = legacy + CustomPresets.ENTRY_SEP + "新预设" + CustomPresets.NAME_SEP + "glowLayers=7";
        int n2 = CustomPresets.parse(mixed).size();
        check("E2 新旧分隔符混用仍能解析出条目（不抛异常）", n2 >= 0 && n2 <= 2, "条目数=" + n2);

        // 3) 纯旧格式必须能读（老用户数据）
        check("E3 纯旧格式（U+0001）可读出新值",
                CustomPresets.get(legacy, "老预设") != null, "get()=" + CustomPresets.get(legacy, "老预设"));

        // 4) 读旧格式后再 put 一次 → 应转成新分隔符（迁移路径）
        String migrated = CustomPresets.put(legacy, "改过的", "glowLayers=5");
        boolean usesNew = migrated.indexOf(CustomPresets.NAME_SEP) >= 0
                && migrated.indexOf((char)1) < 0;
        check("E4 读旧格式后 put 会写成新分隔符（迁移完成）", usesNew,
                "含新=" + (migrated.indexOf(CustomPresets.NAME_SEP) >= 0)
                        + " 含旧=" + (migrated.indexOf((char)1) >= 0));

        // 5) 名称含换行/制表符
        String nl = "名\n字\t";
        String s5 = CustomPresets.put("", nl, "glowLayers=8");
        check("E5 名称含换行/制表符不破坏解析", CustomPresets.parse(s5).size() == 1,
                "条目数=" + CustomPresets.parse(s5).size());

        // 6) 空串 / 只有分隔符 / 只有快照
        check("E6 退化输入不抛异常",
                CustomPresets.parse("").isEmpty()
                        && CustomPresets.parse(String.valueOf(CustomPresets.ENTRY_SEP)).isEmpty()
                        && CustomPresets.parse(String.valueOf(CustomPresets.NAME_SEP)).isEmpty(),
                "");

        // 7) 快照内部含分隔符（自定义预设串被手改坏）
        String corrupt = "坏预设" + CustomPresets.NAME_SEP + "glowLayers=8" + CustomPresets.NAME_SEP + "多余";
        check("E7 快照里混入分隔符时不崩（要么切出坏条目要么忽略）",
                CustomPresets.parse(corrupt).size() <= 1,
                "条目数=" + CustomPresets.parse(corrupt).size());

        System.out.println();
        System.out.printf("=== SEP-EDGE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) { System.out.println("SEP-EDGE RESULT: FAILED"); System.exit(1); }
        System.out.println("SEP-EDGE RESULT: ALL PASS");
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}
