package cn.local.kapitb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Log.i("KapiShopping", RootBootstrap.apply(context, false));
            } catch (Exception error) {
                RootBootstrap.status(context, "开机加载失败：" + error.getMessage());
                Log.e("KapiShopping", "Boot load failed", error);
            } finally {
                pending.finish();
            }
        }, "kapi-shopping-boot").start();
    }
}
