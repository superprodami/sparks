package cn.local.kapitb;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import cn.local.kapijd.NativeAdapter;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class KapiHooks {
    private static final String BASE = "com.st.iad.common.accessibility.";

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> resolver = loader.loadClass(BASE + "r3");
        Method extend = resolver.getDeclaredMethod("V", ArrayList.class);
        Method enrich = resolver.getDeclaredMethod("F", loader.loadClass(BASE + "bean.NodeInfo"));
        Method visit = resolver.getDeclaredMethod("Y", String.class, String.class, int.class,
                AccessibilityNodeInfo.class, loader.loadClass("kotlinx.coroutines.CoroutineScope"), boolean.class,
                loader.loadClass(BASE + "c"), boolean.class, Long.class, boolean.class,
                loader.loadClass("kotlin.jvm.functions.Function1"));
        Method configure = loader.loadClass(BASE + "PaymentAccessibilityService")
                .getDeclaredMethod("applyAccessibilityConfig", ArrayList.class, String.class);
        Method submit = loader.loadClass(BASE + "AccountDataUploader").getDeclaredMethod("N",
                loader.loadClass(BASE + "bean.AccessibleAccountReq"), loader.loadClass(BASE + "bean.Location"));

        NativeAdapter adapter = new NativeAdapter(loader);
        module.hook(extend).intercept(chain -> {
            @SuppressWarnings("unchecked")
            List<Object> configs = (List<Object>) chain.getArg(0);
            adapter.extend(configs);
            return chain.proceed();
        });
        module.hook(enrich).intercept(chain -> adapter.enrich(chain.proceed()));
        module.hook(visit).intercept(chain -> {
            NativeAdapter.visit(chain.getThisObject(), (String) chain.getArg(0),
                    (AccessibilityNodeInfo) chain.getArg(3));
            return chain.proceed();
        });
        module.hook(configure).intercept(chain -> {
            Object result = chain.proceed();
            NativeAdapter.configureService((AccessibilityService) chain.getThisObject());
            return result;
        });
        module.hook(submit).intercept(chain -> adapter.submit(chain.getThisObject(), chain.getArg(0))
                ? null : chain.proceed());
        Log.i("KapiShopping", "Kapi order hooks installed");
    }
}
