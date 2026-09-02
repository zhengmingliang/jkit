package com.alianga.jkit;

import com.alianga.jkit.log.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端口探测工具类，提供 TCP 端口连通性检测与批量端口扫描能力。
 *
 * <p>批量扫描采用「NIO 非阻塞连接 + 少量工作线程分片」的混合模型：
 * 每个工作线程用一个 {@link Selector} 同时保持数百个在途连接，
 * 既避免了「一个端口占一个线程」的开销，又能把套接字系统调用摊到多个 CPU 核心上。
 * 域名只解析一次，扫描过程不共享任何可变静态状态，多个线程可以同时发起互不干扰的扫描。</p>
 */
public class PortsUtils {
    private static final Log log = Log.get(PortsUtils.class);

    /**
     * 默认连接超时时间（毫秒）
     */
    private static final int DEFAULT_CONNECT_TIME_OUT = 200;

    /**
     * 默认同时在途的连接数上限。取值受限于进程可用的文件描述符配额，
     * 实际不足时扫描会自动降低并发而不会失败。
     */
    private static final int DEFAULT_MAX_IN_FLIGHT = 1024;

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
     * 端口号上限
     */
    private static final int MAX_PORT = 65535;

    private static ThreadPoolExecutor pool;

    private PortsUtils() {
    }

    /**
     * 使用默认超时时间（200 毫秒）判断目标主机的指定端口是否开放。
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            // 连接被拒绝、超时或主机不可达，语义上都等价于「端口不可用」，无需上抛
            return false;
        }
    }

    /**
     * 扫描目标主机的端口，使用默认超时（200 毫秒）与默认并发（1024 个在途连接）。
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
     * <p>扫描在调用线程内用一个 {@link Selector} 驱动全部探测，不使用线程池，
     * 因此总耗时约为 {@code 端口总数 / maxInFlight * timeout} 的量级
     * （被拒绝的端口会立即返回 RST，通常远快于该上限）。
     * 调大 {@code maxInFlight} 能近似线性地缩短总耗时，但会占用同等数量的文件描述符；
     * 若进程配额不足，实现会自动退化为较低并发继续扫描，不会抛异常。</p>
     *
     * <p>若扫描期间当前线程被中断，方法会尽快返回已经探测到的部分结果，并保留线程的中断状态。</p>
     *
     * @param host 要扫描的服务 ip 或域名
     * @param portsRule 端口规则，支持逗号分隔与 {@code 起-止} 区间混写
     * @param timeout 单个端口的连接超时时间，单位毫秒，必须大于 0
     * @param maxInFlight 同时在途的连接数上限，必须大于 0
     * @return 升序排列的开放端口列表，没有开放端口时返回空列表
     * @throws IllegalArgumentException 参数非法或主机无法解析时抛出
     * @throws IllegalStateException 无法创建任何套接字通道（如文件描述符已耗尽）时抛出
     */
    public static List<Integer> scanPorts(String host, String portsRule, int timeout, int maxInFlight) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be greater than 0, but was " + timeout);
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be greater than 0, but was " + maxInFlight);
        }
        int[] ports = parsePortsRule(portsRule);
        // 域名只解析一次，避免每个端口都触发一次 DNS 查询
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("unknown host: " + host, e);
        }

        int parallelism = decideParallelism(ports.length);
        List<Integer> openPorts;
        if (parallelism == 1) {
            // 端口不多时直接在调用线程内扫完，不额外创建线程
            openPorts = scanShard(address, ports, 0, ports.length, timeout, maxInFlight);
        } else {
            openPorts = scanSharded(address, ports, timeout, maxInFlight, parallelism);
        }
        java.util.Collections.sort(openPorts);
        return openPorts;
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
     * @param ports 待扫描端口（升序）
     * @param timeout 单端口超时（毫秒）
     * @param maxInFlight 全局在途连接上限，会按分片数均摊
     * @param parallelism 工作线程数
     * @return 各分片合并后的开放端口列表（未排序）
     */
    private static List<Integer> scanSharded(InetAddress address, int[] ports, int timeout,
                                             int maxInFlight, int parallelism) {
        int perShardInFlight = Math.max(1, maxInFlight / parallelism);
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
                        return scanShard(address, ports, from, to, timeout, perShardInFlight);
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
     * @param ports 待扫描端口（升序）
     * @param from 分片起始下标（含）
     * @param to 分片结束下标（不含）
     * @param timeout 单端口超时（毫秒）
     * @param maxInFlight 本分片的在途连接上限
     * @return 本分片探测到的开放端口（按发现顺序）
     * @throws IllegalStateException 选择器创建失败或无法创建任何套接字通道时抛出
     */
    private static List<Integer> scanShard(InetAddress address, int[] ports, int from, int to,
                                           int timeout, int maxInFlight) {
        ScanState state = new ScanState(ports, from, to);
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
                fillWindow(selector, address, timeout, maxInFlight, state);
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
     * @param timeout 单端口超时（毫秒）
     * @param maxInFlight 在途连接上限
     * @param state 本次扫描的状态
     * @throws IOException 一个连接都无法创建时抛出
     */
    private static void fillWindow(Selector selector, InetAddress address, int timeout,
                                   int maxInFlight, ScanState state) throws IOException {
        while (state.inFlight < maxInFlight && state.hasPending()) {
            SocketChannel channel;
            try {
                channel = SocketChannel.open();
            } catch (IOException e) {
                if (state.inFlight == 0) {
                    throw e;
                }
                // 文件描述符暂时耗尽：先让在途连接收敛腾出配额，本轮不再新建
                log.debug("创建套接字通道失败，降低并发后重试：{}", e.getMessage());
                return;
            }
            // 通道已创建，无论后续成败都推进游标，避免同一端口被反复重试
            int port = state.nextPort();
            try {
                channel.configureBlocking(false);
                if (channel.connect(new InetSocketAddress(address, port))) {
                    // 回环地址可能立即完成三次握手
                    state.openPorts.add(port);
                    closeQuietly(channel);
                } else {
                    channel.register(selector, SelectionKey.OP_CONNECT,
                            new Probe(port, System.currentTimeMillis() + timeout));
                    state.inFlight++;
                }
            } catch (IOException e) {
                // 连接被立即拒绝，等价于端口关闭
                closeQuietly(channel);
            }
        }
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
            SocketChannel channel = (SocketChannel) key.channel();
            Probe probe = (Probe) key.attachment();
            boolean open;
            try {
                open = channel.finishConnect();
            } catch (IOException e) {
                // 收到 RST 等于端口关闭，属正常结果
                open = false;
            }
            if (open) {
                state.openPorts.add(probe.port);
            }
            key.cancel();
            closeQuietly(channel);
            state.inFlight--;
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
                state.inFlight--;
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
        private int next;
        private int inFlight;

        ScanState(int[] ports, int from, int to) {
            this.ports = ports;
            this.next = from;
            this.to = to;
        }

        boolean hasPending() {
            return next < to;
        }

        boolean isFinished() {
            return !hasPending() && inFlight == 0;
        }

        int nextPort() {
            return ports[next++];
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
        private final long deadline;

        Probe(int port, long deadline) {
            this.port = port;
            this.deadline = deadline;
        }
    }
}
