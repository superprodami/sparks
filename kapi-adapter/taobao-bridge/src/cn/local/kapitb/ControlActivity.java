package cn.local.kapitb;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ControlActivity extends Activity {
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
        help.setText(R.string.module_help);
        help.setTextSize(17);
        help.setPadding(0, space, 0, 0);
        page.addView(help);
        setContentView(page);
    }
}
