import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;

/**
 * DefaultsDump —— 把 {@link NeovideConfig} 的【全部】public 字段值打印成稳定金表。
 *
 * <h2>为什么需要它（第三轮审查 gate-auditor 的 Q3b 指出的漏洞）</h2>
 *
 * <p>盘点现有两道门禁的覆盖范围：
 * <ul>
 *   <li>{@code TrailParityTest} 只快照 11 个字段；</li>
 *   <li>{@code run-audit} 的 A 段 19 项里含 {@code NeovideConfig} 的 14 项；</li>
 *   <li>而 {@code NeovideConfig} 共 <b>23</b> 个 public 字段。</li>
 * </ul>
 *
 * <p>也就是说有 <b>12 个观感字段完全不在任何关卡视野里</b>：
 * {@code tailColor / shadowColor / tailOpacity / shadowBlurFactor / useShadow /
 * overlayFadeOut / cursorDisappearDelay / cursorFadeOutDuration / trailMode /
 * afterimageLifetime / alwaysHighFps / hideNativeCaret}。
 * 改了它们，门禁全绿 —— 这就是漏洞。
 *
 * <h2>为什么用「反射 + raw bits」而不是「文本正则」</h2>
 *
 * <p>上一轮我试过用 diff 正则判断"默认值有没有被改"，被审查员构造 13 个反例全部绕过：
 * {@code 0.11f→0.12f}、{@code →(0.12f)}、{@code →1.1e-1f}、把新值移到新增行……
 * <b>文本差异无法表达语义</b>。
 *
 * <p>本 harness 读的是<b>运行期的值</b>：
 * <ul>
 *   <li>{@code 0.11f} 与 {@code 0.1100f} 与 {@code 1.1e-1f} → 同样输出，零误报</li>
 *   <li>任何真改 → 输出变化</li>
 *   <li>新增字段 → 自动出现在输出里，判据永不失效</li>
 *   <li>字符串 / 枚举 / 布尔天然覆盖</li>
 * </ul>
 *
 * <p>Float 用 {@code floatToRawIntBits}：区分 {@code +0.0} 与 {@code -0.0}，
 * 也与平台无关（十进制格式化会随 Locale 变）。
 */
public final class DefaultsDump {

    public static void main(String[] args) throws Exception {
        System.out.println("# NeovideConfig 默认值金表（raw bits，与平台/Locale 无关）");
        System.out.println("# 格式：<声明类型> <字段名> = <稳定编码>");

        java.lang.reflect.Field[] fields = NeovideConfig.class.getDeclaredFields();
        java.util.Arrays.sort(fields, java.util.Comparator.comparing(java.lang.reflect.Field::getName));

        NeovideConfig cfg = new NeovideConfig();
        int n = 0;
        for (java.lang.reflect.Field f : fields) {
            if (!java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                continue;                       // 只看对外可见的配置项
            }
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;                       // 静态常量不属"默认值"范畴
            }
            Class<?> t = f.getType();
            Object v = f.get(cfg);
            String encoded;
            if (t == float.class) {
                encoded = String.format("f:0x%08X", Float.floatToRawIntBits((Float) v));
            } else if (t == int.class) {
                encoded = "i:" + v;
            } else if (t == boolean.class) {
                encoded = "b:" + v;
            } else if (t == String.class) {
                encoded = "s:" + (v == null ? "<null>" : v);
            } else if (t.isEnum()) {
                encoded = "e:" + ((Enum<?>) v).name();
            } else if (t == float[].class) {
                // 【必须特判数组】否则输出的是对象哈希（[F@1540e19d），每次运行都不一样，
                // 金表永远不会稳定。而 trailFactors 恰好是最关键的观感参数之一。
                float[] arr = (float[]) v;
                StringBuilder sb = new StringBuilder("fa:[");
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(String.format("0x%08X", Float.floatToRawIntBits(arr[i])));
                }
                encoded = sb.append(']').toString();
            } else if (t == int[].class) {
                encoded = "ia:" + java.util.Arrays.toString((int[]) v);
            } else {
                encoded = "?:<" + String.valueOf(v) + ">";
            }
            System.out.printf("%-12s %-28s = %s%n", t.getSimpleName(), f.getName(), encoded);
            n++;
        }
        System.out.println("# 字段总数 = " + n);
    }
}
