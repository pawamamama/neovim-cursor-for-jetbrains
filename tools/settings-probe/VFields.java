import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/** verifier 自建：State 的逐字段「逐位」快照（float 用 floatToRawIntBits，不看 EPS）。 */
public final class VFields {

    private VFields() {
    }

    public static Map<String, String> snapshot(NeovideCursorSettings.State s) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : NeovideCursorSettings.State.class.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers()) || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                Object v = f.get(s);
                out.put(f.getName(), encode(v));
            } catch (Throwable t) {
                out.put(f.getName(), "<ERR " + t + ">");
            }
        }
        return out;
    }

    public static String encode(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Float) {
            return "float:0x" + Integer.toHexString(Float.floatToRawIntBits((Float) v))
                    + "(" + v + ")";
        }
        if (v instanceof Double) {
            return "double:0x" + Long.toHexString(Double.doubleToRawLongBits((Double) v));
        }
        return v.getClass().getSimpleName() + ":" + v;
    }

    /** 只比较给定前缀集合之外的字段（用于正交性：忽略「本次应当被改的」那些）。 */
    public static void diff(String title, Map<String, String> before, Map<String, String> after,
                            java.util.Set<String> ignore, StringBuilder out) {
        out.append("---- ").append(title).append(" ----\n");
        for (String k : before.keySet()) {
            if (ignore.contains(k)) {
                continue;
            }
            String b = before.get(k);
            String a = after.get(k);
            out.append("  ").append(String.format("%-24s", k))
               .append(b.equals(a) ? "  SAME  " : "  DIFF  ")
               .append(b);
            if (!b.equals(a)) {
                out.append("   ->   ").append(a);
            }
            out.append('\n');
        }
    }

    public static int countDiff(Map<String, String> before, Map<String, String> after,
                                java.util.Set<String> ignore) {
        int n = 0;
        for (String k : before.keySet()) {
            if (ignore.contains(k)) {
                continue;
            }
            if (!before.get(k).equals(after.get(k))) {
                n++;
            }
        }
        return n;
    }
}
