import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import org.jdom.Element;

/**
 * 诊断：平台的 State 序列化是否会「跳过等于默认值的字段」。
 *
 * 背景：用户那次事故里 XML 只剩 3 个字段。
 * 如果 XmlSerializer.serialize 对「等于默认值的字段」不输出，
 * 那 24 个字段"消失"就是正常行为 —— 不是丢数据。
 */
public final class ZZSerializeSkip {
    public static void main(String[] a) throws Exception {
        V0Env.install();

        // 场景 1：全默认值
        NeovideCursorSettings.State def = new NeovideCursorSettings.State();
        Element e1 = com.intellij.util.xmlb.XmlSerializer.serialize(def);
        System.out.println("=== 全默认值序列化 ===");
        System.out.println("  XML: " + (e1 == null ? "null" : e1.getText()));
        System.out.println("  顶层 option 数: " + (e1 == null ? 0 : e1.getChildren("option").size()));

        // 场景 2：改几个值
        NeovideCursorSettings.State modified = new NeovideCursorSettings.State();
        modified.tailOpacity = 0.5f;
        modified.cursorWidthPx = 5;
        modified.rgbGradient = true;
        Element e2 = com.intellij.util.xmlb.XmlSerializer.serialize(modified);
        System.out.println();
        System.out.println("=== 改 3 个字段后序列化 ===");
        System.out.println("  XML: " + (e2 == null ? "null" : e2.getText()));
        System.out.println("  顶层 option 数: " + (e2 == null ? 0 : e2.getChildren("option").size()));

        // 场景 3：用 configurationStore（平台真正用的那条）
        System.out.println();
        System.out.println("=== 结论 ===");
        int n1 = e1 == null ? 0 : e1.getChildren("option").size();
        int n2 = e2 == null ? 0 : e2.getChildren("option").size();
        if (n1 == 0) {
            System.out.println("  全默认值 → 0 个 option：**平台确实跳过等于默认值的字段**");
            System.out.println("  => 用户看到'只剩 3 个字段'是正常行为，不是丢数据");
            System.out.println("  => 但如果某字段的默认值被改了（如 tailOpacity 默认从 1.0 改成别的），");
            System.out.println("     旧 XML 里没写它 → 反序列化得到【新默认值】→ 用户设置被静默改变！");
        } else {
            System.out.println("  全默认值 → " + n1 + " 个 option：不跳过，每个字段都写");
        }
        System.out.println("  改 3 个 → " + n2 + " 个 option");
        System.exit(0);
    }
}
