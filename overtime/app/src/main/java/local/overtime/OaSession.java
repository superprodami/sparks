package local.overtime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

final class OaSession {
    private static final String LOGIN = "https://oa.raynen.cn:8443/login.jsp";
    private static final String OA_ORIGIN = "https://oa.raynen.cn:8443";
    private static final String HR_HOME = "http://ehr.raynen.cn:3039/hr/index.html";
    private static final long TIMEOUT = 45_000;
    private static final long SAVED_SESSION_TIMEOUT = 5_500;
    private static final long QUERY_TIMEOUT = 8_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static OaSession active;

    interface Listener {
        void status(String message);
        void authenticated();
        void success(OaResult result);
        void failure(String message);
    }

    private final WebView view;
    private final Listener listener;
    private final String queryScript;
    private boolean tryingHrSession;
    private boolean usePage;
    private boolean queryCheckPending;
    private int navigation;
    private long queryDeadline;
    private final String portalScript;
    private final String attendanceScript;
    private final String pageSizeScript;
    private final String rangeScript;
    private final LocalDate from;
    private final LocalDate through;
    private final LocalDate today = Overtime.today();
    private final Runnable poll = this::check;
    private OaCredentials.Account account;
    private long deadline;
    private boolean loaded;
    private boolean submitted;
    private boolean portalOpened;
    private boolean authenticated;
    private String rangeStep;
    private long rangeSettlesAt;
    private boolean rangeRequested;
    private OaResult pages;
    private String previousPage;
    private int pageSizeAttempts;
    private boolean pageSizeChecked;
    private String rowsBeforeSizeChange;
    private long sizeChangeDeadline;

