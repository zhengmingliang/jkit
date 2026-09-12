package com.alianga.jkit;

import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link PortsUtils} 的端口规则解析与扫描行为测试。
 *
 * <p>全部用例只探测 127.0.0.1，不依赖外部网络。</p>
 */
public class PortsUtilsTest {

    /**
     * 打乱后集合不变，只是顺序变化。
     */
    @Test
    public void shufflePorts_keepsTheSamePorts() {
        int[] ports = PortsUtils.parsePortsRule("1-16");
        int[] original = ports.clone();
        PortsUtils.shufflePorts(ports);
        Arrays.sort(ports);
        assertArrayEquals(original, ports);
    }

    /**
     * 逗号分隔的端口应逐个解析。
     */
    @Test
    public void parsePortsRule_parsesCommaSeparated() {
        assertArrayEquals(new int[]{22, 80, 443, 3306}, PortsUtils.parsePortsRule("22,3306,80,443"));
    }

    /**
     * 区间应展开为连续端口。
     */
    @Test
    public void parsePortsRule_expandsRange() {
        assertArrayEquals(new int[]{80, 81, 82, 83}, PortsUtils.parsePortsRule("80-83"));
    }

    /**
     * 区间与单端口混写、重复项与空白都应被正确处理，并输出升序去重结果。
     */
    @Test
    public void parsePortsRule_mergesMixedRuleAndDeduplicates() {
        assertArrayEquals(new int[]{22, 80, 81, 82, 443},
                PortsUtils.parsePortsRule(" 443 , 80-82 , 22 , 80 , 443 "));
    }

    /**
     * 起止写反的区间应被自动纠正，而不是解析出空结果。
     */
    @Test
    public void parsePortsRule_normalizesReversedRange() {
        assertArrayEquals(new int[]{80, 81, 82}, PortsUtils.parsePortsRule("82-80"));
    }

    /**
     * 单端口边界值应被接受。
     */
    @Test
    public void parsePortsRule_acceptsBoundaryPorts() {
        assertArrayEquals(new int[]{1}, PortsUtils.parsePortsRule("1"));
        assertArrayEquals(new int[]{65535}, PortsUtils.parsePortsRule("65535"));
    }

    /**
     * 全端口扫描时，过低的并发会被抬到约 2 分钟预算所需的窗口。
     * DROP 目标按单次探测估算，避免为全量重试把 SYN 速率抬到外网防火墙的限速线以上。
     */
    @Test
    public void decideMaxInFlight_raisesLowConcurrencyForFullRange() {
        int raised = PortsUtils.decideMaxInFlight(65535, 300, 64);
        // 65535 * 300ms / 120s = 164，低于此值 2 分钟内扫不完
        assertTrue("全端口 300ms 超时应至少 164 并发，实际=" + raised, raised >= 164);
        assertTrue("并发不应超过硬上限 8192，实际=" + raised, raised <= 8192);
        assertEquals(16, PortsUtils.decideMaxInFlight(10, 200, 16));
        assertEquals(8192, PortsUtils.decideMaxInFlight(65535, 20_000, 64));
    }

    /**
     * 只有明确的 Connection refused 才算 RST；无路由等其它连接失败不能当成拒绝。
     */
    @Test
    public void isConnectionRefused_onlyMatchesRefused() {
        assertTrue(PortsUtils.isConnectionRefused(new java.net.ConnectException("Connection refused")));
        assertFalse(PortsUtils.isConnectionRefused(new java.net.ConnectException("No route to host")));
        assertFalse(PortsUtils.isConnectionRefused(new java.net.SocketException("Invalid argument")));
        assertFalse(PortsUtils.isConnectionRefused(null));
    }

    /**
     * 超时只再探一次：第一次超时值得重试，第二次则视为关闭。
     */
    @Test
    public void shouldRetryTimeout_retriesOnlyOnce() {
        assertTrue(PortsUtils.shouldRetryTimeout(0));
        assertFalse(PortsUtils.shouldRetryTimeout(1));
        assertFalse(PortsUtils.shouldRetryTimeout(2));
        assertFalse(PortsUtils.shouldRetryTimeout(-1));
    }

    /**
     * RST/成功应立刻归还令牌；高频 refill 不得丢掉不足 1ms 的额度。
     */
    @Test
    public void launchPacer_refundsAndKeepsSubMillisecondRefill() {
        PortsUtils.LaunchPacer pacer = new PortsUtils.LaunchPacer(100, 100, 1);
        assertTrue(pacer.tryAcquire());
        assertFalse("突发额度用尽后应暂停发包", pacer.tryAcquire());
        pacer.refund();
        assertTrue("握手成功或 RST 应立即归还令牌", pacer.tryAcquire());
        assertFalse(pacer.tryAcquire());

        long end = System.nanoTime() + 30_000_000L;
        while (System.nanoTime() < end) {
            pacer.canLaunch();
        }
        assertTrue("频繁 refill 不应吞掉不足 1ms 的额度", pacer.tryAcquire());
    }

