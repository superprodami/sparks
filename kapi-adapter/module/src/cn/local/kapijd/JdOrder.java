package cn.local.kapijd;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdOrder {
    private static final Pattern ORDER = Pattern.compile("订单编号[：:\\s]*([0-9]{10,25})");
    private static final Pattern TIME = Pattern.compile("支付时间[：:\\s]*(20[0-9]{2}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2})");
    private static final Pattern AMOUNT = Pattern.compile("实付款[\\s\\S]*?合计\\s*[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)(?:\\s|$)");
    private static final Pattern EXCLUDED = Pattern.compile("等待付款|待付款|订单已取消|订单取消|交易关闭|退款成功|退款完成|退款中|售后处理中");
    private static final Pattern PRODUCT = Pattern.compile("(?m)^([^\\r\\n]+)\\r?\\n到手[ \\t]*$");
    public final String id, amount, time, product;

    private JdOrder(String id, String amount, String time, String product) {
        this.id = id;
        this.amount = amount;
        this.time = time;
        this.product = product;
    }

    public static JdOrder parse(String text) {
        if (EXCLUDED.matcher(text).find()) return null;
        Matcher id = ORDER.matcher(text), amount = AMOUNT.matcher(text), time = TIME.matcher(text);
        Matcher product = PRODUCT.matcher(text);
        if (!id.find() || !amount.find() || !time.find() || !product.find()) return null;
        String orderId = id.group(1), paid = amount.group(1), paidAt = time.group(1);
        if (id.find() || amount.find() || time.find()) return null;
        BigDecimal value = new BigDecimal(paid);
        if (value.signum() <= 0) return null;
        return new JdOrder(orderId, value.setScale(2).toPlainString(), paidAt, product.group(1).replace("\ufffc", "").trim());
    }
}
