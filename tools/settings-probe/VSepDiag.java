import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** VSepDiag —— 定位 U+000B 是在哪一层被丢掉的（XmlSerializer 元素层 / XMLOutputter 字符串层 / SAX 解析层）。 */
public final class VSepDiag {
    public static void main(String[] a) {
        System.out.println("=== VSepDiag：非法控制字符在哪一层被丢 ===");
        V0Env.install();
        String name = "A\u000BB";
        String table = CustomPresets.put("", name, "k=v");
        System.out.println("内存表（转义）= " + esc(table));

        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.customAnimPresets = table;
        s.animCustomName = name;

        org.jdom.Element el = com.intellij.util.xmlb.XmlSerializer.serialize(s);
        // 层 1：XmlSerializer 产出的 element 属性值里还有没有 U+000B
        String attr = el.getAttributeValue("customAnimPresets");
        System.out.println("层1 XmlSerializer 元素属性= " + esc(attr));
        System.out.println("层1 含 U+000B = " + (attr != null && attr.indexOf('\u000B') >= 0));

        // 层 2：元素 → 反序列化（不经过字符串）
        NeovideCursorSettings.State back1 = com.intellij.util.xmlb.XmlSerializer
                .deserialize(el, NeovideCursorSettings.State.class);
        System.out.println("层2 元素直接反序列化 = " + esc(back1.customAnimPresets));

        // 层 3：字符串化
        String xml = new org.jdom.output.XMLOutputter().outputString(el);
        System.out.println("层3 XML 文本含 U+000B = " + (xml.indexOf('\u000B') >= 0)
                + " 含 &#xB; = " + xml.contains("&#xB;") + " 含 &#11; = " + xml.contains("&#11;"));
        int i = xml.indexOf("customAnimPresets");
        System.out.println("层3 片段 = " + esc(xml.substring(i, Math.min(xml.length(), i + 40))));

        // 层 4：字符串 → SAX → 反序列化
        try {
            org.jdom.Element re = new org.jdom.input.SAXBuilder()
                    .build(new java.io.StringReader(xml)).getRootElement();
            NeovideCursorSettings.State back2 = com.intellij.util.xmlb.XmlSerializer
                    .deserialize(re, NeovideCursorSettings.State.class);
            System.out.println("层4 字符串往返 = " + esc(back2.customAnimPresets));
        } catch (Throwable t) {
            System.out.println("层4 抛异常 = " + t);
        }
        System.out.flush();
        System.exit(0);
    }

    private static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x241F || c == 0x241E || c >= 0xFFFE) sb.append(String.format("\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