    /**
     * 归还的令牌不能攒过突发量。否则 RST 密集的路径会把额度囤到在途上限，
     * 再一次性打出去，稳态速率远超设计值。
     */
    @Test
    public void launchPacer_capsTokensAtBurstNotInFlightWindow() {
        PortsUtils.LaunchPacer pacer = new PortsUtils.LaunchPacer(1000, 100, 4);
        for (int i = 0; i < 4; i++) {
            assertTrue("突发额度内应允许发包", pacer.tryAcquire());
        }
        assertFalse("用尽突发额度后应暂停发包", pacer.tryAcquire());

        for (int i = 0; i < 50; i++) {
            pacer.refund();
        }
        int granted = 0;
        while (granted <= 100 && pacer.tryAcquire()) {
            granted++;
        }
        assertTrue("50 次归还不应攒出远超突发量的额度，实际=" + granted, granted <= 10);
    }

    /**
     * 探针丢失应折半退避，且不低于下限；探针恢复后线性回升，不得越过给定上限。
     * 上限必须明显高于下限 MIN_SYN_PER_SECOND(400)，折半退避才不会被下限顶死，
     * 这样本用例才能验证「丢探针折半」与「恢复后线性回升」的语义。
     */
    @Test
    public void adaptiveRate_halvesOnLossAndRecoversLinearly() {
        PortsUtils.AdaptiveRate governor = new PortsUtils.AdaptiveRate(1600);
        assertEquals("初始速率就是调用方给的上限", 1600, governor.current());

        governor.onCanaryLost();
        assertEquals(800, governor.current());
        governor.onCanaryLost();
        assertEquals(400, governor.current());   // 第二次折半正好落到下限
        assertEquals(2, governor.backoffCount());

        // 回升步长是上限的 1/10，必须比退避慢得多，否则会在限速线上振荡
        governor.onCanaryOk();
        assertEquals(560, governor.current());   // 400 + 160

        for (int i = 0; i < 100; i++) {
            governor.onCanaryOk();
        }
        assertEquals("回升不得越过调用方给的上限", 1600, governor.current());
    }

    /**
     * 退避不得跌破下限 MIN_SYN_PER_SECOND(400)，否则扫描会慢到没有实用价值。
     */
    @Test
    public void adaptiveRate_neverDropsBelowFloor() {
        PortsUtils.AdaptiveRate governor = new PortsUtils.AdaptiveRate(500);
        for (int i = 0; i < 50; i++) {
            governor.onCanaryLost();
        }
        assertTrue("速率不应跌到下限 MIN_SYN_PER_SECOND(400) 以下，实际=" + governor.current(),
                governor.current() >= 400);
        assertEquals(50, governor.lossCount());
    }

    /**
     * 只有落在限速窗口内的探测才算「可能被限速丢包」，路径健康时的超时是真过滤。
     */
    @Test
    public void adaptiveRate_limitedWindowCoversProbesAroundTheLoss() {
        PortsUtils.AdaptiveRate governor = new PortsUtils.AdaptiveRate(500);
        assertFalse("还没丢过探针时不该判定为限速", governor.wasLimitedDuring(0L, 1_000L));

        governor.onCanaryLost();
        long limited = System.currentTimeMillis();
        assertTrue("与限速时刻重叠的探测应被判定为可疑",
                governor.wasLimitedDuring(limited - 300, limited + 10));
        assertFalse("限速之后很久才发起的探测不该再算可疑",
                governor.wasLimitedDuring(limited + 60_000, limited + 60_300));
    }

    /**
     * 速率参数必须为正。
     */
    @Test(expected = IllegalArgumentException.class)
    public void scanPorts_rejectsNonPositiveSynRate() {
        PortsUtils.scanPorts("127.0.0.1", "80", 200, 16, 0);
    }

