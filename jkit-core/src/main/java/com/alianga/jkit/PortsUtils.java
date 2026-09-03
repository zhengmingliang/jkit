package com.alianga.jkit;

import com.alianga.jkit.log.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端口探测工具类，提供 TCP 端口连通性检测与批量端口扫描能力。
 *
 * <p>批量扫描采用「NIO 非阻塞连接 + 少量工作线程分片」的混合模型：
 * 每个工作线程用一个 {@link Selector} 同时保持数百个在途连接，
 * 既避免了「一个端口占一个线程」的开销，又能把套接字系统调用摊到多个 CPU 核心上。
 * 关闭端口的 RST 会立即释放在途配额；大规模私网再对静默端口做第二遍补探。
 * 域名只解析一次，扫描过程不共享任何可变静态状态，多个线程可以同时发起互不干扰的扫描。</p>
 *
 * <p>Linux 下会按主路由表选择源地址再发起探测。这样在 Clash/Mihomo 等 TUN 透明代理开启时，
 * 不会因为代理在本地直接完成三次握手，而把全部端口误判为开放。
 * 若系统 DNS 返回 RFC 2544（{@code 198.18.0.0/15}）等 Fake-IP，会再走一次直连 DNS 解析真实地址。</p>
 *
 * <p>扫描本机地址（含 {@code 127.0.0.1}）时优先读内核 TCP listen 表，而不是 {@code connect}。
 * TUN 模式可能劫持回环流量，把关闭端口握手成开放，或把 RST 变成超时；listen 表不受该路径影响。</p>
 */
public class PortsUtils {
    private static final Log log = Log.get(PortsUtils.class);

    /**
     * 默认连接超时时间（毫秒）
     */
    private static final int DEFAULT_CONNECT_TIME_OUT = 500;

    /**
     * 默认同时在途的连接数上限。取值受限于进程可用的文件描述符配额，
     * 实际不足时扫描会自动降低并发而不会失败。
     */
    private static final int DEFAULT_MAX_IN_FLIGHT = 1024;

    /**
     * 全端口扫描的在途窗口预算（毫秒）。只用来抬升 {@code maxInFlight}。
     * DROP 目标的墙钟时间主要由 {@link #DROP_SYN_PER_SECOND} 决定；
     * 1000 SYN/s × 65535 ≈ 66 秒，窗口按约 90 秒预留余量，避免超时被在途窗口卡住。
     */
    private static final int TARGET_FULL_SCAN_MILLIS = 90_000;

    /**
     * 单个端口的最大探测次数。超时的端口会在第一遍扫完之后、用
     * {@link #RETRY_SYN_PER_SECOND} 的慢速率补探一次，避免限速期间丢掉的 SYN
     * 直接变成永久漏报；补探总量受 {@link #RETRY_BUDGET_DIVISOR} 约束，不会把墙钟时间翻倍。
     */
    private static final int MAX_CONNECT_ATTEMPTS = 2;

    /**
     * 单次扫描允许的在途连接上限。本机 ephemeral 端口池大约 2.8 万，留出余量避免耗尽。
     */
    private static final int HARD_MAX_IN_FLIGHT = 8192;

    /**
     * 分片工作线程数上限。端口探测以套接字系统调用为主，实测 1/2/4/8/16/32 线程扫描
     * 全部 65535 个端口耗时约为 1090/604/362/313/279/294 毫秒，8 线程之后收益骤减，
     * 故取该拐点，避免为个位数百分比的提升成倍占用线程与文件描述符。
     */
    private static final int MAX_PARALLELISM = 8;

    /**
     * 每个工作线程至少要分到的端口数，低于该规模不值得再多开线程。
     */
    private static final int MIN_PORTS_PER_WORKER = 256;

    /**
     * 单次 select 的最长等待时间（毫秒）。仅决定超时清理的检查粒度，不影响单个端口的超时判定。
     */
    private static final int POLL_INTERVAL_MILLIS = 20;

    /**
     * 限速窗口的初始突发量。令牌桶容量等于突发量而不是在途上限。
     * 收到 RST 或握手成功会立刻归还令牌：内网/会 RST 的主机吞吐约等于 {@code burst / RTT}。
     * 突发过小会把有 RST 的远程主机卡在每秒数百 SYN；过大则容易一上来就打满安全组。
     * 自适应探针会在丢包后把稳态速率压下来，突发只影响启动与 RST 回收。
     */
    private static final int PACE_BURST = 64;

    /**
     * 远程 DROP 时的默认稳态发包上限。RST 会立刻归还令牌，因此会 RST 的内网不受此值卡住。
     * 4000 SYN/s 会让对端安全组丢掉开放端口的 SYN-ACK，全端口只能扫到一半不到。
     * 1000 SYN/s 约 66 秒，并把单端口超时抬到 500ms，完整优先于十几秒的洪泛。
     */
    private static final int DROP_SYN_PER_SECOND = 1000;

    /**
     * 超时补探阶段的发包上限。必须不高于主速率，否则等于把刚被丢掉的 SYN 再打一遍。
     */
    private static final int RETRY_SYN_PER_SECOND = 400;

    /**
     * 超时补探的端口预算占比：最多对 1/16 的待扫端口做第二次探测。
     * DROP 主机几乎全部超时，全量补探只会把墙钟时间拉长。
     */
    private static final int RETRY_BUDGET_DIVISOR = 16;

    /**
     * 超时补探的最小预算。小范围扫描允许全部补探；全端口则走占比预算。
     */
    private static final int MIN_RETRY_BUDGET = 1024;

    /**
     * 自适应限速的探针复探间隔（毫秒）。探针只占 1 SYN/s，对扫描速率没有影响。
     */
    private static final int CANARY_INTERVAL_MILLIS = 1000;

    /**
     * 自适应限速的下限。65535 / 400 ≈ 164 秒是最坏情况；默认稳态在 1000 SYN/s。
     */
    private static final int MIN_SYN_PER_SECOND = 400;

    /**
     * 私网第一遍的在途连接上限。墙钟由「在途窗口 / RTT」决定，而不是 800 SYN/s：
     * VPN RTT 约 50ms 时，256 在途大约 {@code 65535 * 0.06 / 256 ≈ 15} 秒
     * 即可收完会 RST 的端口。突发过大容易把开放端口的 SYN-ACK 打丢。
     */
    private static final int INTRANET_IN_FLIGHT = 256;

    /**
     * 私网第一遍单端口超时。只用来区分 RST / 开放 / 静默。
     * 50ms RTT 的 VPN 上 280ms 覆盖约 5 个往返；漏掉的开放端口交给第二遍。
     */
    private static final int INTRANET_PASS1_TIMEOUT_MILLIS = 280;

    /**
     * 私网第二遍（仅静默端口）超时。慢服务或 SYN-ACK 排队时才走这里。
     */
    private static final int INTRANET_PASS2_TIMEOUT_MILLIS = 1200;

    /**
     * 私网第二遍在途下限。静默集合通常远小于 65535，不够时再按窗口预算抬升。
     */
    private static final int INTRANET_PASS2_IN_FLIGHT = 96;

    /**
     * 私网安全阀速率。RST 归还令牌时真正的上限是在途窗口；
     * 此值只约束一直不 RST 的 DROP 端口，避免再回到 800/s × 65535 ≈ 82 秒。
     */
    private static final int INTRANET_SYN_PER_SECOND = 4000;

    /**
     * 触发自适应限速（探针）的最小扫描规模。再小的范围打不满安全组，不必上探针。
     */
    private static final int ADAPTIVE_MIN_PORTS = 1024;

    /**
     * 触发常用端口预扫的最小规模。1-1024 这类中等范围已经会洪泛，
     * 必须先慢扫 22/80/443，否则安全组会把熟端口和关闭端口一起丢掉。
     */
    private static final int WELL_KNOWN_PRESCAN_MIN = 128;

    /**
     * 探针超时判定的最小放宽值（毫秒）。探针误判会白白把速率砍半，
     * 因此它的超时要比单端口探测宽松，避免把普通抖动当成限速。
     */
    private static final int CANARY_MIN_TIMEOUT_MILLIS = 1000;

    /**
     * 大规模公网扫描的单端口超时下限。200ms 在空闲时够用（RTT 约 30~50ms），
     * 但洪泛期间排队会把部分 SYN-ACK 拖到 150~200ms 以上，再叠加自适应收紧就会漏报。
     */
    private static final int REMOTE_MIN_TIMEOUT_MILLIS = 500;

    /**
     * 大规模扫描前先慢扫一遍的常用端口。洪泛开始前路径还干净，
     * 22/80/443 这类熟端口不容易被安全组限速丢掉。
     */
    private static final int[] WELL_KNOWN_PORTS = {
            21, 22, 23, 25, 53, 80, 81, 82, 88, 110, 111, 135, 139, 143, 389, 443, 445,
            447, 465, 587, 636, 873, 993, 995, 1080, 1433, 1521, 1723, 2049, 2181, 2375, 2376,
            3000, 3306, 3389, 4000, 4369, 5000, 5432, 5672, 5900, 5984, 6000, 6379, 6500, 6998, 7001,
            8000, 8008, 8023, 8080, 8081, 8084, 8088, 8089, 8194, 8432, 8443, 8702, 8717, 8872, 8888,
            9000, 9090, 9200, 9300, 9418, 11211, 27017, 27018, 31752
    };

    /**
     * Linux 主路由表。policy routing（如 Clash table 2022）不会出现在这里。
     */
    private static final Path PROC_NET_ROUTE = Paths.get("/proc/net/route");

    /**
     * Linux IPv6 主路由表。
     */
    private static final Path PROC_NET_IPV6_ROUTE = Paths.get("/proc/net/ipv6_route");

    /**
     * Linux IPv4 TCP 套接字表，{@code st=0A} 为 LISTEN。
     */
    private static final Path PROC_NET_TCP = Paths.get("/proc/net/tcp");

    /**
     * Linux IPv6 TCP 套接字表。
     */
    private static final Path PROC_NET_TCP6 = Paths.get("/proc/net/tcp6");

    /**
     * {@code /proc/net/tcp} 中 TCP_LISTEN 的十六进制状态。
     */
    private static final String TCP_LISTEN_STATE = "0A";

    /**
     * systemd-resolved 的上行 DNS 列表，通常比 {@code /etc/resolv.conf} 的 127.0.0.53 占位更完整。
     */
    private static final Path SYSTEMD_RESOLV = Paths.get("/run/systemd/resolve/resolv.conf");

    private static final Path RESOLV_CONF = Paths.get("/etc/resolv.conf");

    /**
     * 直连 DNS 查询超时（毫秒）。
     */
    private static final int DIRECT_DNS_TIMEOUT_MILLIS = 800;

    /**
     * DNS A 记录。
     */
    private static final int DNS_TYPE_A = 1;

    /**
     * DNS AAAA 记录。
     */
    private static final int DNS_TYPE_AAAA = 28;

    /**
     * IPv6 RTF_REJECT，匹配到后应跳过。
     */
    private static final int IPV6_RTF_REJECT = 0x0200;

    /**
     * Clash/Mihomo 默认 IPv6 Fake-IP 前缀 {@code fdfe:dcba:9876::/48}。
     */
    private static final byte[] FAKE_IPV6_PREFIX = new byte[]{
            (byte) 0xfd, (byte) 0xfe, (byte) 0xdc, (byte) 0xba, (byte) 0x98, (byte) 0x76
    };

    /**
     * 端口号上限
     */
    private static final int MAX_PORT = 65535;

    private static ThreadPoolExecutor pool;

    private PortsUtils() {
    }

