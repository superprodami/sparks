package cn.local.kapijd;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaobaoOrder {
    private static final Pattern ORDER = Pattern.compile("订单信息[^\\r\\n]*\\r?\\n([0-9]{10,25})[，,]点击复制");
    private static final Pattern TIME = Pattern.compile("付款时间[：:\\s]*(20[0-9]{2}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2})");
    private static final Pattern AMOUNT = Pattern.compile("淘宝实付款金额：[￥¥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)(?:\\s|$)");
    private static final Pattern PRODUCT = Pattern.compile("(?m)^([^\\r\\n]+)商品价格：[￥¥][0-9]+(?:\\.[0-9]{1,2})?[ \\t]*$");
    private static final Pattern EXCLUDED = Pattern.compile("(?m)^(?:待付款|等待买家付款|交易关闭|交易取消|订单已取消|退款成功|退款中|售后处理中)[ \\t]*$");
    public final String id, amount, time, product;

    private TaobaoOrder(String id, String amount, String time, String product) {
        this.id = id;
        this.amount = amount;
        this.time = time;
        this.product = product;
    }

    public static TaobaoOrder parse(String text) {
        // The new detail fragment shares its Android root with the order list below it.
        int background = text.indexOf("\n跳往搜索页");
        if (background >= 0) text = text.substring(0, background);
        if (EXCLUDED.matcher(text).find()) return null;
        Matcher id = ORDER.matcher(text), amount = AMOUNT.matcher(text), time = TIME.matcher(text), product = PRODUCT.matcher(text);
        if (!id.find() || !amount.find() || !time.find() || !product.find()) return null;
        String orderId = id.group(1), paid = amount.group(1), paidAt = time.group(1), name = product.group(1).trim();
        if (id.find() || amount.find() || time.find() || product.find()) return null;
        BigDecimal value = new BigDecimal(paid);
        if (value.signum() <= 0) return null;
        return new TaobaoOrder(orderId, value.setScale(2).toPlainString(), paidAt, name);
    }
}
