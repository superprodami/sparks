package cn.local.kapitb;

import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

final class TaobaoBridge {
    private static final String TAG = "KapiTBBridge";
    private static final int IS_HIDDEN = 8192;
    private static final Pattern MONEY = Pattern.compile("[￥¥]\\s*[0-9]+(?:\\.[0-9]{1,2})?");
    private static final Map<Object, Integer> paidNodes = new WeakHashMap<>();

    private static boolean isOrderDetail(View view) {
        int containerId = view.getResources().getIdentifier("orderListWeex2FragmentContainer", "id", "com.taobao.taobao");
        View ancestor = view;
        while (ancestor.getId() != containerId) {
            ViewParent parent = ancestor.getParent();
            if (!(parent instanceof View)) return false;
            ancestor = (View) parent;
        }
        return true;
    }

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> unicornView = loader.loadClass("io.unicorn.embedding.android.UnicornView");
        Class<?> bridgeType = loader.loadClass("io.unicorn.view.AccessibilityBridge");
        Class<?> nodeType = loader.loadClass("io.unicorn.view.AccessibilityBridge$k");
        Method provider = unicornView.getDeclaredMethod("getAccessibilityNodeProvider");
        Method update = bridgeType.getDeclaredMethod("S", ByteBuffer.class, String[].class, ByteBuffer[].class);
        Method nodeInfo = bridgeType.getDeclaredMethod("createAccessibilityNodeInfo", int.class);
        Method enableSemantics = bridgeType.getDeclaredMethod("x", boolean.class);
        enableSemantics.setAccessible(true);
        Field viewBridge = field(unicornView, "accessibilityBridge");
        Field bridgeView = field(bridgeType, "a");
        Field bridgeNodes = field(bridgeType, "g");
        Field nodeId = field(nodeType, "b");
        Field nodeFlags = field(nodeType, "c");
        Field nodeLabel = field(nodeType, "o");
        Field nodeChildren = field(nodeType, "O");
        Field nodeBounds = field(nodeType, "X");

        module.hook(provider).intercept(chain -> {
            View view = (View) chain.getThisObject();
            if (isOrderDetail(view)) {
                Object bridge = viewBridge.get(view);
                // Android also queries while the engine is detached.
                if (bridge != null) enableSemantics.invoke(bridge, true);
            }
            return chain.proceed();
        });
        module.hook(update).intercept(chain -> {
            Object bridge = chain.getThisObject();
            Integer previous = paidNodes.get(bridge);
            if (previous != null) {
                Object paid = ((Map<?, ?>) bridgeNodes.get(bridge)).get(previous);
                if (paid != null) nodeFlags.setInt(paid, nodeFlags.getInt(paid) | IS_HIDDEN);
            }
            Object result = chain.proceed();
            if (!isOrderDetail((View) bridgeView.get(bridge))) return result;
            paidNodes.remove(bridge);
            Map<?, ?> nodes = (Map<?, ?>) bridgeNodes.get(bridge);
            // The paid total ends the right-hand price column. Discounts may
            // follow it in the left-hand column.
            for (Object node : nodes.values()) {
                String label = (String) nodeLabel.get(node);
                if (label == null || !label.contains("实付款")) continue;
                Rect groupBounds = (Rect) nodeBounds.get(node);
                Object paid = null;
                for (Object child : (List<?>) nodeChildren.get(node)) {
                    String value = (String) nodeLabel.get(child);
                    if (value != null && MONEY.matcher(value).matches()
                            && ((Rect) nodeBounds.get(child)).centerX() > groupBounds.centerX()) paid = child;
                }
                if (paid != null) {
                    nodeFlags.setInt(paid, nodeFlags.getInt(paid) & ~IS_HIDDEN);
                    int id = nodeId.getInt(paid);
                    paidNodes.put(bridge, id);
                    if (previous == null || previous.intValue() != id) Log.i(TAG, "Paid total node=" + id);
                }
            }
            return result;
        });
        module.hook(nodeInfo).intercept(chain -> {
            AccessibilityNodeInfo info = (AccessibilityNodeInfo) chain.proceed();
            Integer paidId = paidNodes.get(chain.getThisObject());
            if (info != null && paidId != null && paidId.intValue() == (Integer) chain.getArg(0)) {
                info.setContentDescription("淘宝实付款金额：" + info.getContentDescription().toString().replaceAll("\\s+", ""));
            }
            return info;
        });
        Log.i(TAG, "Order-detail semantics and paid-total hooks installed");
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