    /**
     * 使用默认超时时间（500 毫秒）判断目标主机的指定端口是否开放。
     *
     * @param host 目标服务的 ip 或域名
     * @param port 待检测的端口号
     * @return 端口可建立 TCP 连接时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isOpen(String host, int port) {
        return isOpen(host, port, DEFAULT_CONNECT_TIME_OUT);
    }

    /**
     * 在指定超时时间内尝试与目标主机的端口建立 TCP 连接，以判断端口是否开放。
     *
     * @param host 目标服务的 ip 或域名
     * @param port 待检测的端口号
     * @param timeout 连接超时时间，单位毫秒
     * @return 端口可建立 TCP 连接时返回 {@code true}，连接失败、超时或主机无法解析时返回 {@code false}
     */
    public static boolean isOpen(String host, int port, int timeout) {
        InetAddress address;
        try {
            address = resolveScanTarget(host);
        } catch (UnknownHostException e) {
            return false;
        }
        Set<Integer> localListening = localListeningPorts(address);
        if (localListening != null) {
            return localListening.contains(port);
        }
        InetAddress bindAddress = selectBindAddress(address);
        try (Socket socket = new Socket()) {
            if (bindAddress != null) {
                socket.bind(new InetSocketAddress(bindAddress, 0));
            }
            socket.connect(new InetSocketAddress(address, port), timeout);
            return true;
        } catch (IOException e) {
            // 连接被拒绝、超时或主机不可达，语义上都等价于「端口不可用」，无需上抛
            return false;
        }
    }

    /**
     * 扫描目标主机的端口，使用默认超时（500 毫秒）与默认并发（1024 个在途连接）。
     *
     * @param host 要扫描的服务 ip 或域名
     * @param portsRule 端口规则，如：{@code 1-65535} 表示扫描 1 到 65535，
     *        {@code 22,3306,80,443} 表示只扫描这几个端口，两种写法可混用
     * @return 升序排列的开放端口列表，没有开放端口时返回空列表
     * @throws IllegalArgumentException 端口规则为空、含非法端口号或主机无法解析时抛出
     */
    public static List<Integer> scanPorts(String host, String portsRule) {
        return scanPorts(host, portsRule, DEFAULT_CONNECT_TIME_OUT, DEFAULT_MAX_IN_FLIGHT);
    }

    /**
     * 扫描目标主机的端口，可指定单个端口的连接超时与同时在途的连接数上限。
     *
     * <p>大规模扫描会按端口数分片并行。关闭端口若立即 RST，令牌会归还，内网通常在数秒内结束。
     * 关闭端口若是 DROP，默认上限约 1000 SYN/s，{@code 1-65535} 大约一分多钟；
     * 对端安全组若丢 SYN，探针会把速率折半。完整优先于把墙钟压到十几秒。
     * 调用方给出的 {@code maxInFlight} 过低时会自动抬高在途窗口。
     * 进程文件描述符不足时会自动降低并发，不会抛异常。</p>
     *
     * <p>全端口扫描会先慢扫一遍常用端口（22/80/443 等），再打乱顺序做全量探测，
     * 避免外网安全组把熟端口和后半段一起限速丢掉。</p>
     *
     * <p>若扫描期间当前线程被中断，方法会尽快返回已经探测到的部分结果，并保留线程的中断状态。</p>
     *
     * <p>Linux 下探测会绑定主路由表对应的本机地址，并在系统 DNS 返回 Fake-IP 时改走直连 DNS，
     * 避免 TUN 透明代理把关闭端口也报成开放。本机地址则直接读内核 listen 表，
     * 不依赖可能被 Clash TUN 劫持的回环 {@code connect}。</p>
     *
     * @param host 要扫描的服务 ip 或域名
     * @param portsRule 端口规则，支持逗号分隔与 {@code 起-止} 区间混写
     * @param timeout 单个端口的连接超时时间，单位毫秒，必须大于 0
     * @param maxInFlight 同时在途的连接数下限；大规模扫描不够快时会自动抬高，必须大于 0
     * @return 升序排列的开放端口列表，没有开放端口时返回空列表
     * @throws IllegalArgumentException 参数非法或主机无法解析时抛出
     * @throws IllegalStateException 无法创建任何套接字通道（如文件描述符已耗尽）时抛出
     */
    public static List<Integer> scanPorts(String host, String portsRule, int timeout, int maxInFlight) {
        return scanPorts(host, portsRule, timeout, maxInFlight, DROP_SYN_PER_SECOND);
    }

