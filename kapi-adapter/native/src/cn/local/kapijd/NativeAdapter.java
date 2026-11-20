package cn.local.kapijd;

import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.WeakHashMap;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

public final class NativeAdapter {
    private static final String JD = "com.jingdong.app.mall";
    private static final String TB = "com.taobao.taobao";
    private static final String MODEL = "com.st.iad.common.accessibility.bean.";
    private static final HashSet<String> inFlight = new HashSet<>();

    private static final WeakHashMap<Object, Boolean> detailVisits = new WeakHashMap<>();
    private final ClassLoader loader;

    public NativeAdapter(ClassLoader loader) {
        this.loader = loader;
    }

    public static void visit(Object resolver, String packageName, AccessibilityNodeInfo root) throws Exception {
        if (!TB.equals(packageName)) return;
        boolean detail = hasDetail(root);
        Boolean previous = detailVisits.put(resolver, detail);
        if (previous == null || previous.booleanValue() != detail)
            Log.i("KapiJD", "TB detail visit=" + detail);
        if (Boolean.FALSE.equals(previous) && detail) {
            resolver.getClass().getMethod("z").invoke(resolver);
            Log.i("KapiJD", "TB detail reopened; native submission retry enabled");
        }
    }

    private static boolean hasDetail(AccessibilityNodeInfo node) {
        if (node == null) return false; // A child may disappear during a renderer update.
        CharSequence text = node.getText(), description = node.getContentDescription();
        if ((text != null && text.toString().contains("订单信息"))
                || (description != null && description.toString().contains("订单信息"))) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasDetail(node.getChild(i))) return true;
        }
        return false;
    }

    public boolean submit(final Object uploader, final Object request) throws Exception {
        Class<?> type = request.getClass();
        String packageName = (String) type.getMethod("getPackageName").invoke(request);
        if (!JD.equals(packageName) && !TB.equals(packageName)) return false;
        final String shop = TB.equals(packageName) ? "淘宝" : "京东";
        String prefix = TB.equals(packageName) ? "TB" : "JD";
        final String flowId = (String) type.getMethod("getFlowId").invoke(request);
        final String orderId = (String) type.getMethod("getTradeNo").invoke(request);
        String amount = (String) type.getMethod("getAmount").invoke(request);
        String time = (String) type.getMethod("getOccurredTime").invoke(request);
        final String product = product(request);
        final String flightKey = prefix + ":" + orderId;
        Class<?> app = loader.loadClass("com.st.iad.common.base.BaseApp");
        Object companion = app.getField("e").get(null);
        Context context = (Context) companion.getClass().getMethod("a").invoke(companion);
        final SharedPreferences receipts = context.getSharedPreferences(TB.equals(packageName) ? "kapi_tb_receipts_v1" : "kapi_jd_receipts_v1", Context.MODE_PRIVATE);
        if (receipts.contains(orderId) && !(Boolean) type.getMethod("getSkipDuplicateCheck").invoke(request)) {
            // Let the native accepted-flow UI finish opening before delivering a local result.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    publishError(uploader, request, flowId, "61004", "这笔" + shop + "订单已识别过，可能重复。需要重记可选“仍然要记”。");
                } catch (Exception error) {
                    throw new IllegalStateException("Cannot show duplicate reminder", error);
                }
            });
            Log.i("KapiJD", prefix + " duplicate reminder shown");
            return true;
        }
        synchronized (inFlight) {
            if (inFlight.contains(flightKey)) return true;
            inFlight.add(flightKey);
        }
        String query = shop + "购买商品：" + product + "，支出人民币" + amount + "元，支付时间" + time
                + "。备注仅填写商品名称：" + product + "。";
        Class<?> autoType = loader.loadClass(MODEL + "AutoRecordReq");
        Object auto = autoType.getConstructor().newInstance();
        autoType.getMethod("setQuery", String.class).invoke(auto, query);
        autoType.getMethod("setCheckValue", String.class).invoke(auto, flightKey);
        autoType.getMethod("setInputType", String.class).invoke(auto, "TEXT");
        autoType.getMethod("setSkipDuplicateCheck", boolean.class).invoke(auto,
                type.getMethod("getSkipDuplicateCheck").invoke(request));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        autoType.getMethod("setOccurredTimestamp", Long.class).invoke(auto, format.parse(time).getTime());
        Object repository = loader.loadClass("bh.d").getConstructor().newInstance();
        Object service = repository.getClass().getField("a").get(repository);
        Class<?> continuationType = loader.loadClass("kotlin.coroutines.Continuation");
        final Object emptyContext = loader.loadClass("kotlin.coroutines.e").getField("a").get(null);
        Object continuation = Proxy.newProxyInstance(loader, new Class<?>[]{continuationType}, (proxy, method, args) -> {
            if (method.getName().equals("getContext")) return emptyContext;
            if (method.getName().equals("resumeWith")) {
                complete(uploader, request, flowId, orderId, flightKey, shop, product, receipts, args[0]);
                return null;
            }
            if (method.getName().equals("toString")) return "KapiJDContinuation";
            throw new UnsupportedOperationException(method.getName());
        });
        Object immediate = loader.loadClass("com.st.iad.common.accessibility.api.IRecordService").getMethod("autoRecordV2", autoType, continuationType)
                .invoke(service, auto, continuation);
        Object suspended = loader.loadClass("km.d").getMethod("l").invoke(null);
        if (immediate != suspended) complete(uploader, request, flowId, orderId, flightKey, shop, product, receipts, immediate);
        Log.i("KapiJD", prefix + " structured text submitted through native V2 text recording API");
        return true;
    }

    private void complete(Object uploader, Object request, String flowId, String orderId, String flightKey, String shop, String product,
                                 SharedPreferences receipts, Object response) throws Exception {
        synchronized (inFlight) { inFlight.remove(flightKey); }
        Throwable error = (Throwable) loader.loadClass("am.i0").getMethod("e", Object.class).invoke(null, response);
        if (error != null) {
            Log.e("KapiJD", "Native V2 text recording failed", error);
            publishError(uploader, request, flowId, "SHOP_TEXT_API", shop + "记账失败：" + error.getMessage());
            return;
        }
        if (!(Boolean) response.getClass().getMethod("isSuccess").invoke(response)) {
            String code = (String) response.getClass().getMethod("codeStr").invoke(response);
            String message = (String) response.getClass().getMethod("errorMessage").invoke(response);
            Log.e("KapiJD", "Native V2 text API failed: " + code + " " + message);
            publishError(uploader, request, flowId, code, message);
            return;
        }
        Object result = response.getClass().getMethod("getData").invoke(response);
        List<?> records = (List<?>) result.getClass().getMethod("getDataList").invoke(result);
        Log.i("KapiJD", "Native V2 text API returned records=" + records.size());
        if (records.size() != 1) {
            publishError(uploader, request, flowId, "SHOP_RESULT_COUNT", shop + "记账返回的账单数量不为一，请检查记录");
            return;
        }
        Object record = records.get(0);
        record.getClass().getMethod("setContent", String.class).invoke(record, product);
        record.getClass().getMethod("setTitle", String.class).invoke(record, product);
        String status = (String) record.getClass().getMethod("getTaskStatus").invoke(record);
        String id = (String) record.getClass().getMethod("getId").invoke(record);
        Log.i("KapiJD", "Native V2 text record status=" + status + ", id=" + id);
        if ("DONE".equals(status) || "FALLBACK".equals(status)) receipts.edit().putString(orderId, id).apply();
        publish(uploader, record, flowId);
    }

    private static void publish(Object uploader, Object result, String flow) throws Exception {
        uploader.getClass().getMethod("L", Object.class, String.class, Long.class).invoke(uploader, result, flow, null);
    }

    private void publishError(Object uploader, Object request, String flow, String code, String message) throws Exception {
        Object error = loader.loadClass(MODEL + "AccountError")
                .getConstructor(String.class, Object.class, String.class, String.class, boolean.class)
                .newInstance("accessible", request, code, message, false);
        publish(uploader, error, flow);
    }

    public static void configureService(AccessibilityService service) {
        AccessibilityServiceInfo info = service.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.eventTypes |= android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED;
        service.setServiceInfo(info);
        Log.i("KapiJD", "Service flags=" + info.flags + ", accessibilityTool=" + info.isAccessibilityTool());
    }

    public void extend(List<Object> configs) throws Exception {
        extendTaobao(configs);
        Class<?> config = loader.loadClass(MODEL + "AccessibilityConfig");
        for (Object item : configs) {
            if (JD.equals(config.getMethod("getPackageName").invoke(item))) return;
        }
        Constructor<?> match = loader.loadClass(MODEL + "MatchKey")
                .getConstructor(String.class, int.class, ArrayList.class);
        ArrayList<Object> fields = new ArrayList<>();
        fields.add(match.newInstance("支付时间", 5, null));
        for (String key : new String[]{"实付款", "订单编号", "支付方式"})
            fields.add(match.newInstance(key, 0, null));
        for (String key : new String[]{"等待付款", "待付款", "订单已取消", "订单取消",
                "交易关闭", "退款成功", "退款完成", "退款中", "售后处理中"})
            fields.add(match.newInstance(key, -100, null));
        ArrayList<Object> pages = new ArrayList<>();
        pages.add(loader.loadClass(MODEL + "PageInfo")
                .getConstructor(String.class, String.class, ArrayList.class, ArrayList.class, int.class)
                .newInstance("已支付订单详情✅", "com.jd.lib.ordercenter.taro.OrderDetailActivityTaro", null, fields, 5));
        configs.add(config.getConstructor(String.class, String.class, ArrayList.class, ArrayList.class,
                loader.loadClass(MODEL + "AccessibilitySceneUi"), ArrayList.class)
                .newInstance("京东", JD, pages, null, null, null));
        Log.i("KapiJD", "Native JD scene injected; configured apps=" + configs.size());
    }

    private void extendTaobao(List<Object> configs) throws Exception {
        Class<?> config = loader.loadClass(MODEL + "AccessibilityConfig");
        for (Object item : configs) {
            if ("com.taobao.taobao".equals(config.getMethod("getPackageName").invoke(item))) return;
        }
        Constructor<?> match = loader.loadClass(MODEL + "MatchKey")
                .getConstructor(String.class, int.class, ArrayList.class);
        ArrayList<Object> fields = new ArrayList<>();
        fields.add(match.newInstance("付款时间", 5, null));
        fields.add(match.newInstance("实付款", 0, null));
        fields.add(match.newInstance("订单信息", 0, null));
        ArrayList<Object> pages = new ArrayList<>();
        for (String activity : new String[]{"com.taobao.tao.welcome.Welcome", "com.taobao.tao.TBMainActivity",
                "com.taobao.android.order.bundle.TBOrderSearchOrFilterActivity"})
            pages.add(loader.loadClass(MODEL + "PageInfo")
                    .getConstructor(String.class, String.class, ArrayList.class, ArrayList.class, int.class)
                    .newInstance("已支付订单详情✅", activity, null, fields, 5));
        configs.add(config.getConstructor(String.class, String.class, ArrayList.class, ArrayList.class,
                loader.loadClass(MODEL + "AccessibilitySceneUi"), ArrayList.class)
                .newInstance("淘宝", "com.taobao.taobao", pages, null, null, null));
        Log.i("KapiJD", "Native Taobao scene injected; configured apps=" + configs.size());
    }

    public static Object enrich(Object request) throws Exception {
        if (request == null) return null;
        Class<?> type = request.getClass();
        if (TB.equals(type.getMethod("getPackageName").invoke(request))) {
            TaobaoOrder order = TaobaoOrder.parse((String) type.getMethod("getOriginQuery").invoke(request));
            if (order == null) {
                Log.i("KapiJD", "TB page skipped: paid detail fields incomplete or excluded status");
                return null;
            }
            set(request, "amount", order.amount);
            set(request, "tradeNo", order.id);
            set(request, "occurredTime", order.time);
            Log.i("KapiJD", "Native TB parsed: amount=" + order.amount + ", order suffix=" + order.id.substring(order.id.length() - 4));
            return request;
        }
        if (!JD.equals(type.getMethod("getPackageName").invoke(request))) return request;
        JdOrder order = JdOrder.parse((String) type.getMethod("getOriginQuery").invoke(request));
        if (order == null) {
            Log.i("KapiJD", "JD page skipped: paid order fields incomplete or excluded status");
            return null;
        }
        set(request, "amount", order.amount);
        set(request, "tradeNo", order.id);
        set(request, "occurredTime", order.time);
        Log.i("KapiJD", "Native JD parsed: amount=" + order.amount + ", order suffix=" + order.id.substring(order.id.length() - 4));
        return request;
    }

    private static String product(Object request) throws Exception {
        Class<?> type = request.getClass();
        String text = (String) type.getMethod("getOriginQuery").invoke(request);
        return TB.equals(type.getMethod("getPackageName").invoke(request)) ? TaobaoOrder.parse(text).product : JdOrder.parse(text).product;
    }

    private static void set(Object target, String name, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