    /**
     * 显式指定速率上限的重载应与默认重载给出一致的结果。
     */
    @Test
    public void scanPorts_explicitRateFindsSameListeningPort() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        try {
            int port = server.getLocalPort();
            List<Integer> found = PortsUtils.scanPorts("127.0.0.1", String.valueOf(port), 300, 16, 50);
            assertTrue("限速上限不应影响单端口探测结果", found.contains(port));
        } finally {
            server.close();
        }
    }

    /**
     * 空规则应被拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void parsePortsRule_rejectsEmptyRule() {
        PortsUtils.parsePortsRule("   ");
    }

    /**
     * null 规则应被拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void parsePortsRule_rejectsNullRule() {
        PortsUtils.parsePortsRule(null);
    }

    /**
     * 端口 0 越界，应被拒绝而不是当作合法端口去连接。
     */
    @Test(expected = IllegalArgumentException.class)
    public void parsePortsRule_rejectsZeroPort() {
        PortsUtils.parsePortsRule("0");
    }

    /**
     * 端口超过 65535 应被拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void parsePortsRule_rejectsPortAboveMax() {
        PortsUtils.parsePortsRule("65536");
    }

    /**
     * 非数字端口应带上原始规则一起报错。
     */
    @Test
    public void parsePortsRule_rejectsNonNumericPort() {
        try {
            PortsUtils.parsePortsRule("22,abc");
            fail("非数字端口应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("异常信息应包含原始规则以便定位", e.getMessage().contains("22,abc"));
        }
    }

    /**
     * 扫描应找出全部真实处于监听状态的端口，且结果按端口号升序排列。
     */
    @Test
    public void scanPorts_findsAllListeningPortsInAscendingOrder() throws IOException {
        List<ServerSocket> servers = new ArrayList<ServerSocket>();
        TreeSet<Integer> expected = new TreeSet<Integer>();
        try {
            for (int i = 0; i < 6; i++) {
                ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                servers.add(server);
                expected.add(server.getLocalPort());
            }
            // 只扫描覆盖这些端口的最小闭区间，避免用例耗时过长
            String rule = expected.first() + "-" + expected.last();
            List<Integer> found = PortsUtils.scanPorts("127.0.0.1", rule, 200, 512);

            // 本机可能还有其他服务在该区间内监听，因此只断言「包含」而非「相等」
            assertTrue("应找出全部已监听端口，缺失：" + missing(expected, found),
                    found.containsAll(expected));
            assertAscending(found);
        } finally {
            closeAll(servers);
        }
    }

    /**
     * 未被监听的端口不应出现在扫描结果中。
     */
    @Test
    public void scanPorts_doesNotReportClosedPort() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        int closedPort = server.getLocalPort();
        server.close();

        List<Integer> found = PortsUtils.scanPorts("127.0.0.1", String.valueOf(closedPort), 300, 16);
        assertFalse("已关闭的端口不应被报告为开放", found.contains(closedPort));
    }

    @Ignore("手工全端口扫描，耗时长且依赖外部网络")
    @Test
    public void scan() {
        printScan("172.16.18.233");
    }

    @Ignore("手工全端口扫描，耗时长且依赖外部网络")
    @Test
    public void scan1() {
//        List<Integer> found = PortsUtils.scanPorts("62.234.82.149", "222", 500, 64);
//        System.out.println(found);
        printScan("62.234.82.149");
    }

    @Ignore("手工全端口扫描，耗时长且依赖外部网络")
    @Test
    public void scan2() {
        List<Integer> found = PortsUtils.scanPorts("62.234.82.149", "6998,8194,8432,8702,8872,31752", 500, 64);
        System.out.println(found);
    }

    /**
     * 回环全端口扫描应找出真实监听端口，且 RST 路径不得被限速拖到数分钟。
     */
    @Test(timeout = 30_000)
    public void scanPorts_loopbackFullRangeFindsListenerQuickly() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        try {
            int port = server.getLocalPort();
            long start = System.currentTimeMillis();
            List<Integer> found = PortsUtils.scanPorts("127.0.0.1", "1-65535", 200, 64);
            System.out.println(found);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("本机全端口应找出监听端口 " + port + "，实际=" + found, found.contains(port));
            assertAscending(found);
            assertTrue("本机全端口应在数秒内完成，实际=" + elapsed + "ms", elapsed < 15_000);
        } finally {
            server.close();
        }
    }

    /**
     * 端口数量超过单分片阈值时会走多线程分片路径，结果仍须完整且有序。
     */
    @Test
    public void scanPorts_shardedScanKeepsResultsCompleteAndSorted() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        try {
            int port = server.getLocalPort();
            // 跨度大于 MIN_PORTS_PER_WORKER，确保触发分片
            int from = Math.max(1, port - 800);
            int to = Math.min(65535, port + 800);
            List<Integer> found = PortsUtils.scanPorts("127.0.0.1", from + "-" + to, 200, 512);

            assertTrue("分片扫描不应丢失开放端口", found.contains(port));
            assertAscending(found);
        } finally {
            server.close();
        }
    }

    /**
     * 非法的超时时间应被拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void scanPorts_rejectsNonPositiveTimeout() {
        PortsUtils.scanPorts("127.0.0.1", "80", 0, 16);
    }

    /**
     * 非法的在途连接上限应被拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void scanPorts_rejectsNonPositiveMaxInFlight() {
        PortsUtils.scanPorts("127.0.0.1", "80", 200, 0);
    }

    /**
     * 无法解析的主机应直接报错，而不是把每个端口都探测一遍再返回空列表。
     */
    @Test(expected = IllegalArgumentException.class)
    public void scanPorts_rejectsUnknownHost() {
        PortsUtils.scanPorts("jkit-a-host-that-does-not-exist.invalid", "80", 200, 16);
    }

    /**
     * 已废弃的线程池重载仍应返回正确结果，且不得关闭调用方传入的线程池。
     */
    @Test
    @SuppressWarnings("deprecation")
    public void scanPorts_deprecatedOverloadDoesNotShutdownCallerPool() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int port = server.getLocalPort();
            List<Integer> found = PortsUtils.scanPorts("127.0.0.1", String.valueOf(port), pool);
            assertTrue(found.contains(port));
            assertFalse("不应关闭调用方传入的线程池", pool.isShutdown());
        } finally {
            pool.shutdownNow();
            server.close();
        }
    }

    /**
     * isOpen 对监听中的端口返回 true，对已关闭的端口返回 false。
     */
    @Test
    public void isOpen_reflectsActualPortState() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        int openPort = server.getLocalPort();
        try {
            assertTrue(PortsUtils.isOpen("127.0.0.1", openPort));
        } finally {
            server.close();
        }
        assertFalse(PortsUtils.isOpen("127.0.0.1", openPort, 300));
    }

    /**
     * 主机无法解析时 isOpen 返回 false 而不是抛异常。
     */
    @Test
    public void isOpen_returnsFalseForUnknownHost() {
        assertFalse(PortsUtils.isOpen("jkit-a-host-that-does-not-exist.invalid", 80, 200));
    }

    /**
     * RFC 2544 基准测试网段以及 Clash 默认 IPv6 Fake-IP 前缀都应被识别。
     */
    @Test
    public void isFakeIp_recognizesClashRanges() throws Exception {
        assertTrue(PortsUtils.isFakeIp(InetAddress.getByName("198.18.0.1")));
        assertTrue(PortsUtils.isFakeIp(InetAddress.getByName("198.19.255.255")));
        assertFalse(PortsUtils.isFakeIp(InetAddress.getByName("198.17.0.1")));
        assertFalse(PortsUtils.isFakeIp(InetAddress.getByName("8.8.8.8")));
        assertTrue(PortsUtils.isFakeIp(InetAddress.getByName("fdfe:dcba:9876::d6")));
        assertFalse(PortsUtils.isFakeIp(InetAddress.getByName("2001:4860:4860::8888")));
    }

    /**
     * 系统解析同时返回 Fake-IP 与真实地址时，应优先使用真实 IPv4。
     */
    @Test
    public void preferRealAddress_skipsFakeIp() throws Exception {
        InetAddress fake4 = InetAddress.getByName("198.18.1.10");
        InetAddress real4 = InetAddress.getByName("8.8.8.8");
        InetAddress real6 = InetAddress.getByName("2001:4860:4860::8888");
        assertEquals(real4, PortsUtils.preferRealAddress(new InetAddress[]{fake4, real6, real4}));
        assertEquals(real6, PortsUtils.preferRealAddress(new InetAddress[]{fake4, real6}));
        assertEquals(fake4, PortsUtils.preferRealAddress(new InetAddress[]{fake4}));
        assertNull(PortsUtils.preferRealAddress(new InetAddress[0]));
    }

    /**
     * 透明代理的默认路由应被跳过，更具体的直连网段则保留。
     */
    @Test
    public void shouldSkipRoute_skipsTunnelDefaultOnly() {
        assertTrue(PortsUtils.isTunnelLikeInterface("Meta"));
        assertTrue(PortsUtils.isTunnelLikeInterface("tun0"));
        assertTrue(PortsUtils.isTunnelLikeInterface("utun3"));
        assertTrue(PortsUtils.shouldSkipRoute("tun0", 0));
        assertFalse(PortsUtils.isTunnelLikeInterface("wlo1"));
        assertFalse(PortsUtils.shouldSkipRoute("wlo1", 0));
        assertFalse(PortsUtils.shouldSkipRoute("tun0", 24));
    }

    /**
     * 外网目标应匹配真实默认路由，而不是 Clash TUN 上的 Fake-IP 默认路由。
     */
    @Test
    public void matchIpv4Route_skipsFakeDefaultWhenRequested() throws Exception {
        List<String> lines = Arrays.asList(
                "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT",
                "Meta\t000012C6\t00000000\t0001\t0\t0\t0\tFCFFFFFF\t0\t0\t0",
                "wlo1\t00000000\t0101A8C0\t0003\t0\t0\t20600\t00000000\t0\t0\t0",
                "tun0\t00000000\t0100080A\t0003\t0\t0\t0\t00000000\t0\t0\t0"
        );
        List<PortsUtils.Ipv4Route> routes = PortsUtils.parseIpv4Routes(lines);
        int target = PortsUtils.ipv4ToInt((Inet4Address) InetAddress.getByName("8.8.8.8"));
        PortsUtils.Ipv4Route withProxy = PortsUtils.matchIpv4Route(target, routes, false);
        PortsUtils.Ipv4Route withoutProxy = PortsUtils.matchIpv4Route(target, routes, true);
        assertNotNull(withProxy);
        assertEquals("tun0", withProxy.iface);
        assertNotNull(withoutProxy);
        assertEquals("wlo1", withoutProxy.iface);
    }

    /**
     * resolv.conf 里的 stub / Fake-IP nameserver 不能再拿来做直连解析。
     */
    @Test
    public void parseNameservers_skipsStubAndFakeIp() {
        List<InetAddress> servers = PortsUtils.parseNameservers(Arrays.asList(
                "# comment",
                "nameserver 127.0.0.53",
                "nameserver 198.18.0.2",
                "nameserver 192.168.1.1 extra",
                "options edns0"
        ));
        assertEquals(Collections.singletonList("192.168.1.1"), toHostAddresses(servers));
    }

    /**
     * 最小 A 记录应答应能解析出地址。
     */
    @Test
    public void parseDnsAnswers_readsARecord() throws Exception {
        byte[] query = PortsUtils.encodeDnsQuery("example.com", 1);
        assertNotNull(query);
        // header(12) + example.com(13) + type/class(4) = 29
        assertEquals(29, query.length);
        byte[] response = new byte[query.length + 16];
        System.arraycopy(query, 0, response, 0, query.length);
        response[2] = (byte) 0x81;
        response[3] = (byte) 0x80;
        response[6] = 0;
        response[7] = 1;
        int pos = query.length;
        response[pos++] = (byte) 0xc0;
        response[pos++] = 0x0c;
        response[pos++] = 0x00;
        response[pos++] = 0x01;
        response[pos++] = 0x00;
        response[pos++] = 0x01;
        response[pos++] = 0x00;
        response[pos++] = 0x00;
        response[pos++] = 0x00;
        response[pos++] = 0x3c;
        response[pos++] = 0x00;
        response[pos++] = 0x04;
        response[pos++] = 93;
        response[pos++] = (byte) 184;
        response[pos++] = (byte) 216;
        response[pos] = 34;
        List<InetAddress> answers = PortsUtils.parseDnsAnswers(response, response.length, 1);
        assertEquals(Collections.singletonList(InetAddress.getByName("93.184.216.34")), answers);
    }

    private static void printScan(String host) {
        long start = System.currentTimeMillis();
        List<Integer> found = PortsUtils.scanPorts(host, "1-65535", 500, 64);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("host=" + host + " elapsed=" + elapsed + "ms open=" + found.size() + " " + found);
    }

    private static List<String> toHostAddresses(List<InetAddress> addresses) {
        List<String> hosts = new ArrayList<String>(addresses.size());
        for (int i = 0; i < addresses.size(); i++) {
            hosts.add(addresses.get(i).getHostAddress());
        }
        return hosts;
    }

    private static void assertAscending(List<Integer> ports) {
        for (int i = 1; i < ports.size(); i++) {
            assertTrue("扫描结果应按端口号升序：" + ports,
                    ports.get(i - 1).intValue() < ports.get(i).intValue());
        }
    }

    private static TreeSet<Integer> missing(TreeSet<Integer> expected, List<Integer> found) {
        TreeSet<Integer> rest = new TreeSet<Integer>(expected);
        rest.removeAll(found);
        return rest;
    }

    private static void closeAll(List<ServerSocket> servers) {
        for (ServerSocket server : servers) {
            try {
                server.close();
            } catch (IOException e) {
                // 测试收尾阶段，关闭失败不影响断言结果
                System.err.println("close server socket failed: " + e.getMessage());
            }
        }
    }
}
