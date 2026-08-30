package com.alianga.jkit;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link PortsUtils} 的端口规则解析与扫描行为测试。
 *
 * <p>全部用例只探测 127.0.0.1，不依赖外部网络。</p>
 */
public class PortsUtilsTest {

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
