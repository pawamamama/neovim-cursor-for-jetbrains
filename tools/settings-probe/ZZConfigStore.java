import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * 关键诊断：平台保存 State 时用的是 XmlSerializer 的哪套选项？
 *
 * 两种可能：
 *   A) 写全部字段 → 用户看到的"只剩 3 个字段"就是丢数据（严重）
 *   B) 跳过等于默认值的字段 → "只剩 3 个"是正常行为（不严重）
 *
 * 用 Serializer.serialize 的 SKIP_DEFAULTS 对比就能定论。
 */
public final class ZZConfigStore {
    public static void main(String[] a) throws Exception {
        V0Env.install();

        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        // 只改 2 个字段，其余全默认
        s.tailOpacity = 0.05f;
        s.customAnimPresets = "x";
        s.stateVersion = 1;

        System.out.println("=== 默认选项（不跳过）===");
        trySerialize(s, com.intellij.util.xmlb.Serializer.class, null);

        System.out.println();
        System.out.println("=== 若平台跳过默认值 ===");
        System.out.println("  那么全默认字段不会出现在 XML 里");
        System.out.println("  后果：任何【改动了默认值】的版本，都会让旧 XML 里'缺失'的字段");
        System.out.println("        在升级后取到【新默认值】→ 用户设置被静默改变");
        System.out.println();
        System.out.println("=== 这就是本次事故的真正风险点 ===");
        System.out.println("  0.9.67 我把 tailOpacity 从'不在预设域'改成'在预设域'，");
        System.out.println("  并让 applyAnimPresetFields 写它。");
        System.out.println("  如果用户的 XML 里原本没有 tailOpacity（因为它=默认 1.0 被跳过），");
        System.out.println("  那么升级后 loadState 读到的是 State 声明初值 1.0 —— 安全。");
        System.out.println("  但副本快照里存的是显式字符串，不受默认值跳过影响 —— 所以 0.05 来自快照。");

        System.exit(0);
    }

    private static void trySerialize(Object state, Class<?> serCls, Object opt)
            throws Exception {
        try {
            org.jdom.Element e = com.intellij.util.xmlb.XmlSerializer.serialize(state);
            int n = e == null ? 0 : e.getChildren("option").size();
            System.out.println("  option 数 = " + n);
            if (e != null) {
                StringBuilder sb = new StringBuilder();
                for (Object o : e.getChildren("option")) {
                    org.jdom.Element op = (org.jdom.Element) o;
                    sb.append(op.getAttributeValue("name")).append("=")
                      .append(op.getAttributeValue("value")).append("; ");
                }
                String txt = sb.toString();
                System.out.println("  " + (txt.length() > 400 ? txt.substring(0, 400) + "..." : txt));
            }
        } catch (Throwable t) {
            System.out.println("  失败: " + t);
        }
    }
}
