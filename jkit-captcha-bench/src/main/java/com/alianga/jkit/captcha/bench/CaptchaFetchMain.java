package com.alianga.jkit.captcha.bench;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.HttpResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量下载图片验证码，攒真实样本集用。
 *
 * <p>对同一个验证码地址反复 GET，按魔法字节校验响应确实是图片（防止把登录失效的
 * HTML 页存成 png），成功一张才计入 {@code --count}；连续失败太多自动停，避免
 * 会话过期后空转。下载走 {@link HttpUtils}，复用全局 Cookie 会话与 SSL/代理配置。
 * 同一进程里先登录（或用 {@code --cookie} 带上会话），验证码才能与会话绑定。
 *
 * <pre>
 * mvn -pl jkit-captcha-bench exec:java -Dexec.args="--url https://host/captcha --count 200"
 * java ...CaptchaFetchMain --url https://host/captcha --out target/fetch --count 200 \
 *     --threads 4 --interval 500 --cookie "JSESSIONID=xxx" --header "Referer: https://host/login"
 * </pre>
 *
 * <p>只对有权抓取的系统使用；默认每线程 500ms 间隔，调大并发时注意对方服务的承受力。
 */
public final class CaptchaFetchMain {
    /** 连续失败达到这个数就停：多半是会话过期或地址变了，继续抓只会拿到错误页。 */
    static final int MAX_CONSECUTIVE_FAILURES = 20;
    /** 小于这个字节数的图片没有价值，多半是错误占位图。 */
    private static final int MIN_IMAGE_BYTES = 50;

    private CaptchaFetchMain() {
    }

    /**
     * @param args 命令行参数
     * @throws Exception 线程池等待被中断时
     */
    public static void main(String[] args) throws Exception {
        String url = arg(args, "--url", null);
        if (url == null || url.isEmpty()) {
            System.out.println("缺少 --url，例如 --url https://host/captcha");
            return;
        }
        File out = new File(arg(args, "--out", "target/captcha-fetch"));
        int count = Integer.parseInt(arg(args, "--count", "100"));
        int threads = Math.max(1, Integer.parseInt(arg(args, "--threads", "4")));
        long interval = Math.max(0, Long.parseLong(arg(args, "--interval", "500")));
        int timeout = Integer.parseInt(arg(args, "--timeout", "10000"));
        String prefix = arg(args, "--prefix", "captcha");
        List<String> headerArgs = new ArrayList<String>();
        for (int i = 0; i < args.length - 1; i++) {
            if ("--header".equals(args[i])) {
                headerArgs.add(args[i + 1]);
            }
        }
        Map<String, String> headers = new HashMap<String, String>();
        for (int i = 0; i < headerArgs.size(); i++) {
            String entry = headerArgs.get(i);
            int colon = entry.indexOf(':');
            if (colon > 0) {
                headers.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
            }
        }
        String cookie = arg(args, "--cookie", "");
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        if (!out.exists() && !out.mkdirs()) {
            System.out.println("无法创建输出目录 -> " + out.getAbsolutePath());
            return;
        }
        HttpUtils.setConnectTimeout(timeout);
        HttpUtils.setReadTimeout(timeout);

        long started = System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(started);
        AtomicInteger seq = new AtomicInteger();
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger consecutiveFailures = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    while (!stop.get() && saved.get() < count) {
                        try {
                            HttpResponse response = HttpUtils.getResponse(url, headers);
                            byte[] body = response.isSuccessful() ? response.body().bytes()
                                    : new byte[0];
                            String contentType = response.body() == null ? ""
                                    : nullToEmpty(response.body().contentType());
                            if (body.length < MIN_IMAGE_BYTES) {
                                onReject(failed, consecutiveFailures, stop, "HTTP "
                                        + response.code() + "，响应 " + body.length + " 字节");
                            } else if (!contentType.startsWith("image/")) {
                                onReject(failed, consecutiveFailures, stop,
                                        "Content-Type 不是图片：" + contentType);
                            } else {
                                String ext = imageExt(body, contentType);
                                if (ext == null) {
                                    onReject(failed, consecutiveFailures, stop,
                                            "魔法字节不是已知图片格式");
                                } else {
                                    consecutiveFailures.set(0);
                                    int index = seq.incrementAndGet();
                                    File file = new File(out, prefix + '-' + stamp
                                            + '-' + pad4(index) + '.' + ext);
                                    FileOutputStream stream = new FileOutputStream(file);
                                    try {
                                        stream.write(body);
                                    } finally {
                                        stream.close();
                                    }
                                    int done = saved.incrementAndGet();
                                    if (done % 10 == 0 || done == count) {
                                        System.out.println("  已下载 " + done + "/" + count);
                                    }
                                }
                            }
                        } catch (IOException ex) {
                            onReject(failed, consecutiveFailures, stop, "网络错误：" + ex.getMessage());
                        }
                        if (interval > 0) {
                            try {
                                TimeUnit.MILLISECONDS.sleep(interval);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.DAYS);
        System.out.printf(Locale.ROOT, "完成：保存 %d 张，失败 %d 次，目录 %s，用时 %.1f s%n",
                Integer.valueOf(saved.get()), Integer.valueOf(failed.get()),
                out.getAbsolutePath(), Double.valueOf((System.currentTimeMillis() - started) / 1000d));
    }

    /** 一次拒绝：计入失败并检查是否该熔断。 */
    private static void onReject(AtomicInteger failed, AtomicInteger consecutiveFailures,
                                 AtomicBoolean stop, String reason) {
        failed.incrementAndGet();
        int strikes = consecutiveFailures.incrementAndGet();
        if (strikes == 1 || strikes % 5 == 0) {
            System.out.println("  失败 " + strikes + " 次：" + reason);
        }
        if (strikes >= MAX_CONSECUTIVE_FAILURES) {
            if (stop.compareAndSet(false, true)) {
                System.out.println("连续失败 " + strikes + " 次，停止抓取（检查会话与地址）");
            }
        }
    }

    /**
     * 按魔法字节判格式，顺带给出扩展名；未知魔法字节返回 null。
     * 魔法字节比 Content-Type 可靠：错误页也可能标成 image/png。
     */
    static String imageExt(byte[] body, String contentType) {
        if (body.length >= 8 && (body[0] & 0xFF) == 0x89 && body[1] == 'P'
                && body[2] == 'N' && body[3] == 'G') {
            return "png";
        }
        if (body.length >= 6 && body[0] == 'G' && body[1] == 'I' && body[2] == 'F'
                && body[3] == '8') {
            return "gif";
        }
        if (body.length >= 3 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8
                && (body[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (body.length >= 2 && body[0] == 'B' && body[1] == 'M') {
            return "bmp";
        }
        if (body.length >= 12 && body[0] == 'R' && body[1] == 'I' && body[2] == 'F'
                && body[3] == 'F' && body[8] == 'W' && body[9] == 'E' && body[10] == 'B'
                && body[11] == 'P') {
            return "webp";
        }
        if (contentType.startsWith("image/")) {
            String subtype = contentType.substring("image/".length());
            int slash = subtype.indexOf(';');
            if (slash > 0) {
                subtype = subtype.substring(0, slash);
            }
            subtype = subtype.trim().toLowerCase(Locale.ROOT);
            if (!subtype.isEmpty() && subtype.length() <= 5 && subtype.matches("[a-z0-9]+")) {
                return subtype;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String pad4(int value) {
        return String.format(Locale.ROOT, "%04d", Integer.valueOf(value));
    }

    private static String arg(String[] args, String key, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