    /**
     * 扫描目标主机的端口，并显式指定发包速率上限。
     *
     * <p>{@code synPerSecond} 是 <b>上限</b>而不是固定值。大规模扫描会启用自适应限速：
     * 常用端口预扫阶段挑一个已确认开放的端口当探针，洪泛期间每
     * {@link #CANARY_INTERVAL_MILLIS} 毫秒复探一次。探针一旦超时，说明对端已经开始丢包，
     * 速率立即折半（下限 {@link #MIN_SYN_PER_SECOND}）；探针恢复后再线性回升到本参数给出的上限。
     * 这样在默认 DROP 的主机上也能拿到真实的限速信号，而不是靠固定速率去猜。</p>
     *
     * <p>探针存在时，补探策略也会跟着收紧：只有落在限速窗口内的超时端口才值得再探一次，
     * 路径健康时的超时就是真过滤，不再浪费预算。</p>
     *
     * <p>速率越低越完整、也越慢。默认上限约 1000 SYN/s（完整优先）；用开放端口探针做 AIMD：
     * 安全组开始丢包就折半，恢复后再爬升。拿不准就用
     * {@link #scanPorts(String, String, int, int)} 的默认上限，让自适应限速自己找位置。</p>
     *
     * @param host 要扫描的服务 ip 或域名
     * @param portsRule 端口规则，支持逗号分隔与 {@code 起-止} 区间混写
     * @param timeout 单个端口的连接超时时间，单位毫秒，必须大于 0
     * @param maxInFlight 同时在途的连接数下限，必须大于 0
     * @param synPerSecond 发包速率上限（SYN/s），必须大于 0
     * @return 升序排列的开放端口列表，没有开放端口时返回空列表
     * @throws IllegalArgumentException 参数非法或主机无法解析时抛出
     * @throws IllegalStateException 无法创建任何套接字通道（如文件描述符已耗尽）时抛出
     */
    public static List<Integer> scanPorts(String host, String portsRule, int timeout, int maxInFlight,
                                          int synPerSecond) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be greater than 0, but was " + timeout);
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be greater than 0, but was " + maxInFlight);
        }
        if (synPerSecond <= 0) {
            throw new IllegalArgumentException("synPerSecond must be greater than 0, but was " + synPerSecond);
        }
        int[] ports = parsePortsRule(portsRule);
        // 域名只解析一次，避免每个端口都触发一次 DNS 查询
        InetAddress address;
        try {
            address = resolveScanTarget(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("unknown host: " + host, e);
        }
        // 本机目标不走 TCP connect：Clash TUN 可能劫持 127.0.0.1，
        // 把关闭端口握手成开放或把 RST 拖成超时。内核 listen 表才是真实占用。
        Set<Integer> localListening = localListeningPorts(address);
        if (localListening != null) {
            return filterListeningPorts(ports, localListening);
        }
        shufflePorts(ports);
        InetAddress bindAddress = selectBindAddress(address);
        // 大规模私网：用在途窗口 + RST 立即让位，而不是 800 SYN/s 匀速。
        if (isPrivateScanTarget(address) && ports.length >= WELL_KNOWN_PRESCAN_MIN) {
            return scanIntranetPorts(address, bindAddress, ports, timeout, maxInFlight);
        }
        int rateCap = decideSynRateCap(address, synPerSecond);
        int effectiveTimeout = decideRemoteTimeout(address, timeout, ports.length);
        // RST / 握手成功立刻归还令牌，关闭端口不必等满超时。
        boolean refundCompletions = true;

        int effectiveInFlight = decideMaxInFlight(ports.length, effectiveTimeout, maxInFlight);
        int parallelism = decideParallelism(ports.length);
        List<Integer> openPorts = new ArrayList<Integer>();
        List<Integer> wellKnown = scanWellKnownFirst(address, bindAddress, ports, effectiveTimeout, rateCap);
        openPorts.addAll(wellKnown);

        // 预扫命中的开放端口就是最好的探针：它确定开放，之后再探不通只能是路径被限速了
        AdaptiveRate governor = null;
        CanaryProbe canary = null;
        if (ports.length >= ADAPTIVE_MIN_PORTS && !wellKnown.isEmpty()) {
            governor = new AdaptiveRate(rateCap);
            canary = new CanaryProbe(address, bindAddress, wellKnown.get(0),
                    Math.max(effectiveTimeout * 2, CANARY_MIN_TIMEOUT_MILLIS), governor);
            canary.start();
        }

        LaunchPacer pacer = new LaunchPacer(effectiveInFlight, effectiveTimeout, PACE_BURST, rateCap, governor);
        LaunchPacer retryPacer = new LaunchPacer(effectiveInFlight, effectiveTimeout, PACE_BURST,
                decideRetryRate(rateCap), governor);
        AtomicInteger retryBudget = new AtomicInteger(decideRetryBudget(ports.length, address));
        List<Integer> rest;
        try {
            if (parallelism == 1) {
                // 端口不多时直接在调用线程内扫完，不额外创建线程
                rest = scanShard(address, bindAddress, ports, 0, ports.length, effectiveTimeout,
                        effectiveInFlight, null, pacer, retryPacer, retryBudget, governor, refundCompletions,
                        null);
            } else {
                rest = scanSharded(address, bindAddress, ports, effectiveTimeout, effectiveInFlight, parallelism,
                        pacer, retryPacer, retryBudget, governor, refundCompletions);
            }
        } finally {
            if (canary != null) {
                canary.stop();
                log.debug("自适应限速结束：上限 {} SYN/s，收敛到 {} SYN/s，退避 {} 次，探针丢失 {} 次",
                        synPerSecond, governor.current(), governor.backoffCount(), governor.lossCount());
            }
        }
        Set<Integer> unique = new TreeSet<Integer>(openPorts);
        for (int i = 0; i < rest.size(); i++) {
            Integer port = rest.get(i);
            if (unique.add(port)) {
                openPorts.add(port);
            }
        }
        Collections.sort(openPorts);
        return openPorts;
    }

    /**
     * 大规模扫描前先慢扫常用端口，避免洪泛一开始就把 22/80/443 漏掉。
     *
     * @param address 已解析的目标地址
     * @param bindAddress 探测使用的本机源地址
     * @param scanned 本次计划扫描的全部端口
     * @param timeout 单端口超时（毫秒）
     * @param synPerSecond 发包速率上限（SYN/s）
     * @return 常用端口中已开放的列表，可能为空
     */
    private static List<Integer> scanWellKnownFirst(InetAddress address, InetAddress bindAddress,
                                                    int[] scanned, int timeout, int synPerSecond) {
        if (scanned.length < WELL_KNOWN_PRESCAN_MIN) {
            return Collections.emptyList();
        }
        Set<Integer> requested = new TreeSet<Integer>();
        for (int i = 0; i < scanned.length; i++) {
            requested.add(scanned[i]);
        }
        int count = 0;
        for (int i = 0; i < WELL_KNOWN_PORTS.length; i++) {
            if (requested.contains(WELL_KNOWN_PORTS[i])) {
                count++;
            }
        }
        if (count == 0) {
            return Collections.emptyList();
        }
        int[] extra = new int[count];
        int n = 0;
        for (int i = 0; i < WELL_KNOWN_PORTS.length; i++) {
            if (requested.contains(WELL_KNOWN_PORTS[i])) {
                extra[n++] = WELL_KNOWN_PORTS[i];
            }
        }
        // 预扫必须真正慢：若沿用 4000 SYN/s，熟端口会和对端安全组的洪泛限速撞在一起，
        // 预扫全空 → 没有探针 → 全端口漏报 22/80/443。
        int slowTimeout = Math.max(timeout, 800);
        LaunchPacer slow = new LaunchPacer(16, slowTimeout, 8, 200, null);
        LaunchPacer slowRetry = new LaunchPacer(16, slowTimeout, 8, 100, null);
        return scanShard(address, bindAddress, extra, 0, extra.length, slowTimeout, 16, null,
                slow, slowRetry, new AtomicInteger(extra.length), null, true, null);
    }

    /**
     * 私网两阶段扫描：第一遍激进分类，第二遍只补静默端口。
     *
     * @param address 已解析的目标地址
     * @param bindAddress 探测使用的本机源地址
     * @param ports 已打乱的待扫端口
     * @param timeout 调用方超时（毫秒）
     * @param maxInFlight 调用方给出的在途下限
     * @return 升序开放端口
     */
    private static List<Integer> scanIntranetPorts(InetAddress address, InetAddress bindAddress,
                                                   int[] ports, int timeout, int maxInFlight) {
        int pass1Timeout = Math.max(timeout, INTRANET_PASS1_TIMEOUT_MILLIS);
        // 调用方默认 1024 是公网下限；私网必须封顶，否则 VPN 上 RST 会被挤到数百毫秒。
        int pass1InFlight = INTRANET_IN_FLIGHT;
        if (maxInFlight > 0 && maxInFlight < pass1InFlight) {
            pass1InFlight = Math.max(32, maxInFlight);
        }
        Set<Integer> unique = new TreeSet<Integer>();
        List<Integer> wellKnown = scanWellKnownFirst(address, bindAddress, ports, pass1Timeout,
                INTRANET_SYN_PER_SECOND);
        unique.addAll(wellKnown);

        LaunchPacer pass1Pacer = new LaunchPacer(pass1InFlight, pass1Timeout, pass1InFlight,
                INTRANET_SYN_PER_SECOND, null);
        List<Integer> silents = new ArrayList<Integer>();
        List<Integer> first = scanShard(address, bindAddress, ports, 0, ports.length, pass1Timeout,
                pass1InFlight, null, pass1Pacer, pass1Pacer, new AtomicInteger(0), null, true, silents);
        unique.addAll(first);

        if (!silents.isEmpty()) {
            int[] retryPorts = toPortArray(silents);
            shufflePorts(retryPorts);
            int pass2Timeout = Math.max(timeout, INTRANET_PASS2_TIMEOUT_MILLIS);
            int pass2InFlight = decideMaxInFlight(retryPorts.length, pass2Timeout,
                    INTRANET_PASS2_IN_FLIGHT);
            LaunchPacer pass2Pacer = new LaunchPacer(pass2InFlight, pass2Timeout, pass2InFlight,
                    INTRANET_SYN_PER_SECOND, null);
            List<Integer> second = scanShard(address, bindAddress, retryPorts, 0, retryPorts.length,
                    pass2Timeout, pass2InFlight, null, pass2Pacer, pass2Pacer, new AtomicInteger(0),
                    null, true, null);
            unique.addAll(second);
        }
        return new ArrayList<Integer>(unique);
    }

    /**
     * 把端口列表拷成数组，供第二遍扫描打乱后使用。
     *
     * @param ports 端口列表
     * @return 同内容的数组，可能为空
     */
    private static int[] toPortArray(List<Integer> ports) {
        int[] arr = new int[ports.size()];
        for (int i = 0; i < ports.size(); i++) {
            arr[i] = ports.get(i);
        }
        return arr;
    }

    /**
     * 补探阶段的速率：取主速率的一半，并且不超过 {@link #RETRY_SYN_PER_SECOND}。
     * 补探必须比第一遍更慢，否则等于把刚被丢掉的那批 SYN 以同样速率再打一遍。
     *
     * @param synPerSecond 主速率上限
     * @return 补探速率（SYN/s），至少为 1
     */
    private static int decideRetryRate(int synPerSecond) {
        int half = Math.max(1, synPerSecond / 2);
        return Math.min(half, RETRY_SYN_PER_SECOND);
    }

    /**
     * 私网目标用安全阀封顶，不再卡在 800 SYN/s；公网保持调用方（或默认 DROP）上限。
     *
     * <p>私网真正的加速旋钮是在途窗口和 RST 归还，而不是把 SYN/s 再抬到上万。
     * 本方法只给出令牌桶的安全阀，避免 DROP 主机在无 RST 时失控洪泛。</p>
     *
     * @param target 已解析目标
     * @param requested 调用方给出的上限
     * @return 实际使用的 SYN/s 上限
     */
    static int decideSynRateCap(InetAddress target, int requested) {
        int floor = Math.max(1, requested);
        if (isPrivateScanTarget(target)) {
            return Math.min(floor, INTRANET_SYN_PER_SECOND);
        }
        return floor;
    }

    /**
     * 站点本地 / 链路本地（含 VPN 里的 RFC1918），不是本机网卡上的这个地址。
     *
     * @param target 已解析目标
     * @return 私网扫描目标时返回 {@code true}
     */
    static boolean isPrivateScanTarget(InetAddress target) {
        return target != null && (target.isSiteLocalAddress() || target.isLinkLocalAddress());
    }

    /**
     * 大规模扫描抬高单端口超时，避免洪泛排队把慢 SYN-ACK 裁成关闭。
     * 回环和小范围保持调用方超时，避免把 CI 里的 TEST-NET 小扫描拖慢。
     * 私网全端口也要抬：走 VPN 的 RFC1918 往返可达数十毫秒。
     *
     * @param target 已解析目标
     * @param timeout 调用方超时（毫秒）
     * @param portCount 待扫描端口数
     * @return 实际用于探测的超时
     */
    static int decideRemoteTimeout(InetAddress target, int timeout, int portCount) {
        if (timeout <= 0) {
            return timeout;
        }
        if (target != null && target.isLoopbackAddress()) {
            return timeout;
        }
        if (portCount >= WELL_KNOWN_PRESCAN_MIN) {
            int floor = isPrivateScanTarget(target)
                    ? INTRANET_PASS1_TIMEOUT_MILLIS : REMOTE_MIN_TIMEOUT_MILLIS;
            return Math.max(timeout, floor);
        }
        return timeout;
    }

    /**
     * 按已观测 RTT 收紧单次探测超时，但不超过调用方给出的上限。
     * 没有样本时保持原超时，避免把高延迟公网的 SYN-ACK 裁掉。
     *
     * @param requested 调用方超时（毫秒）
     * @param avgRttMillis 已完成探测（成功或 RST）的平均往返
     * @return 实际用于新探测的超时
     */
    static int decideAdaptiveTimeout(int requested, int avgRttMillis) {
        if (requested <= 0) {
            return requested;
        }
        if (avgRttMillis <= 0) {
            return requested;
        }
        int adapted = avgRttMillis * 4 + 20;
        if (adapted < 40) {
            adapted = 40;
        }
        return Math.min(requested, adapted);
    }

    /**
     * 决定超时补探的端口预算。端口很少时全部补探；全端口扫描则只补 1/16，
     * 避免对端默认 DROP 时把墙钟时间翻倍。
     *
     * @param portCount 待扫描端口数
     * @return 允许发起第二次探测的端口数上限
     */
    private static int decideRetryBudget(int portCount) {
        return decideRetryBudget(portCount, null);
    }

    /**
     * 公网 DROP 主机几乎全部超时，补探预算按 1/16 封顶。
     * 大规模私网走 {@link #scanIntranetPorts}，静默集合本身就是补探预算，不再走这里。
     *
     * @param portCount 待扫描端口数
     * @param target 已解析目标，保留参数以兼容调用方
     * @return 允许发起第二次探测的端口数上限
     */
    static int decideRetryBudget(int portCount, InetAddress target) {
        if (portCount <= MIN_RETRY_BUDGET) {
            return Math.max(0, portCount);
        }
        return Math.max(MIN_RETRY_BUDGET, portCount / RETRY_BUDGET_DIVISOR);
    }

    /**
     * 按扫描规模把过低的并发窗口抬到能在 {@link #TARGET_FULL_SCAN_MILLIS} 内完成。
     * 关闭端口若是 DROP，耗时近似 {@code 端口数 / 并发 × timeout}；
     * 调用方给的 {@code maxInFlight} 只作为下限，不够快时自动加大，同时不超过硬上限。
     *
     * @param portCount 待扫描端口数
     * @param timeout 单端口超时（毫秒）
     * @param requested 调用方给出的并发下限
     * @return 实际使用的在途连接上限
     */
    static int decideMaxInFlight(int portCount, int timeout, int requested) {
        int floor = Math.max(1, requested);
        if (portCount <= 0 || timeout <= 0) {
            return floor;
        }
        long needed = ((long) portCount * timeout + TARGET_FULL_SCAN_MILLIS - 1) / TARGET_FULL_SCAN_MILLIS;
        if (needed < 1L) {
            needed = 1L;
        }
        if (needed > HARD_MAX_IN_FLIGHT) {
            needed = HARD_MAX_IN_FLIGHT;
        }
        return (int) Math.max(floor, needed);
    }

    /**
     * 超时是否还值得再探一次。RST 不走这里，只有「到点仍未完成」的探测才会问。
     *
     * @param attempt 已经发出的探测次数，从 0 起算
     * @return 还可以再发一次时返回 {@code true}
     */
    static boolean shouldRetryTimeout(int attempt) {
        return attempt >= 0 && attempt + 1 < MAX_CONNECT_ATTEMPTS;
    }

    /**
     * 是否是对端 RST / Connection refused。无路由、超时等其它失败不能当成端口关闭的 RST。
     *
     * @param error 连接阶段的异常
     * @return 可以确定端口关闭时返回 {@code true}
     */
    static boolean isConnectionRefused(IOException error) {
        if (error == null) {
            return false;
        }
        String msg = error.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("refused") || msg.contains("拒绝");
    }

    /**
     * 根据待扫端口规模决定分片工作线程数。
     *
     * @param portCount 待扫描的端口总数
     * @return 工作线程数，至少为 1
     */
    private static int decideParallelism(int portCount) {
        int byWorkload = (portCount + MIN_PORTS_PER_WORKER - 1) / MIN_PORTS_PER_WORKER;
        int cap = Math.min(MAX_PARALLELISM, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(cap, byWorkload));
    }

    /**
     * 把端口切成连续分片交给多个工作线程并行扫描，每个分片独立驱动一个 {@link Selector}。
     *
     * @param address 已解析的目标地址
     * @param bindAddress 探测使用的本机源地址，{@code null} 表示交给操作系统选择
     * @param ports 待扫描端口（已打乱）
     * @param timeout 单端口超时（毫秒）
     * @param maxInFlight 全局在途连接上限，由各分片共享而不是均摊
     * @param parallelism 工作线程数
     * @param pacer 全扫描共享的发包令牌桶
     * @param retryPacer 超时补探阶段使用的慢速令牌桶
     * @param retryBudget 全扫描共享的补探端口预算
     * @param governor 自适应限速控制器，{@code null} 表示固定速率
     * @param refundCompletions 握手成功或 RST 时是否归还令牌
     * @return 各分片合并后的开放端口列表（未排序）
     */
    private static List<Integer> scanSharded(InetAddress address, InetAddress bindAddress, int[] ports,
                                             int timeout, int maxInFlight, int parallelism,
                                             LaunchPacer pacer, LaunchPacer retryPacer,
                                             AtomicInteger retryBudget, AdaptiveRate governor,
                                             boolean refundCompletions) {
        AtomicInteger globalInFlight = new AtomicInteger();
        int perShardInFlight = Math.max(1, maxInFlight);
        int chunk = (ports.length + parallelism - 1) / parallelism;
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, new ScanThreadFactory());
        List<Integer> merged = new ArrayList<Integer>();
        try {
            List<Future<List<Integer>>> futures = new ArrayList<Future<List<Integer>>>(parallelism);
            for (int i = 0; i < parallelism; i++) {
                final int from = i * chunk;
                if (from >= ports.length) {
                    break;
                }
                final int to = Math.min(ports.length, from + chunk);
                futures.add(executor.submit(new Callable<List<Integer>>() {
                    @Override
                    public List<Integer> call() {
                        return scanShard(address, bindAddress, ports, from, to, timeout,
                                perShardInFlight, globalInFlight, pacer, retryPacer, retryBudget, governor,
                                refundCompletions, null);
                    }
                }));
            }
            boolean interrupted = false;
            for (Future<List<Integer>> future : futures) {
                try {
                    merged.addAll(future.get());
                } catch (InterruptedException e) {
                    // 恢复中断位并返回已完成分片的结果，剩余分片由 shutdownNow 取消
                    interrupted = true;
                    break;
                } catch (ExecutionException e) {
                    throw toRuntimeException(e.getCause());
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            executor.shutdownNow();
        }
        return merged;
    }

    /**
     * 扫描 {@code ports[from, to)} 区间，单线程内用一个选择器承载全部在途连接。
     *
     * @param address 已解析的目标地址
     * @param bindAddress 探测使用的本机源地址，{@code null} 表示交给操作系统选择
     * @param ports 待扫描端口
     * @param from 分片起始下标（含）
     * @param to 分片结束下标（不含）
     * @param timeout 单端口超时（毫秒）
     * @param maxInFlight 本分片的在途连接上限
     * @param pacer 第一遍探测使用的令牌桶
     * @param retryPacer 超时补探阶段使用的慢速令牌桶
     * @param retryBudget 各分片共享的补探端口预算
     * @param governor 自适应限速控制器，{@code null} 表示固定速率
     * @param refundCompletions 握手成功或 RST 时是否归还令牌
     * @param silentOut 第一遍超时且未补探的端口；{@code null} 表示不收集
     * @return 本分片探测到的开放端口（按发现顺序）
     * @throws IllegalStateException 选择器创建失败或无法创建任何套接字通道时抛出
     */
    private static List<Integer> scanShard(InetAddress address, InetAddress bindAddress, int[] ports,
                                           int from, int to, int timeout, int maxInFlight,
                                           AtomicInteger globalInFlight, LaunchPacer pacer,
                                           LaunchPacer retryPacer, AtomicInteger retryBudget,
                                           AdaptiveRate governor, boolean refundCompletions,
                                           List<Integer> silentOut) {
        ScanState state = new ScanState(ports, from, to, globalInFlight, pacer, retryPacer,
                retryBudget, governor, timeout, refundCompletions);
        Selector selector = null;
        try {
            selector = Selector.open();
            boolean interrupted = false;
            while (!state.isFinished()) {
                if (Thread.interrupted()) {
                    // 记下中断，跳出后恢复中断位并返回已探测到的结果
                    interrupted = true;
                    break;
                }
                fillWindow(selector, address, bindAddress, timeout, maxInFlight, state);
                selector.select(Math.min(timeout, POLL_INTERVAL_MILLIS));
                harvest(selector, state);
                sweepTimeouts(selector, state);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            throw new IllegalStateException("scan ports failed: " + address.getHostAddress(), e);
        } finally {
            closeSelector(selector);
        }
        if (silentOut != null && !state.silentPorts.isEmpty()) {
            silentOut.addAll(state.silentPorts);
        }
        return state.openPorts;
    }

    /**
     * 把分片线程抛出的原因转换为可直接上抛的运行时异常。
     *
     * @param cause 分片任务的失败原因
     * @return 运行时异常
     */
    private static RuntimeException toRuntimeException(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException("scan ports failed", cause);
    }

    /**
     * 端口扫描
     *
     * @param host 要扫描的服务ip或域名
     * @param portsRule 端口规则，如：1-65535 即扫描端口1到65535端口； 22,3306,80,443即只扫描这几个端口
     * @param threadPool 线程池，当前实现不再需要，传入值会被忽略
     * @return 升序排列的开放端口列表，没有开放端口时返回空列表
     * @deprecated 扫描已改为 NIO 非阻塞实现，单线程即可承载上千在途连接，无需外部线程池。
     *         请改用 {@link #scanPorts(String, String)} 或
     *         {@link #scanPorts(String, String, int, int)}；本重载仅为源码兼容保留，
     *         传入的线程池不会被使用，也不会被关闭。
     */
    @Deprecated
    public static List<Integer> scanPorts(String host, String portsRule, ExecutorService threadPool) {
        return scanPorts(host, portsRule);
    }

    /**
     * 获取默认的线程池
     *
     * @return 固定 100 线程的线程池实例，多次调用返回同一实例（已关闭时会重建）
     * @deprecated 端口扫描已不再使用线程池，本方法仅为兼容保留。若确实需要线程池请自行创建并负责关闭；
     *         本方法返回的实例不会被自动关闭。
     */
    @Deprecated
    public static synchronized ExecutorService getDefaultThreadPool() {
        if (pool == null || pool.isShutdown()) {
            pool = new ThreadPoolExecutor(100, 100, 10, TimeUnit.SECONDS,
                    new LinkedBlockingDeque<Runnable>());
        }
        return pool;
    }

    /**
     * 在并发窗口未满且仍有待扫端口时，持续发起非阻塞连接。
     *
     * @param selector 选择器
     * @param address 已解析的目标地址
     * @param bindAddress 探测使用的本机源地址，{@code null} 表示交给操作系统选择
     * @param timeout 单端口超时（毫秒）
     * @param maxInFlight 在途连接上限
     * @param state 本次扫描的状态
     * @throws IOException 一个连接都无法创建时抛出
     */
    private static void fillWindow(Selector selector, InetAddress address, InetAddress bindAddress,
                                   int timeout, int maxInFlight, ScanState state) throws IOException {
        while (state.canLaunch(maxInFlight) && state.hasPending()) {
            if (!state.tryAcquire(maxInFlight)) {
                return;
            }
            SocketChannel channel;
            try {
                channel = SocketChannel.open();
            } catch (IOException e) {
                state.release();
                if (state.inFlight() == 0) {
                    throw e;
                }
                // 文件描述符暂时耗尽：先让在途连接收敛腾出配额，本轮不再新建
                log.debug("创建套接字通道失败，降低并发后重试：{}", e.getMessage());
                return;
            }
            PendingProbe pending = state.nextProbe();
            SelectionKey key = null;
            try {
                channel.configureBlocking(false);
                try {
                    channel.socket().setReuseAddress(true);
                } catch (IOException ignored) {
                    // 未连接套接字上不是所有平台都允许，忽略后仍可继续探测
                }
                try {
                    channel.socket().setSoLinger(true, 0);
                } catch (IOException ignored) {
                    // 部分平台未连接时不允许 linger，不影响探测
                }
                if (bindAddress != null) {
                    try {
                        channel.bind(new InetSocketAddress(bindAddress, 0));
                    } catch (IOException bindError) {
                        // 指定源地址后 ephemeral 端口可能暂时耗尽；先关掉本通道，
                        // 让在途连接收敛后再用同一源地址重试，避免改走 Clash TUN。
                        closeQuietly(channel);
                        state.requeue(pending);
                        state.release();
                        if (state.inFlight() == 0) {
                            throw bindError;
                        }
                        log.debug("绑定源地址 {} 失败，降低并发后重试：{}",
                                bindAddress.getHostAddress(), bindError.getMessage());
                        return;
                    }
                }
                // 先注册再 connect，避免 SYN-ACK 落在 register 之前时 epoll 边沿触发漏掉就绪事件
                long start = System.currentTimeMillis();
                int wait = state.currentTimeout();
                if (wait <= 0) {
                    wait = timeout;
                }
                key = channel.register(selector, SelectionKey.OP_CONNECT,
                        new Probe(pending.port, start, start + wait, pending.attempt));
                if (channel.connect(new InetSocketAddress(address, pending.port))) {
                    markOpen(key, channel, state, pending.port, start);
                }
            } catch (IOException e) {
                if (key != null) {
                    key.cancel();
                }
                closeQuietly(channel);
                // 只有明确的 Connection refused 才是端口关闭；ENOBUFS / EAGAIN 等本地瞬时
                // 错误说明这个 SYN 根本没发出去，端口状态未知，必须重排队而不是记成关闭。
                if (!isConnectionRefused(e) && shouldRetryTimeout(pending.attempt)) {
                    state.requeue(new PendingProbe(pending.port, pending.attempt + 1));
                    log.debug("端口 {} 探测未发出，稍后重试：{}", pending.port, e.getMessage());
                }
                state.release();
            }
        }
    }

    /**
     * 三次握手已经完成：记为开放并立刻释放配额。
     *
     * @param key 已注册的选择键，可为 {@code null}
     * @param channel 已连接的通道
     * @param state 本次扫描的状态
     * @param port 开放端口
     */
    private static void markOpen(SelectionKey key, SocketChannel channel, ScanState state, int port,
                                 long startMillis) {
        state.openPorts.add(port);
        state.noteSample(startMillis);
        if (key != null) {
            key.cancel();
        }
        closeQuietly(channel);
        state.release();
    }

    /**
     * 处理已就绪的连接结果。
     *
     * @param selector 选择器
     * @param state 本次扫描的状态
     */
    private static void harvest(Selector selector, ScanState state) {
        Set<SelectionKey> selected = selector.selectedKeys();
        for (Iterator<SelectionKey> it = selected.iterator(); it.hasNext();) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) {
                continue;
            }
            SocketChannel channel = (SocketChannel) key.channel();
            Probe probe = (Probe) key.attachment();
            boolean open;
            try {
                if (!channel.finishConnect()) {
                    // 连接仍在进行，等下一次 OP_CONNECT，不能当成关闭
                    continue;
                }
                open = true;
            } catch (IOException e) {
                // 收到 RST 等于端口关闭，属正常结果
                open = false;
            }
            if (open) {
                state.openPorts.add(probe.port);
            }
            state.noteSample(probe.startMillis);
            key.cancel();
            closeQuietly(channel);
            state.release();
        }
    }

    /**
     * 关闭已超过各自截止时间仍未完成的连接。
     *
     * @param selector 选择器
     * @param state 本次扫描的状态
     */
    private static void sweepTimeouts(Selector selector, ScanState state) {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            // 已在 harvest 中 cancel 的键要等下一次 select 才会移出 keys()，这里靠 isValid 跳过
            if (!key.isValid()) {
                continue;
            }
            Probe probe = (Probe) key.attachment();
            if (now >= probe.deadline) {
                key.cancel();
                closeQuietly((SocketChannel) key.channel());
                // 限速期间开放端口的 SYN 也会被丢，只探一次就变成永久漏报，
                // 因此把超时端口排到第一遍之后用慢速率补探一次（受预算约束）。
                state.requeueTimeout(probe.port, probe.attempt, probe.startMillis, now);
                state.releaseTimedOut();
            }
        }
    }

    /**
     * 把端口规则解析为升序去重的端口数组。
     *
     * @param portsRule 端口规则，支持逗号分隔与 {@code 起-止} 区间混写
     * @return 升序且去重后的端口数组
     * @throws IllegalArgumentException 规则为空、格式错误或端口越界时抛出
     */
    static int[] parsePortsRule(String portsRule) {
        if (portsRule == null || portsRule.trim().isEmpty()) {
            throw new IllegalArgumentException("portsRule must not be empty");
        }
        Set<Integer> ordered = new TreeSet<Integer>();
        for (String item : portsRule.split(",")) {
            String segment = item.trim();
            if (segment.isEmpty()) {
                continue;
            }
            int dash = segment.indexOf('-');
            if (dash > 0) {
                int start = parsePort(segment.substring(0, dash), portsRule);
                int end = parsePort(segment.substring(dash + 1), portsRule);
                if (start > end) {
                    int tmp = start;
                    start = end;
                    end = tmp;
                }
                for (int port = start; port <= end; port++) {
                    ordered.add(port);
                }
            } else {
                ordered.add(parsePort(segment, portsRule));
            }
        }
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("no valid port in rule: " + portsRule);
        }
        int[] ports = new int[ordered.size()];
        int index = 0;
        for (Integer port : ordered) {
            ports[index++] = port;
        }
        return ports;
    }

    /**
     * Fisher-Yates 打乱待扫端口。顺序扫描容易被防火墙按号段限速，后半段 SYN 被丢就会漏报开放端口。
     *
     * @param ports 待打乱的端口数组，原地修改
     */
    static void shufflePorts(int[] ports) {
        if (ports == null || ports.length < 2) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = ports.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = ports[i];
            ports[i] = ports[j];
            ports[j] = tmp;
        }
    }

    /**
     * 解析单个端口号并校验取值范围。
     *
     * @param text 端口文本
     * @param portsRule 原始规则，仅用于异常信息
     * @return 合法端口号
     * @throws IllegalArgumentException 非数字或不在 1~65535 范围内时抛出
     */
    private static int parsePort(String text, String portsRule) {
        String trimmed = text.trim();
        int port;
        try {
            port = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("illegal port '" + trimmed + "' in rule: " + portsRule, e);
        }
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("port out of range [1, " + MAX_PORT + "]: " + port);
        }
        return port;
    }

    /**
     * 解析扫描目标：优先使用真实地址，避开 Clash/Mihomo 等注入的 Fake-IP。
     *
     * @param host ip 或域名
     * @return 用于发起 TCP 探测的地址
     * @throws UnknownHostException 主机无法解析时抛出
     */
    static InetAddress resolveScanTarget(String host) throws UnknownHostException {
        if (looksLikeIpLiteral(host)) {
            return InetAddress.getByName(host);
        }
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            InetAddress recovered = resolveViaDirectDns(host);
            if (recovered != null) {
                return recovered;
            }
            throw e;
        }
        InetAddress preferred = preferRealAddress(all);
        if (preferred != null && !isFakeIp(preferred)) {
            return preferred;
        }
        InetAddress recovered = resolveViaDirectDns(host);
        if (recovered != null) {
            return recovered;
        }
        if (preferred != null) {
            log.debug("未能绕过 Fake-IP，仍使用系统解析结果：{}", preferred.getHostAddress());
            return preferred;
        }
        throw new UnknownHostException(host);
    }

    /**
     * 从一组解析结果里挑一个最适合做端口探测的地址：真实 IPv4 优先于真实 IPv6，最后才退回 Fake-IP。
     *
     * @param all 系统或 DNS 返回的地址，允许含 {@code null}
     * @return 选中的地址；数组为空时返回 {@code null}
     */
    static InetAddress preferRealAddress(InetAddress[] all) {
        if (all == null || all.length == 0) {
            return null;
        }
        InetAddress first = null;
        InetAddress first4 = null;
        InetAddress firstReal4 = null;
        InetAddress firstReal6 = null;
        for (int i = 0; i < all.length; i++) {
            InetAddress address = all[i];
            if (address == null) {
                continue;
            }
            if (first == null) {
                first = address;
            }
            boolean fake = isFakeIp(address);
            if (address instanceof Inet4Address) {
                if (first4 == null) {
                    first4 = address;
                }
                if (!fake && firstReal4 == null) {
                    firstReal4 = address;
                }
            } else if (!fake && firstReal6 == null) {
                firstReal6 = address;
            }
        }
        if (firstReal4 != null) {
            return firstReal4;
        }
        if (firstReal6 != null) {
            return firstReal6;
        }
        if (first4 != null) {
            return first4;
        }
        return first;
    }

    /**
     * RFC 2544 基准测试网段 {@code 198.18.0.0/15} 以及 Clash 默认 IPv6 Fake-IP 前缀
     * {@code fdfe:dcba:9876::/48}。透明代理会把这些地址在本地接住，TCP 三次握手总会成功。
     *
     * @param address 待判断的地址
     * @return 属于 Fake-IP 网段时返回 {@code true}
     */
    static boolean isFakeIp(InetAddress address) {
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            return (bytes[0] & 0xff) == 198 && (bytes[1] & 0xfe) == 18;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            for (int i = 0; i < FAKE_IPV6_PREFIX.length; i++) {
                if (bytes[i] != FAKE_IPV6_PREFIX[i]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 按 Linux 主路由表为探测选择源地址。找不到合适路由时返回 {@code null}，由操作系统自行选择。
     *
     * @param target 已解析的目标地址
     * @return 应 bind 的本机地址；非 Linux 或无法匹配时返回 {@code null}
     */
    static InetAddress selectBindAddress(InetAddress target) {
        if (target == null) {
            return null;
        }
        if (target.isLoopbackAddress()) {
            return target;
        }
        if (target instanceof Inet4Address) {
            return selectIpv4BindAddress((Inet4Address) target);
        }
        if (target instanceof Inet6Address) {
            return selectIpv6BindAddress((Inet6Address) target);
        }
        return null;
    }

    /**
     * 目标是否是本机地址。回环、未指定地址以及当前网卡上的地址都算。
     *
     * @param address 已解析的目标
     * @return 本机地址时返回 {@code true}
     */
    static boolean isLocalScanTarget(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            return false;
        }
    }

    /**
     * 读取本机当前处于 LISTEN 的端口。仅 Linux 内核表可用时返回集合（可能为空）；
     * 其它系统返回 {@code null}，调用方应回退到 TCP 探测。
     *
     * @param target 扫描目标，用于匹配绑定地址（含 0.0.0.0 / :: 通配）
     * @return 监听端口集合；内核表不可用时返回 {@code null}
     */
    static Set<Integer> localListeningPorts(InetAddress target) {
        if (target == null || !isLocalScanTarget(target)) {
            return null;
        }
        boolean hasTcp = Files.isRegularFile(PROC_NET_TCP);
        boolean hasTcp6 = Files.isRegularFile(PROC_NET_TCP6);
        if (!hasTcp && !hasTcp6) {
            return null;
        }
        Set<Integer> ports = new HashSet<Integer>();
        if (hasTcp) {
            collectLinuxTcpListeners(readLinesQuietly(PROC_NET_TCP), target, false, ports);
        }
        if (hasTcp6) {
            collectLinuxTcpListeners(readLinesQuietly(PROC_NET_TCP6), target, true, ports);
        }
        return ports;
    }

    /**
     * 从请求列表中留下内核正在监听的端口，并升序返回。
     *
     * @param requested 调用方解析出的端口
     * @param listening 内核 listen 表
     * @return 升序开放端口
     */
    static List<Integer> filterListeningPorts(int[] requested, Set<Integer> listening) {
        List<Integer> open = new ArrayList<Integer>();
        if (requested == null || listening == null) {
            return open;
        }
        for (int i = 0; i < requested.length; i++) {
            if (listening.contains(requested[i])) {
                open.add(requested[i]);
            }
        }
        Collections.sort(open);
        return open;
    }

    /**
     * 解析 {@code /proc/net/tcp} 或 {@code /proc/net/tcp6} 中的 LISTEN 行。
     *
     * @param lines 表文本
     * @param target 扫描目标
     * @param ipv6 是否为 tcp6
     * @param into 结果集合
     */
    static void collectLinuxTcpListeners(List<String> lines, InetAddress target, boolean ipv6,
                                         Set<Integer> into) {
        if (lines == null || target == null || into == null) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("sl")) {
                continue;
            }
            String[] cols = splitAsciiColumns(line);
            if (cols.length < 4) {
                continue;
            }
            if (!TCP_LISTEN_STATE.equalsIgnoreCase(cols[3])) {
                continue;
            }
            int colon = cols[1].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String addrHex = cols[1].substring(0, colon);
            String portHex = cols[1].substring(colon + 1);
            int port;
            try {
                port = Integer.parseInt(portHex, 16);
            } catch (NumberFormatException e) {
                continue;
            }
            if (port < 1 || port > MAX_PORT) {
                continue;
            }
            byte[] local = ipv6 ? parseProcIpv6Address(addrHex) : parseProcIpv4Address(addrHex);
            if (listenAddressMatches(local, target)) {
                into.add(port);
            }
        }
    }

    /**
     * {@code /proc/net/tcp} 的 IPv4 地址是小端十六进制。
     *
     * @param hex 8 位十六进制
     * @return 网络序 4 字节；格式错误时返回 {@code null}
     */
    static byte[] parseProcIpv4Address(String hex) {
        if (hex == null || hex.length() != 8) {
            return null;
        }
        try {
            int le = (int) Long.parseLong(hex, 16);
            return new byte[]{
                    (byte) le,
                    (byte) (le >>> 8),
                    (byte) (le >>> 16),
                    (byte) (le >>> 24)
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code /proc/net/tcp6} 的 IPv6 地址按 4 个小端 32 位字存放。
     *
     * @param hex 32 位十六进制
     * @return 16 字节地址；格式错误时返回 {@code null}
     */
    static byte[] parseProcIpv6Address(String hex) {
        if (hex == null || hex.length() != 32) {
            return null;
        }
        byte[] bytes = new byte[16];
        try {
            for (int w = 0; w < 4; w++) {
                int word = (int) Long.parseLong(hex.substring(w * 8, w * 8 + 8), 16);
                bytes[w * 4] = (byte) word;
                bytes[w * 4 + 1] = (byte) (word >>> 8);
                bytes[w * 4 + 2] = (byte) (word >>> 16);
                bytes[w * 4 + 3] = (byte) (word >>> 24);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return bytes;
    }

    /**
     * 套接字本地地址是否对扫描目标可见：精确匹配、IPv4 映射，或同族通配（0.0.0.0 / ::）。
     *
     * @param local 内核表里的本地地址
     * @param target 扫描目标
     * @return 该监听应对本次扫描可见时返回 {@code true}
     */
    static boolean listenAddressMatches(byte[] local, InetAddress target) {
        if (local == null || target == null) {
            return false;
        }
        byte[] dest = target.getAddress();
        if (dest == null) {
            return false;
        }
        if (local.length == dest.length && addressBytesEqual(local, dest)) {
            return true;
        }
        if (isUnspecifiedAddress(local) && local.length == dest.length) {
            return true;
        }
        if (local.length == 16 && dest.length == 4 && isIpv4MappedAddress(local)) {
            return local[12] == dest[0] && local[13] == dest[1]
                    && local[14] == dest[2] && local[15] == dest[3];
        }
        return false;
    }

    static boolean isIpv4MappedAddress(byte[] v6) {
        if (v6 == null || v6.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (v6[i] != 0) {
                return false;
            }
        }
        return v6[10] == (byte) 0xff && v6[11] == (byte) 0xff;
    }

    private static boolean isUnspecifiedAddress(byte[] addr) {
        if (addr == null || (addr.length != 4 && addr.length != 16)) {
            return false;
        }
        for (int i = 0; i < addr.length; i++) {
            if (addr[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean addressBytesEqual(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress selectIpv4BindAddress(Inet4Address target) {
        if (!Files.isRegularFile(PROC_NET_ROUTE)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(PROC_NET_ROUTE, StandardCharsets.US_ASCII);
            List<Ipv4Route> routes = parseIpv4Routes(lines);
            boolean skipFakeNic = !isFakeIp(target);
            Ipv4Route route = matchIpv4Route(ipv4ToInt(target), routes, skipFakeNic);
            if (route != null) {
                InetAddress bound = addressOnInterface(route.iface, true, target.isLoopbackAddress());
                if (bound != null) {
                    return bound;
                }
            }
            return skipFakeNic ? fallbackPhysicalAddress(true) : null;
        } catch (IOException e) {
            log.debug("读取 IPv4 路由表失败：{}", e.getMessage());
            return null;
        }
    }

    private static InetAddress selectIpv6BindAddress(Inet6Address target) {
        if (!Files.isRegularFile(PROC_NET_IPV6_ROUTE)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(PROC_NET_IPV6_ROUTE, StandardCharsets.US_ASCII);
            List<Ipv6Route> routes = parseIpv6Routes(lines);
            boolean skipFakeNic = !isFakeIp(target);
            Ipv6Route route = matchIpv6Route(target.getAddress(), routes, skipFakeNic);
            if (route != null) {
                InetAddress bound = addressOnInterface(route.iface, false,
                        target.isLoopbackAddress() || target.isLinkLocalAddress());
                if (bound != null) {
                    return bound;
                }
            }
            return skipFakeNic ? fallbackPhysicalAddress(false) : null;
        } catch (IOException e) {
            log.debug("读取 IPv6 路由表失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 {@code /proc/net/route} 文本。目标地址、掩码在文件中以小端十六进制保存。
     *
     * @param lines 路由表文本行
     * @return 已启用的 IPv4 路由
     */
    static List<Ipv4Route> parseIpv4Routes(List<String> lines) {
        List<Ipv4Route> routes = new ArrayList<Ipv4Route>();
        if (lines == null) {
            return routes;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("Iface")) {
                continue;
            }
            String[] cols = splitAsciiColumns(line);
            if (cols.length < 8) {
                continue;
            }
            int flags;
            int metric;
            try {
                flags = Integer.parseInt(cols[3], 16);
                metric = Integer.parseInt(cols[6]);
            } catch (NumberFormatException e) {
                continue;
            }
            if ((flags & 0x1) == 0 || (flags & 0x200) != 0) {
                continue;
            }
            int dest = reverseIpv4Hex(cols[1]);
            int mask = reverseIpv4Hex(cols[7]);
            if (dest == Integer.MIN_VALUE || mask == Integer.MIN_VALUE) {
                continue;
            }
            routes.add(new Ipv4Route(cols[0], dest, mask, metric, Integer.bitCount(mask)));
        }
        return routes;
    }

    /**
     * 最长前缀匹配，前缀长度相同时取 metric 更小的路由。
     *
     * @param target 网络序 IPv4 地址
     * @param routes 候选路由
     * @return 最佳路由；没有匹配项时返回 {@code null}
     */
    static Ipv4Route matchIpv4Route(int target, List<Ipv4Route> routes) {
        return matchIpv4Route(target, routes, false);
    }

    /**
     * 最长前缀匹配，前缀长度相同时取 metric 更小的路由。
     *
     * @param target 网络序 IPv4 地址
     * @param routes 候选路由
     * @param skipFakeNic 为 {@code true} 时跳过承载 Fake-IP 的网卡，避免外网探测再走回透明代理
     * @return 最佳路由；没有匹配项时返回 {@code null}
     */
    static Ipv4Route matchIpv4Route(int target, List<Ipv4Route> routes, boolean skipFakeNic) {
        Ipv4Route best = null;
        if (routes == null) {
            return null;
        }
        for (int i = 0; i < routes.size(); i++) {
            Ipv4Route route = routes.get(i);
            if ((target & route.mask) != route.dest) {
                continue;
            }
            if (skipFakeNic && shouldSkipRoute(route.iface, route.prefixLen)) {
                continue;
            }
            if (best == null
                    || route.prefixLen > best.prefixLen
                    || (route.prefixLen == best.prefixLen && route.metric < best.metric)) {
                best = route;
            }
        }
        return best;
    }

    /**
     * 解析 {@code /proc/net/ipv6_route} 文本。
     *
     * @param lines 路由表文本行
     * @return 已启用的 IPv6 路由
     */
    static List<Ipv6Route> parseIpv6Routes(List<String> lines) {
        List<Ipv6Route> routes = new ArrayList<Ipv6Route>();
        if (lines == null) {
            return routes;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = splitAsciiColumns(line);
            if (cols.length < 10) {
                continue;
            }
            byte[] dest = parseHexAddress(cols[0], 16);
            if (dest == null) {
                continue;
            }
            int prefixLen;
            int metric;
            int flags;
            try {
                prefixLen = Integer.parseInt(cols[1], 16);
                metric = (int) Long.parseLong(cols[5], 16);
                flags = Integer.parseInt(cols[8], 16);
            } catch (NumberFormatException e) {
                continue;
            }
            if (prefixLen < 0 || prefixLen > 128 || (flags & IPV6_RTF_REJECT) != 0) {
                continue;
            }
            routes.add(new Ipv6Route(cols[9], dest, prefixLen, metric));
        }
        return routes;
    }

    static Ipv6Route matchIpv6Route(byte[] target, List<Ipv6Route> routes) {
        return matchIpv6Route(target, routes, false);
    }

    static Ipv6Route matchIpv6Route(byte[] target, List<Ipv6Route> routes, boolean skipFakeNic) {
        Ipv6Route best = null;
        if (target == null || routes == null) {
            return null;
        }
        for (int i = 0; i < routes.size(); i++) {
            Ipv6Route route = routes.get(i);
            if (!ipv6PrefixMatch(route.dest, route.prefixLen, target)) {
                continue;
            }
            if (skipFakeNic && shouldSkipRoute(route.iface, route.prefixLen)) {
                continue;
            }
            if (best == null
                    || route.prefixLen > best.prefixLen
                    || (route.prefixLen == best.prefixLen && route.metric < best.metric)) {
                best = route;
            }
        }
        return best;
    }

    private static InetAddress addressOnInterface(String iface, boolean ipv4, boolean allowLocal) {
        if (iface == null || iface.isEmpty()) {
            return null;
        }
        try {
            NetworkInterface nic = NetworkInterface.getByName(iface);
            if (nic == null || !nic.isUp()) {
                return null;
            }
            InetAddress fallback = null;
            Enumeration<InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (ipv4) {
                    if (!(address instanceof Inet4Address)) {
                        continue;
                    }
                } else if (!(address instanceof Inet6Address)) {
                    continue;
                }
                if (address.isMulticastAddress()) {
                    continue;
                }
                if (!allowLocal && (address.isLoopbackAddress() || address.isLinkLocalAddress())) {
                    continue;
                }
                if (isFakeIp(address) && !allowLocal) {
                    continue;
                }
                if (!ipv4 && address instanceof Inet6Address && ((Inet6Address) address).isIPv4CompatibleAddress()) {
                    if (fallback == null) {
                        fallback = address;
                    }
                    continue;
                }
                return address;
            }
            return fallback;
        } catch (SocketException e) {
            log.debug("读取网卡 {} 地址失败：{}", iface, e.getMessage());
            return null;
        }
    }

    /**
     * 外网探测应避开透明代理接管的默认路由：
     * Clash/Mihomo 的 {@code from all lookup 2022} 会把绑在 TUN 上的连接再送回代理，
     * 三次握手在本地完成，关闭端口也会被报成开放。
     *
     * @param iface 路由出口网卡
     * @param prefixLen 路由前缀长度；仅默认路由（0）会被按名称过滤
     * @return 应跳过该路由时返回 {@code true}
     */
    static boolean shouldSkipRoute(String iface, int prefixLen) {
        if (isFakeIpInterface(iface)) {
            return true;
        }
        if (prefixLen != 0) {
            return false;
        }
        return isTunnelLikeInterface(iface);
    }

    /**
     * 按网卡名识别常见隧道 / 透明代理接口。名称匹配失败时仍可能通过 {@link #isFakeIpInterface(String)} 识别。
     *
     * @param iface 网卡名
     * @return 看起来像 TUN/TAP/Clash Meta 接口时返回 {@code true}
     */
    static boolean isTunnelLikeInterface(String iface) {
        if (iface == null || iface.isEmpty()) {
            return false;
        }
        String name = iface.toLowerCase(Locale.ROOT);
        return name.startsWith("tun")
                || name.startsWith("tap")
                || name.startsWith("utun")
                || name.startsWith("wg")
                || "meta".equals(name)
                || name.startsWith("meta")
                || name.contains("clash")
                || name.contains("mihomo");
    }

    /**
     * 主路由表被透明代理改写后，退回到一块看起来像物理网卡的地址再 bind。
     * Clash 的 {@code from all lookup 2022} 对绑了真实源地址的连接不会再生效。
     *
     * @param ipv4 {@code true} 选择 IPv4 地址，否则选择 IPv6
     * @return 可用的本机地址；找不到时返回 {@code null}
     */
    static InetAddress fallbackPhysicalAddress(boolean ipv4) {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) {
                return null;
            }
            InetAddress fallback = null;
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) {
                    continue;
                }
                if (isTunnelLikeInterface(nic.getName()) || isFakeIpInterface(nic.getName())) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (ipv4) {
                        if (!(address instanceof Inet4Address)) {
                            continue;
                        }
                    } else if (!(address instanceof Inet6Address)) {
                        continue;
                    }
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isMulticastAddress() || isFakeIp(address)) {
                        continue;
                    }
                    if (nic.getName().startsWith("en") || nic.getName().startsWith("eth")
                            || nic.getName().startsWith("wl") || nic.getName().startsWith("em")
                            || nic.getName().startsWith("p")) {
                        return address;
                    }
                    if (fallback == null) {
                        fallback = address;
                    }
                }
            }
            return fallback;
        } catch (SocketException e) {
            log.debug("枚举本机网卡失败：{}", e.getMessage());
            return null;
        }
    }

    private static boolean isFakeIpInterface(String iface) {
        try {
            NetworkInterface nic = NetworkInterface.getByName(iface);
            if (nic == null) {
                return false;
            }
            Enumeration<InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                if (isFakeIp(addresses.nextElement())) {
                    return true;
                }
            }
        } catch (SocketException e) {
            return false;
        }
        return false;
    }

    private static InetAddress resolveViaDirectDns(String host) {
        String asciiHost;
        try {
            asciiHost = java.net.IDN.toASCII(host);
        } catch (Exception e) {
            asciiHost = host;
        }
        if (asciiHost == null || asciiHost.isEmpty() || looksLikeIpLiteral(asciiHost)) {
            return null;
        }
        List<InetAddress> nameservers = loadNameservers();
        InetAddress ipv4 = queryFirst(nameservers, asciiHost, DNS_TYPE_A);
        if (ipv4 != null) {
            return ipv4;
        }
        return queryFirst(nameservers, asciiHost, DNS_TYPE_AAAA);
    }

    private static InetAddress queryFirst(List<InetAddress> nameservers, String host, int qtype) {
        for (int i = 0; i < nameservers.size(); i++) {
            InetAddress dns = nameservers.get(i);
            InetAddress bind = selectBindAddress(dns);
            if (bind == null && isFakeIp(dns)) {
                continue;
            }
            InetAddress answer = queryDns(dns, bind, host, qtype);
            if (answer != null && !isFakeIp(answer)) {
                log.debug("直连 DNS {} 将 {} 解析为 {}", dns.getHostAddress(), host, answer.getHostAddress());
                return answer;
            }
        }
        return null;
    }

    /**
     * 读取系统 nameserver，跳过 Fake-IP 与本机 stub（它们会再次返回 Fake-IP）。
     *
     * @param lines resolv.conf 文本
     * @return 去重后的上游 DNS
     */
    static List<InetAddress> parseNameservers(List<String> lines) {
        LinkedHashSet<InetAddress> unique = new LinkedHashSet<InetAddress>();
        if (lines == null) {
            return new ArrayList<InetAddress>();
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (!line.regionMatches(true, 0, "nameserver", 0, 10)) {
                continue;
            }
            String rest = line.substring(10).trim();
            if (rest.isEmpty()) {
                continue;
            }
            int space = rest.indexOf(' ');
            String token = space < 0 ? rest : rest.substring(0, space);
            try {
                InetAddress dns = InetAddress.getByName(token);
                if (dns.isLoopbackAddress() || isFakeIp(dns)) {
                    continue;
                }
                unique.add(dns);
            } catch (UnknownHostException e) {
                // 忽略无法解析的 nameserver 行
            }
        }
        return new ArrayList<InetAddress>(unique);
    }

    private static List<InetAddress> loadNameservers() {
        List<InetAddress> servers = parseNameservers(readLinesQuietly(SYSTEMD_RESOLV));
        if (servers.isEmpty()) {
            servers = parseNameservers(readLinesQuietly(RESOLV_CONF));
        }
        addPublicDnsFallback(servers);
        return servers;
    }

    private static void addPublicDnsFallback(List<InetAddress> servers) {
        String[] fallback = {"223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8"};
        for (int i = 0; i < fallback.length; i++) {
            try {
                InetAddress dns = InetAddress.getByName(fallback[i]);
                if (!servers.contains(dns)) {
                    servers.add(dns);
                }
            } catch (UnknownHostException e) {
                // 字面量 IP，不会发生
            }
        }
    }

    private static List<String> readLinesQuietly(Path path) {
        try {
            if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                return Files.readAllLines(path, StandardCharsets.US_ASCII);
            }
        } catch (IOException e) {
            log.debug("读取 {} 失败：{}", path, e.getMessage());
        }
        return Collections.emptyList();
    }

    private static InetAddress queryDns(InetAddress dns, InetAddress bind, String host, int qtype) {
        byte[] question = encodeDnsQuery(host, qtype);
        if (question == null) {
            return null;
        }
        DatagramSocket socket = null;
        try {
            if (bind != null) {
                socket = new DatagramSocket(new InetSocketAddress(bind, 0));
            } else {
                socket = new DatagramSocket();
            }
            socket.setSoTimeout(DIRECT_DNS_TIMEOUT_MILLIS);
            socket.send(new DatagramPacket(question, question.length, dns, 53));
            byte[] buf = new byte[512];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            List<InetAddress> answers = parseDnsAnswers(packet.getData(), packet.getLength(), qtype);
            for (int i = 0; i < answers.size(); i++) {
                InetAddress answer = answers.get(i);
                if (!isFakeIp(answer)) {
                    return answer;
                }
            }
        } catch (IOException e) {
            log.debug("直连 DNS 查询 {} @{} 失败：{}", host, dns.getHostAddress(), e.getMessage());
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return null;
    }

    static byte[] encodeDnsQuery(String host, int qtype) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.putShort((short) ThreadLocalRandom.current().nextInt(1, 65535));
        buf.putShort((short) 0x0100);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        String[] labels = host.split("\\.");
        for (int i = 0; i < labels.length; i++) {
            byte[] label = labels[i].getBytes(StandardCharsets.US_ASCII);
            if (label.length == 0 || label.length > 63) {
                return null;
            }
            buf.put((byte) label.length);
            buf.put(label);
        }
        buf.put((byte) 0);
        buf.putShort((short) qtype);
        buf.putShort((short) 1);
        byte[] query = new byte[buf.position()];
        buf.flip();
        buf.get(query);
        return query;
    }

    /**
     * 解析 DNS 应答中指定类型的地址记录，忽略 CNAME。
     *
     * @param data 应答报文
     * @param length 有效长度
     * @param qtype {@link #DNS_TYPE_A} 或 {@link #DNS_TYPE_AAAA}
     * @return 解析到的地址，可能为空列表
     */
    static List<InetAddress> parseDnsAnswers(byte[] data, int length, int qtype) {
        List<InetAddress> answers = new ArrayList<InetAddress>();
        if (data == null || length < 12) {
            return answers;
        }
        int flags = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        if ((flags & 0xf) != 0) {
            return answers;
        }
        int qdcount = ((data[4] & 0xff) << 8) | (data[5] & 0xff);
        int ancount = ((data[6] & 0xff) << 8) | (data[7] & 0xff);
        int pos = 12;
        for (int i = 0; i < qdcount; i++) {
            pos = skipDnsName(data, length, pos);
            pos += 4;
            if (pos > length) {
                return answers;
            }
        }
        for (int i = 0; i < ancount && pos + 10 <= length; i++) {
            pos = skipDnsName(data, length, pos);
            if (pos + 10 > length) {
                break;
            }
            int type = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
            int rdlen = ((data[pos + 8] & 0xff) << 8) | (data[pos + 9] & 0xff);
            pos += 10;
            if (pos + rdlen > length) {
                break;
            }
            if (type == qtype) {
                try {
                    if (type == DNS_TYPE_A && rdlen == 4) {
                        answers.add(InetAddress.getByAddress(copyOfRange(data, pos, 4)));
                    } else if (type == DNS_TYPE_AAAA && rdlen == 16) {
                        answers.add(InetAddress.getByAddress(copyOfRange(data, pos, 16)));
                    }
                } catch (UnknownHostException e) {
                    // getByAddress 对定长数组不会失败
                }
            }
            pos += rdlen;
        }
        return answers;
    }

    private static int skipDnsName(byte[] data, int length, int pos) {
        int jumps = 0;
        boolean jumped = false;
        int end = pos;
        while (pos < length && jumps < 10) {
            int len = data[pos] & 0xff;
            if (len == 0) {
                if (!jumped) {
                    end = pos + 1;
                }
                return jumped ? end : pos + 1;
            }
            if ((len & 0xc0) == 0xc0) {
                if (pos + 1 >= length) {
                    return length;
                }
                if (!jumped) {
                    end = pos + 2;
                    jumped = true;
                }
                pos = ((len & 0x3f) << 8) | (data[pos + 1] & 0xff);
                jumps++;
                continue;
            }
            pos += 1 + len;
        }
        return jumped ? end : Math.min(pos, length);
    }

    private static byte[] copyOfRange(byte[] data, int offset, int len) {
        byte[] copy = new byte[len];
        System.arraycopy(data, offset, copy, 0, len);
        return copy;
    }

    static boolean looksLikeIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return true;
        }
        if (host.indexOf(':') >= 0) {
            return true;
        }
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dots == 3;
    }

    static int ipv4ToInt(Inet4Address address) {
        byte[] bytes = address.getAddress();
        return ((bytes[0] & 0xff) << 24)
                | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8)
                | (bytes[3] & 0xff);
    }

    private static int reverseIpv4Hex(String hex) {
        if (hex == null || hex.length() != 8) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.reverseBytes((int) Long.parseLong(hex, 16));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static byte[] parseHexAddress(String hex, int size) {
        if (hex == null || hex.length() != size * 2) {
            return null;
        }
        byte[] bytes = new byte[size];
        try {
            for (int i = 0; i < size; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return bytes;
    }

    static boolean ipv6PrefixMatch(byte[] dest, int prefixLen, byte[] target) {
        if (dest == null || target == null || dest.length != 16 || target.length != 16) {
            return false;
        }
        if (prefixLen == 0) {
            return true;
        }
        int full = prefixLen / 8;
        int rem = prefixLen % 8;
        for (int i = 0; i < full; i++) {
            if (dest[i] != target[i]) {
                return false;
            }
        }
        if (rem > 0) {
            int mask = 0xff << (8 - rem);
            return (dest[full] & mask) == (target[full] & mask);
        }
        return true;
    }

    private static String[] splitAsciiColumns(String line) {
        List<String> cols = new ArrayList<String>(12);
        int i = 0;
        while (i < line.length()) {
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            if (i >= line.length()) {
                break;
            }
            int start = i;
            while (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
                i++;
            }
            cols.add(line.substring(start, i));
        }
        return cols.toArray(new String[cols.size()]);
    }

    /**
     * {@code /proc/net/route} 中的一条 IPv4 路由。
     */
    static final class Ipv4Route {
        final String iface;
        final int dest;
        final int mask;
        final int metric;
        final int prefixLen;

        Ipv4Route(String iface, int dest, int mask, int metric, int prefixLen) {
            this.iface = iface;
            this.dest = dest;
            this.mask = mask;
            this.metric = metric;
            this.prefixLen = prefixLen;
        }
    }

    /**
     * {@code /proc/net/ipv6_route} 中的一条 IPv6 路由。
     */
    static final class Ipv6Route {
        final String iface;
        final byte[] dest;
        final int prefixLen;
        final int metric;

        Ipv6Route(String iface, byte[] dest, int prefixLen, int metric) {
            this.iface = iface;
            this.dest = dest;
            this.prefixLen = prefixLen;
            this.metric = metric;
        }
    }

    /**
     * 关闭通道并忽略关闭异常。
     *
     * @param channel 待关闭的通道，可为 {@code null}
     */
    private static void closeQuietly(SocketChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("关闭套接字通道失败：{}", e.getMessage());
        }
    }

    /**
     * 关闭套接字并忽略关闭异常。
     *
     * @param socket 待关闭的套接字，可为 {@code null}
     */
    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("关闭套接字失败：{}", e.getMessage());
        }
    }

    /**
     * 关闭选择器，并释放其上仍注册着的通道。
     *
     * @param selector 待关闭的选择器，可为 {@code null}
     */
    private static void closeSelector(Selector selector) {
        if (selector == null) {
            return;
        }
        // 中断或异常提前退出时仍有在途通道，必须一并释放，避免文件描述符泄漏
        for (SelectionKey key : selector.keys()) {
            closeQuietly((SocketChannel) key.channel());
        }
        try {
            selector.close();
        } catch (IOException e) {
            log.debug("关闭选择器失败：{}", e.getMessage());
        }
    }

    /**
     * 单次分片扫描的可变状态。每个分片独立创建，因此不存在跨分片或跨调用的共享状态。
     */
    private static final class ScanState {
        private final int[] ports;
        private final int to;
        private final List<Integer> openPorts = new ArrayList<Integer>();
        private final AtomicInteger globalInFlight;
        private final LaunchPacer pacer;
        private final LaunchPacer retryPacer;
        private final AtomicInteger retryBudget;
        private final AdaptiveRate governor;
        private final boolean refundCompletions;
        private final ArrayDeque<RetryPort> retries = new ArrayDeque<RetryPort>();
        private final ArrayDeque<RetryPort> timedOut = new ArrayDeque<RetryPort>();
        private final List<Integer> silentPorts = new ArrayList<Integer>();
        private final int requestedTimeout;
        private int next;
        private int inFlight;
        private int probeTimeout;
        private long rttSum;
        private int rttCount;

        ScanState(int[] ports, int from, int to, AtomicInteger globalInFlight, LaunchPacer pacer,
                  LaunchPacer retryPacer, AtomicInteger retryBudget, AdaptiveRate governor,
                  int timeout, boolean refundCompletions) {
            this.ports = ports;
            this.next = from;
            this.to = to;
            this.globalInFlight = globalInFlight;
            this.pacer = pacer;
            this.retryPacer = retryPacer;
            this.retryBudget = retryBudget;
            this.governor = governor;
            this.requestedTimeout = timeout;
            this.probeTimeout = timeout;
            this.refundCompletions = refundCompletions;
        }

        int currentTimeout() {
            return probeTimeout;
        }

        void noteSample(long startMillis) {
            int sample = (int) Math.max(1L, System.currentTimeMillis() - startMillis);
            rttSum += sample;
            rttCount++;
            if (rttCount >= 8) {
                int adapted = decideAdaptiveTimeout(requestedTimeout, (int) (rttSum / rttCount));
                // 只允许放宽、不允许收紧：RST 往往比慢服务的 SYN-ACK 快，
                // 按 RST 收紧会把 150~200ms 的开放端口裁成超时。
                if (adapted > probeTimeout) {
                    probeTimeout = adapted;
                }
            }
        }

        boolean hasPending() {
            return next < to || !retries.isEmpty() || !timedOut.isEmpty();
        }

        boolean isFinished() {
            return !hasPending() && inFlight == 0;
        }

        /**
         * 第一遍已经扫完、只剩超时端口要补探。此阶段改用慢速令牌桶，
         * 否则等于把刚被限速丢掉的那批 SYN 以同样的速率再打一遍。
         */
        private boolean inRetryPhase() {
            return retries.isEmpty() && next >= to && !timedOut.isEmpty();
        }

        private LaunchPacer activePacer() {
            return inRetryPhase() ? retryPacer : pacer;
        }

        PendingProbe nextProbe() {
            if (!retries.isEmpty()) {
                RetryPort retry = retries.pollFirst();
                return new PendingProbe(retry.port, retry.attempt);
            }
            if (next < to) {
                return new PendingProbe(ports[next++], 0);
            }
            RetryPort retry = timedOut.pollFirst();
            return new PendingProbe(retry.port, retry.attempt);
        }

        void requeue(PendingProbe pending) {
            retries.addFirst(new RetryPort(pending.port, pending.attempt));
        }

        /**
         * 把超时的端口排到第一遍之后再补探一次，受全局预算约束。
         *
         * <p>有探针时策略更精准：只有落在限速窗口内超时的端口才值得再探——
         * 探针正常说明路径通畅，这时候的超时就是真的被过滤了，补探只是浪费预算。
         * 没有探针（小范围扫描或预扫没命中）时退回按预算无差别补探。</p>
         *
         * @param port 超时的端口
         * @param attempt 已经发出的探测次数
         * @param probeStart 本次探测的发起时刻
         * @param now 判定超时的时刻
         */
        void requeueTimeout(int port, int attempt, long probeStart, long now) {
            if (!shouldRetryTimeout(attempt)) {
                silentPorts.add(port);
                return;
            }
            // 带外探针只有 1 SYN/s、超时也更宽，洪泛丢包时它仍然能通，
            // 因此不能按探针健康度跳过补探，只按预算约束。
            if (retryBudget != null && retryBudget.getAndDecrement() <= 0) {
                // 预算已用尽，恢复计数避免长期跑负；留给两阶段扫描的静默集合。
                retryBudget.incrementAndGet();
                silentPorts.add(port);
                return;
            }
            timedOut.addLast(new RetryPort(port, attempt + 1));
        }

        int inFlight() {
            return inFlight;
        }

        boolean canLaunch(int maxInFlight) {
            if (inFlight >= maxInFlight) {
                return false;
            }
            if (globalInFlight != null && globalInFlight.get() >= maxInFlight) {
                return false;
            }
            LaunchPacer active = activePacer();
            return active == null || active.canLaunch();
        }

        boolean tryAcquire(int maxInFlight) {
            if (inFlight >= maxInFlight) {
                return false;
            }
            LaunchPacer active = activePacer();
            if (active != null && !active.tryAcquire()) {
                return false;
            }
            if (globalInFlight == null) {
                inFlight++;
                return true;
            }
            while (true) {
                int current = globalInFlight.get();
                if (current >= maxInFlight) {
                    if (active != null) {
                        active.refund();
                    }
                    return false;
                }
                if (globalInFlight.compareAndSet(current, current + 1)) {
                    inFlight++;
                    return true;
                }
            }
        }

        void release() {
            finishProbe(true);
        }

        void releaseTimedOut() {
            finishProbe(false);
        }

        private void finishProbe(boolean refund) {
            if (inFlight > 0) {
                inFlight--;
            }
            if (globalInFlight != null) {
                globalInFlight.decrementAndGet();
            }
            // RST / 握手成功立刻归还令牌，关闭端口不必等满超时。
            // 超时不退：DROP 路径只靠令牌桶补发，避免静默端口把窗口冲满。
            LaunchPacer active = activePacer();
            if (refund && refundCompletions && active != null) {
                active.refund();
            }
        }
    }

    /**
     * 令牌桶限速：一开始只发放 {@code burst} 个令牌，之后按当前速率匀速补发。
     * 令牌上限就是 {@code burst} 本身，而不是在途上限——否则 RST 密集的路径会把额度
     * 一路攒到数百，再一次性打出去，远超设计中的突发量。
     * <p>速率取「构造时给的固定值」与「自适应控制器当前值」的较小者，
     * 因此补探桶即使共用控制器也不会被抬到主速率上去。</p>
     * <p>RST / 握手成功会 {@link #refund()}。突发量等于令牌容量，
     * 私网把突发量对齐在途窗口，公网仍用较小突发以免一上来打满安全组。
     * 亚毫秒间隔用小数累计，避免高频 refill 把额度丢掉。
     */
    static final class LaunchPacer {
        private final int fixedRate;
        private final AdaptiveRate governor;
        private final boolean paused;
        private final int capacity;
        private long lastRefillNanos;
        private double tokens;

        LaunchPacer(int maxInFlight, int timeoutMillis, int burst) {
            this(maxInFlight, timeoutMillis, burst, DROP_SYN_PER_SECOND, null);
        }

        LaunchPacer(int maxInFlight, int timeoutMillis, int burst, int synPerSecond) {
            this(maxInFlight, timeoutMillis, burst, synPerSecond, null);
        }

        LaunchPacer(int maxInFlight, int timeoutMillis, int burst, int synPerSecond, AdaptiveRate governor) {
            int ceiling = Math.max(1, burst);
            int window = Math.max(1, maxInFlight);
            if (ceiling > window) {
                ceiling = window;
            }
            // 令牌上限收敛到突发量：refund 只是把额度还回来，不该让稳态速率被攒出来的库存突破
            this.capacity = ceiling;
            this.fixedRate = Math.max(1, synPerSecond);
            this.governor = governor;
            // timeoutMillis 保留以兼容调用方，速率按绝对 SYN/s 而不是窗口/超时。
            this.paused = timeoutMillis <= 0;
            this.lastRefillNanos = System.nanoTime();
            this.tokens = ceiling;
        }

        /**
         * 当前生效速率：自适应控制器只会把速率往下压，不会突破构造时给的上限。
         *
         * @return 每纳秒补发的令牌数
         */
        private double refillPerNanos() {
            if (paused) {
                return 0.0;
            }
            int rate = fixedRate;
            if (governor != null) {
                int adaptive = governor.current();
                if (adaptive < rate) {
                    rate = adaptive;
                }
            }
            return rate / 1_000_000_000.0;
        }

        synchronized boolean canLaunch() {
            refill();
            return tokens >= 1.0;
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        synchronized void refund() {
            if (tokens < capacity) {
                tokens += 1.0;
                if (tokens > capacity) {
                    tokens = capacity;
                }
            }
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0L) {
                return;
            }
            lastRefillNanos = now;
            tokens += elapsed * refillPerNanos();
            if (tokens > capacity) {
                tokens = capacity;
            }
        }
    }

    /**
     * 自适应限速控制器：用一个「已确认开放」的探针端口反推对端是否已经开始丢包，
     * 再按 AIMD（丢包折半、正常线性回升）调整发包速率。
     *
     * <p>固定速率的问题在于没有反馈：对默认 DROP 的主机来说，超时是常态，
     * 光看超时比例分不出「端口被过滤」和「我把对端打限速了」。探针端口确定开放，
     * 它一旦探不通就只剩一种解释——路径被限速了，这就是需要的那个信号。</p>
     */
    static final class AdaptiveRate {
        private final int maxRate;
        private final int minRate;
        private final int increaseStep;
        private final AtomicInteger backoffs = new AtomicInteger();
        private final AtomicInteger losses = new AtomicInteger();
        private volatile int current;
        private volatile long lastLimitedMillis = Long.MIN_VALUE;

        AdaptiveRate(int maxRate) {
            this.maxRate = Math.max(1, maxRate);
            this.minRate = Math.min(this.maxRate, MIN_SYN_PER_SECOND);
            // 回升要比退避慢得多，否则刚降下来就又冲上去，速率会一直在限速线上振荡
            this.increaseStep = Math.max(1, this.maxRate / 10);
            this.current = this.maxRate;
        }

        int current() {
            return current;
        }

        int backoffCount() {
            return backoffs.get();
        }

        int lossCount() {
            return losses.get();
        }

        /**
         * 探针探不通：对端已经在丢包，速率折半并记下限速时刻。
         */
        synchronized void onCanaryLost() {
            losses.incrementAndGet();
            lastLimitedMillis = System.currentTimeMillis();
            int next = Math.max(minRate, current / 2);
            if (next != current) {
                current = next;
                backoffs.incrementAndGet();
                log.debug("探针丢失，发包速率降到 {} SYN/s", next);
            }
        }

        /**
         * 探针正常：线性回升，避免一次退避之后整轮扫描都停在保守速率上。
         */
        synchronized void onCanaryOk() {
            if (current >= maxRate) {
                return;
            }
            current = Math.min(maxRate, current + increaseStep);
        }

        /**
         * 判断某次探测是否落在限速窗口内。放宽一个探针间隔，因为限速是在
         * 探针超时那一刻才被发现的，实际起始时间要早于这个时刻。
         *
         * @param startMillis 探测发起时刻
         * @param endMillis 探测超时时刻
         * @return 这次超时可能是被限速丢包造成的
         */
        boolean wasLimitedDuring(long startMillis, long endMillis) {
            long limited = lastLimitedMillis;
            if (limited == Long.MIN_VALUE) {
                return false;
            }
            return limited >= startMillis - CANARY_INTERVAL_MILLIS && limited <= endMillis;
        }
    }

    /**
     * 探针线程：周期性地重探一个已确认开放的端口，把结果喂给 {@link AdaptiveRate}。
     * 只占约 1 SYN/s，对扫描速率没有实质影响。
     */
    private static final class CanaryProbe implements Runnable {
        private final InetAddress address;
        private final InetAddress bindAddress;
        private final int port;
        private final int timeout;
        private final AdaptiveRate governor;
        private volatile boolean stopped;
        private Thread thread;

        CanaryProbe(InetAddress address, InetAddress bindAddress, int port, int timeout,
                    AdaptiveRate governor) {
            this.address = address;
            this.bindAddress = bindAddress;
            this.port = port;
            this.timeout = timeout;
            this.governor = governor;
        }

        void start() {
            thread = new Thread(this, "jkit-portscan-canary");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                if (probeOnce()) {
                    governor.onCanaryOk();
                } else {
                    governor.onCanaryLost();
                }
                try {
                    Thread.sleep(CANARY_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    // 扫描结束时会 interrupt，直接退出即可
                    return;
                }
            }
        }

        private boolean probeOnce() {
            Socket socket = new Socket();
            try {
                if (bindAddress != null) {
                    socket.bind(new InetSocketAddress(bindAddress, 0));
                }
                socket.connect(new InetSocketAddress(address, port), timeout);
                return true;
            } catch (IOException e) {
                // 收到 RST 说明路径是通的，只是探针端口上的服务恰好停了；这不是限速。
                return isConnectionRefused(e);
            } finally {
                closeQuietly(socket);
            }
        }
    }

    /**
     * 分片工作线程工厂，产出守护线程，避免扫描线程阻止 JVM 退出。
     */
    private static final class ScanThreadFactory implements ThreadFactory {
        private static final AtomicInteger SEQ = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "jkit-portscan-" + SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 单个端口的探测上下文。
     */
    private static final class Probe {
        private final int port;
        private final long startMillis;
        private final long deadline;
        private final int attempt;

        Probe(int port, long startMillis, long deadline, int attempt) {
            this.port = port;
            this.startMillis = startMillis;
            this.deadline = deadline;
            this.attempt = attempt;
        }
    }

    private static final class RetryPort {
        private final int port;
        private final int attempt;

        RetryPort(int port, int attempt) {
            this.port = port;
            this.attempt = attempt;
        }
    }

    /**
     * 待发出的探测：首次扫描 attempt=0，超时重试 attempt=1。
     */
    private static final class PendingProbe {
        private final int port;
        private final int attempt;

        PendingProbe(int port, int attempt) {
            this.port = port;
            this.attempt = attempt;
        }
    }
}
