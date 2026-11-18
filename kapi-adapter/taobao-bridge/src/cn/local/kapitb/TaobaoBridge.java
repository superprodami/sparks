package cn.local.kapitb;

import android.app.Application;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

public final class TaobaoBridge implements IXposedHookLoadPackage {
    private static boolean installed;
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

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.taobao.taobao".equals(param.packageName) || !param.packageName.equals(param.processName)) return;
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (installed) return;
                installed = true;
                ClassLoader loader = ((Application) hook.args[0]).getClassLoader();
                XposedHelpers.findAndHookMethod("io.unicorn.embedding.android.UnicornView", loader,
                        "getAccessibilityNodeProvider", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        View view = (View) hook.thisObject;
                        if (!isOrderDetail(view)) return;
                        Object bridge = XposedHelpers.getObjectField(view, "accessibilityBridge");
                        if (bridge == null) return; // Android also queries while the engine is detached.
                        XposedHelpers.callMethod(bridge, "x", true);
                    }
                });
                XposedHelpers.findAndHookMethod("io.unicorn.view.AccessibilityBridge", loader, "S",
                        java.nio.ByteBuffer.class, String[].class, java.nio.ByteBuffer[].class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        Integer previous = paidNodes.get(hook.thisObject);
                        if (previous == null) return;
                        Map<?, ?> nodes = (Map<?, ?>) XposedHelpers.getObjectField(hook.thisObject, "g");
                        Object paid = nodes.get(previous);
                        if (paid != null) XposedHelpers.setIntField(paid, "c", XposedHelpers.getIntField(paid, "c") | 8192);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        Object bridge = hook.thisObject;
                        if (!isOrderDetail((View) XposedHelpers.getObjectField(bridge, "a"))) return;
                        Integer previous = paidNodes.remove(bridge);
                        Map<?, ?> nodes = (Map<?, ?>) XposedHelpers.getObjectField(bridge, "g");
                        // The paid total ends the right-hand price column. Savings and coin
                        // deductions can follow it in the left-hand discount breakdown.
                        for (Object node : nodes.values()) {
                            String label = (String) XposedHelpers.getObjectField(node, "o");
                            if (label == null || !label.contains("实付款")) continue;
                            Rect groupBounds = (Rect) XposedHelpers.getObjectField(node, "X");
                            Object paid = null;
                            for (Object child : (List<?>) XposedHelpers.getObjectField(node, "O")) {
                                String text = (String) XposedHelpers.getObjectField(child, "o");
                                if (text != null && MONEY.matcher(text).matches()
                                        && ((Rect) XposedHelpers.getObjectField(child, "X")).centerX() > groupBounds.centerX()) paid = child;
                            }
                            if (paid != null) {
                                // IS_HIDDEN=8192 is verified in this installed Unicorn build.
                                XposedHelpers.setIntField(paid, "c", XposedHelpers.getIntField(paid, "c") & ~8192);
                                int id = XposedHelpers.getIntField(paid, "b");
                                paidNodes.put(bridge, id);
                                if (previous == null || previous.intValue() != id)
                                    Log.i("KapiTBBridge", "Paid node=" + id + ", value=" + XposedHelpers.getObjectField(paid, "o"));
                            }
                        }
                    }
                });
                XposedHelpers.findAndHookMethod("io.unicorn.view.AccessibilityBridge", loader,
                        "createAccessibilityNodeInfo", int.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam hook) {
                        Integer paidId = paidNodes.get(hook.thisObject);
                        if (paidId == null || paidId.intValue() != (Integer) hook.args[0]) return;
                        AccessibilityNodeInfo info = (AccessibilityNodeInfo) hook.getResult();
                        if (info != null) info.setContentDescription("淘宝实付款金额：" + info.getContentDescription().toString().replaceAll("\\s+", ""));
                    }
                });
                Log.i("KapiTBBridge", "Order-detail semantics and paid-total hooks installed");
            }
        });
    }
}
