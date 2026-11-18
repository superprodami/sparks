package cn.local.kapitb;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ControlActivity extends Activity {
    private TextView status;
    private Button enable, stop;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int space = (int) (24 * getResources().getDisplayMetrics().density);
        page.setPadding(space, space * 2, space, space);
        TextView title = new TextView(this);
        title.setText("咔皮京东淘宝适配");
        title.setTextSize(25);
        page.addView(title);
        TextView help = new TextView(this);
        help.setText("\n打开已支付订单详情，显示商品、付款时间和订单号后，咔皮会弹出记账预览。备注只保留商品名称。\n\n此模块需要 Root 来加载咔皮适配。请在系统设置中允许本模块自启动，并将后台省电设为无限制，确保开机自动加载。\n");
        help.setTextSize(17);
        page.addView(help);
        status = new TextView(this);
        status.setTextSize(17);
        status.setText(getSharedPreferences("state", MODE_PRIVATE).getString("status", "尚未应用。请点击启用并允许 Root 授权。"));
        page.addView(status);
        enable = new Button(this);
        enable.setText("启用并应用");
        page.addView(enable);
        stop = new Button(this);
        stop.setText("停用并恢复");
        page.addView(stop);
        enable.setOnClickListener(view -> run(true));
        stop.setOnClickListener(view -> run(false));
        setContentView(page);
    }

    private void run(boolean apply) {
        enable.setEnabled(false);
        stop.setEnabled(false);
        status.setText("正在处理，请确认 Root 授权…");
        new Thread(() -> {
            String result;
            try {
                result = apply ? RootBootstrap.apply(this, true) : RootBootstrap.stop(this);
            } catch (Exception error) {
                result = "操作失败：" + error.getMessage();
                RootBootstrap.status(this, result);
            }
            final String text = result;
            runOnUiThread(() -> {
                status.setText(text);
                enable.setEnabled(true);
                stop.setEnabled(true);
            });
        }, "kapi-shopping-control").start();
    }
}
