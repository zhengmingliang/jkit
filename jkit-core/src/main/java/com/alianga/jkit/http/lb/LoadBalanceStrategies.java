package com.alianga.jkit.http.lb;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置负载均衡策略集合。
 * <p>
 * 选型建议：
 * <ul>
 *   <li>{@link #p2cLeastLoaded()}（默认推荐）：随机取两个候选比"在途数 / 权重"，
 *       O(1) 且比全量扫描的最少连接更不容易产生羊群效应；上游实例性能不均时表现最好。</li>
 *   <li>{@link #smoothWeightedRoundRobin()}：nginx 的平滑加权轮询。权重 3:1 时
 *       输出 {@code a b a a} 而不是取模式的 {@code a a a b}，流量更平顺。</li>
 *   <li>{@link #weightedRandom()}：最省状态，适合端点很多、请求量很大的场景。</li>
 *   <li>{@link #consistentHash()}：按会话键做粘性路由（带虚拟节点），
 *       适合上游有本地缓存 / 会话状态的场景。</li>
 * </ul>
 *
 * @author 郑明亮
 */
public final class LoadBalanceStrategies {
    private LoadBalanceStrategies() {
    }

    /**
     * @return nginx 风格平滑加权轮询
     */
    public static LoadBalanceStrategy smoothWeightedRoundRobin() {
        return new SmoothWeightedRoundRobin();
    }

    /**
     * @return 加权随机
     */
    public static LoadBalanceStrategy weightedRandom() {
        return new WeightedRandom();
    }

    /**
     * @return 二选一最小负载（power of two choices），按在途数 / 权重比较
     */
    public static LoadBalanceStrategy p2cLeastLoaded() {
        return new P2CLeastLoaded(false);
    }

    /**
     * 二选一最小负载，代价里额外计入 EWMA 延迟，能自动避开"能连上但很慢"的实例。
     *
     * @return 策略
     */
    public static LoadBalanceStrategy p2cPeakEwma() {
        return new P2CLeastLoaded(true);
    }

    /**
     * @return 一致性哈希（每个端点 160 个虚拟节点）
     */
    public static LoadBalanceStrategy consistentHash() {
        return new ConsistentHash(160);
    }

    /**
     * 一致性哈希，可指定虚拟节点数。
     *
     * @param virtualNodes 每个端点的虚拟节点数，越大分布越均匀
     * @return 策略
     */
    public static LoadBalanceStrategy consistentHash(int virtualNodes) {
        return new ConsistentHash(virtualNodes);
    }

    /**
     * nginx 平滑加权轮询：每轮给所有候选的 current 加上自身权重，取 current 最大者，
     * 再把选中者的 current 减去总权重。相比"计数取模"，同样的权重比例下输出更平顺。
     */
    static final class SmoothWeightedRoundRobin implements LoadBalanceStrategy {
        private final Object lock = new Object();

        @Override
        public String name() {
            return "smooth-weighted-round-robin";
        }

        @Override
        public Endpoint select(List<Endpoint> candidates, String hashKey) {
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            synchronized (lock) {
                long total = 0L;
                Endpoint best = null;
                int bestCurrent = Integer.MIN_VALUE;
                for (Endpoint endpoint : candidates) {
                    AtomicInteger current = endpoint.currentWeightRef();
                    int updated = current.addAndGet(endpoint.getWeight());
                    total += endpoint.getWeight();
                    if (best == null || updated > bestCurrent) {
                        best = endpoint;
                        bestCurrent = updated;
                    }
                }
                if (best == null) {
                    return candidates.get(0);
                }
                // total 受 Endpoint.MAX_WEIGHT 与候选数约束，int 足够
                best.currentWeightRef().addAndGet(-(int) Math.min(Integer.MAX_VALUE, total));
                return best;
            }
        }
    }

    /**
     * 加权随机。用 long 累加权重，避免权重求和溢出后 {@code nextInt(bound)} 抛异常。
     */
    static final class WeightedRandom implements LoadBalanceStrategy {
        @Override
        public String name() {
            return "weighted-random";
        }

        @Override
        public Endpoint select(List<Endpoint> candidates, String hashKey) {
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            long total = 0L;
            for (Endpoint endpoint : candidates) {
                total += endpoint.getWeight();
            }
            if (total <= 0L) {
                return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            }
            long target = ThreadLocalRandom.current().nextLong(total);
            long accumulated = 0L;
            for (Endpoint endpoint : candidates) {
                accumulated += endpoint.getWeight();
                if (target < accumulated) {
                    return endpoint;
                }
            }
            return candidates.get(candidates.size() - 1);
        }
    }

    /**
     * Power of two choices：随机抽两个不同候选，取代价小的那个。
     * <p>
     * 相比遍历全部端点取最小值，P2C 是 O(1)，而且在多客户端并发时不会所有人
     * 同时涌向同一个"当前最闲"的节点（羊群效应）。
     */
    static final class P2CLeastLoaded implements LoadBalanceStrategy {
        private final boolean useLatency;

        P2CLeastLoaded(boolean useLatency) {
            this.useLatency = useLatency;
        }

        @Override
        public String name() {
            return useLatency ? "p2c-peak-ewma" : "p2c-least-loaded";
        }

        @Override
        public Endpoint select(List<Endpoint> candidates, String hashKey) {
            int size = candidates.size();
            if (size == 1) {
                return candidates.get(0);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int first = random.nextInt(size);
            int second = random.nextInt(size - 1);
            if (second >= first) {
                second++;
            }
            Endpoint a = candidates.get(first);
            Endpoint b = candidates.get(second);
            double costA = cost(a);
            double costB = cost(b);
            if (costA == costB) {
                return random.nextBoolean() ? a : b;
            }
            return costA < costB ? a : b;
        }

        private double cost(Endpoint endpoint) {
            double load = (endpoint.getInFlight() + 1.0d) / endpoint.getWeight();
            if (!useLatency) {
                return load;
            }
            // 无延迟样本时按 1ms 估算，避免新节点被永久冷落或永久优先
            double latency = endpoint.getEwmaLatencyMs();
            return load * (latency <= 0.0d ? 1.0d : latency);
        }
    }

    /**
     * 一致性哈希环，带虚拟节点。候选集合变化时重建环（按候选签名缓存）。
     */
    static final class ConsistentHash implements LoadBalanceStrategy {
        private final int virtualNodes;
        private final Object lock = new Object();
        private String cachedSignature;
        private SortedMap<Long, Endpoint> cachedRing;

        ConsistentHash(int virtualNodes) {
            this.virtualNodes = Math.max(1, virtualNodes);
        }

        @Override
        public String name() {
            return "consistent-hash";
        }

        @Override
        public Endpoint select(List<Endpoint> candidates, String hashKey) {
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            if (hashKey == null || hashKey.isEmpty()) {
                // 没有亲和键时退化为加权随机，而不是固定打向同一个端点
                return new WeightedRandom().select(candidates, null);
            }
            SortedMap<Long, Endpoint> ring = ringFor(candidates);
            if (ring.isEmpty()) {
                return candidates.get(0);
            }
            long hash = hash64(hashKey);
            SortedMap<Long, Endpoint> tail = ring.tailMap(hash);
            Long key = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
            return ring.get(key);
        }

        private SortedMap<Long, Endpoint> ringFor(List<Endpoint> candidates) {
            String signature = signatureOf(candidates);
            synchronized (lock) {
                if (signature.equals(cachedSignature) && cachedRing != null) {
                    return cachedRing;
                }
                TreeMap<Long, Endpoint> ring = new TreeMap<Long, Endpoint>();
                for (Endpoint endpoint : candidates) {
                    // 虚拟节点数按权重放大，权重高的端点占更多环位
                    int nodes = (int) Math.min(20_000L, (long) virtualNodes * endpoint.getWeight());
                    for (int i = 0; i < nodes; i++) {
                        ring.put(hash64(endpoint.getBaseUrl() + "#" + i), endpoint);
                    }
                }
                cachedSignature = signature;
                cachedRing = Collections.unmodifiableSortedMap(ring);
                return cachedRing;
            }
        }

        private static String signatureOf(List<Endpoint> candidates) {
            List<String> names = new ArrayList<String>(candidates.size());
            for (Endpoint endpoint : candidates) {
                names.add(endpoint.getBaseUrl() + ":" + endpoint.getWeight());
            }
            Collections.sort(names);
            return names.toString();
        }

        /**
         * FNV-1a 64 位哈希 + murmur3 尾混淆。
         * <p>
         * 单纯的 FNV-1a 对 {@code node#0}、{@code node#1} 这类只差一个字符的输入雪崩性很差，
         * 虚拟节点会在环上聚成一团，导致某些端点长时间拿不到流量。加一层尾混淆后分布明显均匀。
         */
        static long hash64(String value) {
            long hash = 0xcbf29ce484222325L;
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                hash ^= b & 0xFF;
                hash *= 0x100000001b3L;
            }
            hash ^= hash >>> 33;
            hash *= 0xff51afd7ed558ccdL;
            hash ^= hash >>> 33;
            hash *= 0xc4ceb9fe1a85ec53L;
            hash ^= hash >>> 33;
            return hash & Long.MAX_VALUE;
        }
    }
}