    @SuppressLint("SetJavaScriptEnabled")
    OaSession(Context context, OaCredentials.Account account, LocalDate from, LocalDate through,
            Listener listener) throws IOException {
        this.account = account;
        this.listener = listener;
        this.from = from;
        this.through = through;
        queryScript = script(context, "oa-query.js") + "(" + JSONObject.quote(from.toString()) + ","
                + JSONObject.quote(through.toString()) + ")";
        portalScript = script(context, "oa-portal.js");
        attendanceScript = script(context, "oa-attendance.js");
        pageSizeScript = script(context, "oa-page-size.js");
        rangeScript = script(context, "oa-range.js");
        view = new WebView(context);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        // The DHR portal exposes its employee menu in its desktop view.
        settings.setUserAgentString(settings.getUserAgentString()
                .replaceFirst("\\(Linux;[^)]*\\)", "(Windows NT 10.0; Win64; x64)")
                .replace(" Version/4.0", "").replace(" Mobile", ""));
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                if (request.isForMainFrame() && !allowed(request.getUrl())) {
                    MAIN.post(() -> fail(outsideOrigin(request.getUrl())));
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView webView, String url, Bitmap favicon) {
                navigation++;
                loaded = false;
                if (!allowed(Uri.parse(url))) {
                    fail(outsideOrigin(Uri.parse(url)));
                }
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                loaded = true;
                requestCheck(0);
            }

            @Override
            public void onReceivedError(WebView webView, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    if (tryingHrSession) {
                        retryViaPortal();
                    } else {
                        fail("无法打开 OA 或人事系统，请检查网络后刷新。");
                    }
                }
            }
        });
    }

    View view() {
        return view;
    }

    void start(boolean newLogin) {
        if (active != null) {
            destroyView();
            listener.failure("已有一次 OA 同步正在进行。");
            return;
        }
        active = this;
        deadline = SystemClock.uptimeMillis() + TIMEOUT;
        listener.status("正在打开 OA");
        if (newLogin) {
            android.webkit.WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(ignored -> loadLogin());
        } else {
            tryingHrSession = true;
            queryDeadline = SystemClock.uptimeMillis() + SAVED_SESSION_TIMEOUT;
            view.loadUrl(HR_HOME);
            requestCheck(200);
        }
    }

    private void loadLogin() {
        if (active != this) {
            return;
        }
        view.loadUrl(LOGIN);
        requestCheck(500);
    }

    void layoutForBackground() {
        view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1280, 800);
    }

    void close() {
        if (active == this) {
            active = null;
            MAIN.removeCallbacks(poll);
            destroyView();
        }
    }

    private void destroyView() {
        account = null;
        view.stopLoading();
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        view.destroy();
    }

    private void check() {
        if (active != this) {
            return;
        }
        if (SystemClock.uptimeMillis() >= deadline) {
            fail(rangeRequested ? "OA 未返回所选日期的日结果，请稍后重试。"
                    : authenticated ? "已登录 OA，但没有读到「日结果」。请检查页面或稍后刷新。"
                    : "未能完成 OA 登录，请检查账号、密码和网络。");
            return;
        }
        if (tryingHrSession && SystemClock.uptimeMillis() >= queryDeadline && !queryCheckPending) {
            retryViaPortal();
            return;
        }
        requestCheck(usePage ? 800 : 200);
        String url = view.getUrl();
        if (!loaded || url == null) {
            return;
        }
        Uri current = Uri.parse(url);
        if (isOa(current)) {
            tryingHrSession = false;
            queryDeadline = 0;
            checkPortal(current);
        } else if (isHr(current)) {
            if (usePage) {
                authenticate();
                readAttendance();
            } else {
                queryAttendance();
            }
        }
    }

    private void retryViaPortal() {
        navigation++;
        tryingHrSession = false;
        queryDeadline = 0;
        loaded = false;
        loadLogin();
    }

    private void queryAttendance() {
        if (queryCheckPending) {
            return;
        }
        if (queryDeadline == 0) {
            queryDeadline = SystemClock.uptimeMillis() + QUERY_TIMEOUT;
        }
        int currentNavigation = navigation;
        queryCheckPending = true;
        view.evaluateJavascript(queryScript, payload -> {
            queryCheckPending = false;
            if (active != this || currentNavigation != navigation) {
                return;
            }
            try {
                if (payload != null && !"null".equals(payload)) {
                    JSONObject response = new JSONObject(payload);
                    String state = response.getString("state");
                    if ("ready".equals(state)) {
                        OaResult result = new OaResult(response, today);
                        authenticate();
                        Log.i("AttendanceOA", "已通过日结果查询完成同步");
                        success(result);
                        return;
                    }
                    if (!"unavailable".equals(state) && SystemClock.uptimeMillis() < queryDeadline) {
                        return;
                    }
                } else if (SystemClock.uptimeMillis() < queryDeadline) {
                    return;
                }
            } catch (IOException | JSONException | DateTimeParseException error) {
                // An unfamiliar grid format can still be read from the rendered page.
            }
            if (tryingHrSession) {
                retryViaPortal();
            } else {
                usePage = true;
            }
        });
    }

    private void checkPortal(Uri uri) {
        if ("/login.jsp".equals(uri.getPath()) || "/j_acegi_security_check".equals(uri.getPath())) {
            if (!submitted && account != null) {
                submitLogin();
            } else if (submitted) {
                view.evaluateJavascript("(() => document.querySelector('.lui_login_message_div.tip_message')"
                        + "?.textContent.trim() || '')()", response -> {
                    if (!"\"\"".equals(response) && !"null".equals(response)) {
                        fail("OA 未接受这次登录，请核对账号、密码或查看登录页。");
                    }
                });
            }
            return;
        }
        if (!portalOpened) {
            view.evaluateJavascript(portalScript, hit -> {
                if (active == this && "true".equals(hit)) {
                    authenticate();
                    portalOpened = true;
                    listener.status("正在进入人事系统");
                }
            });
        }
    }

    private void submitLogin() {
        submitted = true;
        listener.status("正在登录 OA");
        String script = "((username, password) => {"
                + "if (location.origin !== " + JSONObject.quote(OA_ORIGIN) + ") return false;"
                + "const user = document.querySelector('input[name=j_username]');"
                + "const pass = document.querySelector('input[name=j_password]');"
                + "const submit = document.querySelector('input[name=btn_submit]');"
                + "if (!user || !pass || !submit || pass.type !== 'password' || user.form !== pass.form"
                + " || pass.form !== submit.form || pass.form.method !== 'post'"
                + " || pass.form.action !== " + JSONObject.quote(OA_ORIGIN + "/j_acegi_security_check")
                + " || typeof window.kmss_onsubmit !== 'function') return false;"
                + "user.value = username; pass.value = password; submit.click(); return true;"
                + "})(" + JSONObject.quote(account.username) + "," + JSONObject.quote(account.password) + ")";
        view.evaluateJavascript(script, sent -> {
            if (active == this && !"true".equals(sent)) {
                submitted = false;
            }
        });
    }

    private void authenticate() {
        if (active == this && !authenticated) {
            authenticated = true;
            account = null;
            CookieManager.getInstance().flush();
            listener.authenticated();
        }
    }

    private void readAttendance() {
        if (rangeStep != null) {
            selectRange();
            return;
        }
        if (SystemClock.uptimeMillis() < rangeSettlesAt) {
            return;
        }
        view.evaluateJavascript(attendanceScript, payload -> {
            if (active != this || payload == null || "null".equals(payload)) {
                return;
            }
            try {
                JSONObject page = new JSONObject(payload);
                if (!"ready".equals(page.getString("state"))) {
                    return;
                }
                String rows = page.getJSONArray("rows").toString();
                OaResult result = new OaResult(page, today);
                if (!result.covers(from) || !result.covers(through)) {
                    if (!rangeRequested) {
                        rangeRequested = true;
                        rangeStep = "start";
                        listener.status("正在查询所选日期");
                        selectRange();
                    }
                    return;
                }
                if (rangeRequested && !result.punches.isEmpty()
                        && (result.punches.firstKey().isBefore(from) || result.punches.lastKey().isAfter(through))) {
                    return;
                }
                int pageSize = page.getInt("pageSize");
                if (!pageSizeChecked && pageSize != 50) {
                    selectPageSize(rows, page.getBoolean("hasNext"));
                    return;
                }
                pageSizeChecked = true;
                if (rows.equals(rowsBeforeSizeChange) && SystemClock.uptimeMillis() < sizeChangeDeadline) {
                    return;
                }
                rowsBeforeSizeChange = null;
                if (rows.equals(previousPage)) {
                    return;
                }
                if (pages == null) {
                    pages = result;
                } else {
                    pages.punches.putAll(result.punches);
                }
                if (page.getBoolean("hasNext")) {
                    previousPage = rows;
                    listener.status("正在读取日结果的后续页");
                    view.evaluateJavascript("document.querySelector('.el-pagination button.btn-next')?.click()", null);
                    return;
                }
                OaResult completed = pages;
                pages = null;
                previousPage = null;
                Log.i("AttendanceOA", "日结果每页条数：" + pageSize);
                success(completed);
            } catch (IOException | JSONException | DateTimeParseException error) {
                fail(error instanceof IOException ? error.getMessage() : "无法读取 OA 日结果的日期或打卡时间。");
            }
        });
    }

    private void selectPageSize(String rows, boolean hasNext) {
        boolean opened = pageSizeAttempts > 0;
        pageSizeAttempts++;
        view.evaluateJavascript(pageSizeScript + "(" + opened + ")", value -> {
            if (active != this) {
                return;
            }
            if ("\"selected\"".equals(value)) {
                pageSizeChecked = true;
                if (hasNext) {
                    rowsBeforeSizeChange = rows;
                    sizeChangeDeadline = SystemClock.uptimeMillis() + 5000;
                }
            } else if (!"\"opening\"".equals(value) || pageSizeAttempts >= 3) {
                pageSizeChecked = true;
            }
        });
    }

    private void selectRange() {
        view.evaluateJavascript(rangeScript + "(" + JSONObject.quote(from.toString()) + ","
                + JSONObject.quote(through.toString()) + "," + JSONObject.quote(rangeStep) + ")", raw -> {
            if (active != this) {
                return;
            }
            String step = raw == null ? "" : raw.replace("\"", "");
            Log.i("AttendanceOA", "日期选择：" + step);
            switch (step) {
                case "open-range":
                    break;
                case "start":
                case "end":
                case "confirm":
                    rangeStep = step;
                    break;
                case "applied":
                    rangeStep = null;
                    rangeSettlesAt = SystemClock.uptimeMillis() + 1800;
                    break;
                default:
                    fail("无法在 OA 日结果中选择这段日期。");
            }
        });
    }

    private void success(OaResult result) {
        if (active == this) {
            close();
            listener.success(result);
        }
    }

    private void fail(String message) {
        if (active == this) {
            close();
            listener.failure(message);
        }
    }

    private void requestCheck(long delay) {
        if (active == this) {
            MAIN.removeCallbacks(poll);
            MAIN.postDelayed(poll, delay);
        }
    }

    private static boolean allowed(Uri uri) {
        return isOa(uri) || isHr(uri) || isHrEntry(uri) || "about:blank".equals(uri.toString());
    }

    private static boolean isHrEntry(Uri uri) {
        return "http".equals(uri.getScheme()) && "oa.raynen.cn".equals(uri.getHost()) && uri.getPort() == 3033;
    }

    private static String outsideOrigin(Uri uri) {
        String origin = uri.getHost() == null ? uri.getScheme() : uri.getScheme() + "://" + uri.getHost();
        if (uri.getPort() != -1) {
            origin += ":" + uri.getPort();
        }
        return "OA 跳转到了其他站点（" + origin + "），无法继续同步。";
    }

    private static boolean isOa(Uri uri) {
        return "https".equals(uri.getScheme()) && "oa.raynen.cn".equals(uri.getHost()) && uri.getPort() == 8443;
    }

    private static boolean isHr(Uri uri) {
        return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && "ehr.raynen.cn".equals(uri.getHost()) && uri.getPort() == 3039;
    }

    private static String script(Context context, String file) throws IOException {
        try (InputStream input = context.getAssets().open(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
