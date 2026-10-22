package com.alianga.jkit.storage.engine.cmcloud;

import java.util.Arrays;

/**
 * 纯 Java 二维码编码器（授权入口短链用）：字节模式、纠错级别 M、版本 1~10、
 * 掩码 0~7 按标准惩罚规则自动择优。不引入任何第三方二维码库。
 *
 * <p>实现覆盖 QR 规范的完整链路：数据编码 → 纠错码（GF(256) Reed-Solomon，本原多项式
 * 0x11D）→ 分块交织 → 矩阵布置（finder / timing / alignment / dark module /
 * format &amp; version 信息）→ 掩码与惩罚评分。矩阵外圈静区由渲染方补（默认 4 模块）。</p>
 *
 * @author 郑明亮
 */
public final class QrEncoder {
    /** 纠错级别 M 的指示位（L=01, M=00, Q=11, H=10）。 */
    private static final int EC_LEVEL_BITS = 0b00;

    /**
     * 版本 1~10、级别 M 分块表：{总码字, 数据码字, 短块数, 短块数据码字,
     * 长块数, 长块数据码字, 每块纠错码字}。与 QR 规范错误纠正特性表一致，
     * 任何一个数改错都会导致无法解码。
     */
    private static final int[][] EC_M = {
            {26, 16, 1, 16, 0, 0, 10},          // v1
            {44, 28, 1, 28, 0, 0, 16},          // v2
            {70, 44, 1, 44, 0, 0, 26},          // v3
            {100, 64, 2, 32, 0, 0, 18},         // v4
            {134, 86, 2, 43, 0, 0, 24},         // v5
            {172, 108, 4, 27, 0, 0, 16},        // v6
            {196, 124, 4, 31, 0, 0, 18},        // v7
            {242, 154, 2, 38, 2, 39, 22},       // v8
            {292, 182, 3, 36, 2, 37, 22},       // v9
            {346, 216, 4, 43, 1, 44, 26},       // v10
    };

    /** 各版本 alignment 图案中心坐标（v1 无）。 */
    private static final int[][] ALIGNMENT = {
            {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
    };

    /** 各版本的剩余位（数据 + 纠错码字放完之后补 0）。 */
    private static final int[] REMAINDER_BITS = {0, 7, 7, 7, 7, 7, 0, 0, 0, 0};

    // GF(256) 对数表（生成元 2，本原多项式 0x11D），Reed-Solomon 与 BCH 都要用
    private static final int[] EXP_TABLE = new int[512];
    private static final int[] LOG_TABLE = new int[256];

    static {
        int value = 1;
        for (int i = 0; i < 255; i++) {
            EXP_TABLE[i] = value;
            LOG_TABLE[value] = i;
            value <<= 1;
            if (value >= 256) {
                value ^= 0x11D;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP_TABLE[i] = EXP_TABLE[i - 255];
        }
    }

    private final int version;
    private final int size;
    private final boolean[][] matrix;
    private final boolean[][] reserved;

    private QrEncoder(int version) {
        this.version = version;
        this.size = version * 4 + 17;
        this.matrix = new boolean[size][size];
        this.reserved = new boolean[size][size];
    }

    /**
     * 编码为二维码矩阵（true = 深色模块）。自动选版本与掩码。
     *
     * @param content 内容字节（UTF-8）
     * @return size × size 矩阵（不含静区）
     * @throws IllegalArgumentException 内容超出 v10-M 容量
     */
    public static boolean[][] encode(byte[] content) {
        for (int version = 1; version <= 10; version++) {
            if (dataCapacityBytes(version) >= content.length) {
                return encode(content, version, bestMask(content, version));
            }
        }
        throw new IllegalArgumentException("内容超出二维码容量（版本 10 / 级别 M 上限 "
                + dataCapacityBytes(10) + " 字节）");
    }

    /** 指定版本与掩码编码（测试与掩码择优复用）。 */
    public static boolean[][] encode(byte[] content, int version, int mask) {
        return new QrEncoder(version).assemble(content, mask);
    }

    /** 各版本字节容量（数据码字 - 模式头 4 bit - 计数 8/16 bit）。 */
    public static int dataCapacityBytes(int version) {
        int dataCodewords = EC_M[version - 1][1];
        int headerBits = 4 + (version < 10 ? 8 : 16);
        return (dataCodewords * 8 - headerBits) / 8;
    }

    // ---------------------------------------------------------------- 组装

    private boolean[][] assemble(byte[] content, int mask) {
        int[] spec = EC_M[version - 1];
        byte[] data = buildData(content, spec[1]);
        byte[][] blocks = splitBlocks(data, spec);
        byte[] dataSequence = interleaveData(blocks);
        byte[] ecSequence = interleaveEc(blocks, spec[6]);
        placeFunctionPatterns();
        placeData(dataSequence, ecSequence);
        applyMask(mask);
        drawFormat(mask);
        if (version >= 7) {
            drawVersionInfo();
        }
        return matrix;
    }

    // ---------------------------------------------------------------- 数据编码

    /** 模式指示 + 计数 + 数据 + 终止符 + 补位 → 恰好 spec 数据码字数。 */
    private byte[] buildData(byte[] content, int dataCodewords) {
        int countBits = version < 10 ? 8 : 16;
        int totalBits = dataCodewords * 8;
        boolean[] bits = new boolean[totalBits];
        int at = 0;
        at = writeBits(bits, at, 0b0100, 4);                       // byte mode
        at = writeBits(bits, at, content.length, countBits);
        for (byte b : content) {
            at = writeBits(bits, at, b & 0xFF, 8);
        }
        // 终止符（最多 4 个 0）
        at = writeBits(bits, at, 0, Math.min(4, totalBits - at));
        // 补齐到字节边界，再交替补 0xEC / 0x11
        int pad = 0xEC;
        while (at < totalBits) {
            int width = Math.min(8, totalBits - at);
            at = writeBits(bits, at, pad >> (8 - width), width);
            pad = pad == 0xEC ? 0x11 : 0xEC;
        }
        byte[] out = new byte[dataCodewords];
        for (int i = 0; i < dataCodewords; i++) {
            int value = 0;
            for (int bit = 0; bit < 8; bit++) {
                value = (value << 1) | (bits[i * 8 + bit] ? 1 : 0);
            }
            out[i] = (byte) value;
        }
        return out;
    }

    private static int writeBits(boolean[] bits, int at, int value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            bits[at++] = ((value >> i) & 1) == 1;
        }
        return at;
    }

    // ---------------------------------------------------------------- 纠错与交织

    private static byte[][] splitBlocks(byte[] data, int[] spec) {
        int blocks = spec[2] + spec[4];
        byte[][] result = new byte[blocks][];
        int offset = 0;
        for (int i = 0; i < spec[2]; i++) {
            result[i] = Arrays.copyOfRange(data, offset, offset + spec[3]);
            offset += spec[3];
        }
        for (int i = 0; i < spec[4]; i++) {
            result[spec[2] + i] = Arrays.copyOfRange(data, offset, offset + spec[5]);
            offset += spec[5];
        }
        return result;
    }

    private static byte[] interleaveData(byte[][] blocks) {
        int total = 0;
        for (byte[] block : blocks) {
            total += block.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        int longest = blocks[blocks.length - 1].length;
        for (int round = 0; round < longest; round++) {
            for (byte[] block : blocks) {
                if (round < block.length) {
                    out[at++] = block[round];
                }
            }
        }
        return out;
    }

    private static byte[] interleaveEc(byte[][] blocks, int ecPerBlock) {
        byte[] out = new byte[ecPerBlock * blocks.length];
        int at = 0;
        for (int round = 0; round < ecPerBlock; round++) {
            for (byte[] block : blocks) {
                out[at++] = reedSolomon(block, ecPerBlock)[round];
            }
        }
        return out;
    }

    /** GF(256) Reed-Solomon：生成多项式除法取余数（综合除法，逐字节移入）。 */
    private static byte[] reedSolomon(byte[] data, int ecLen) {
        int[] generator = generatorPoly(ecLen);
        int[] remainder = new int[ecLen];
        for (byte item : data) {
            int factor = (item & 0xFF) ^ remainder[0];
            System.arraycopy(remainder, 1, remainder, 0, ecLen - 1);
            remainder[ecLen - 1] = 0;
            if (factor != 0) {
                int logFactor = LOG_TABLE[factor];
                for (int i = 0; i < ecLen; i++) {
                    if (generator[i + 1] != 0) {
                        remainder[i] ^= EXP_TABLE[(LOG_TABLE[generator[i + 1]] + logFactor) % 255];
                    }
                }
            }
        }
        byte[] out = new byte[ecLen];
        for (int i = 0; i < ecLen; i++) {
            out[i] = (byte) remainder[i];
        }
        return out;
    }

    /**
     * 生成多项式 (x-α⁰)(x-α¹)…(x-α^(n-1)) 系数：result[j] 为 x^(n-j) 的系数
     * （result[0] 恒为 1）。逐次乘 (x + α^i) 就地展开，系数在 GF(256) 上异或。
     */
    private static int[] generatorPoly(int degree) {
        int[] result = new int[degree + 1];
        result[0] = 1;
        int currentDegree = 0;
        for (int i = 0; i < degree; i++) {
            int newDegree = currentDegree + 1;
            // 降序就地更新：newpoly[j] = poly[j] ^ α^i * poly[j-1]
            for (int j = newDegree; j >= 1; j--) {
                if (result[j - 1] != 0) {
                    result[j] ^= EXP_TABLE[(LOG_TABLE[result[j - 1]] + i) % 255];
                }
            }
            // 首项系数（result[0]）只接收 x 平移部分，α 部分越界丢弃，保持不变
            currentDegree = newDegree;
        }
        return result;
    }

    // ---------------------------------------------------------------- 函数图案

    private void placeFunctionPatterns() {
        // finder + 分隔（三个角，含外圈一圈浅色）
        int[][] corners = {{0, 0}, {size - 7, 0}, {0, size - 7}};
        for (int[] corner : corners) {
            int baseRow = corner[0];
            int baseCol = corner[1];
            for (int i = -1; i <= 7; i++) {
                for (int j = -1; j <= 7; j++) {
                    int row = baseRow + i;
                    int col = baseCol + j;
                    if (row < 0 || row >= size || col < 0 || col >= size) {
                        continue;
                    }
                    boolean dark = i >= 0 && i <= 6 && j >= 0 && j <= 6
                            && (i == 0 || i == 6 || j == 0 || j == 6
                            || (i >= 2 && i <= 4 && j >= 2 && j <= 4));
                    matrix[row][col] = dark;
                    reserved[row][col] = true;
                }
            }
        }

        // timing（行 6 / 列 6）
        for (int i = 8; i < size - 8; i++) {
            boolean dark = i % 2 == 0;
            matrix[6][i] = dark;
            reserved[6][i] = true;
            matrix[i][6] = dark;
            reserved[i][6] = true;
        }

        // alignment（与 finder 重叠的跳过；与 timing 重叠的按自身图案覆盖，节拍一致）
        int[] centers = ALIGNMENT[version - 1];
        for (int row : centers) {
            for (int col : centers) {
                if (nearFinder(row, col)) {
                    continue;
                }
                for (int i = -2; i <= 2; i++) {
                    for (int j = -2; j <= 2; j++) {
                        matrix[row + i][col + j] = Math.max(Math.abs(i), Math.abs(j)) != 1;
                        reserved[row + i][col + j] = true;
                    }
                }
            }
        }

        // format 信息区预留 + dark module（行 8、列 size-8）
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                reserved[8][i] = true;
                reserved[i][8] = true;
            }
        }
        for (int i = 0; i < 8; i++) {
            reserved[size - 1 - i][8] = true;
        }
        for (int i = 0; i <= 7; i++) {
            reserved[8][size - 1 - i] = true;
        }
        // dark module（左下 finder 右上角：行 size-8、列 8，恒为深色）
        matrix[size - 8][8] = true;
        reserved[size - 8][8] = true;

        // version 信息区预留（v≥7）
        if (version >= 7) {
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 3; j++) {
                    reserved[size - 11 + j][i] = true;
                    reserved[i][size - 11 + j] = true;
                }
            }
        }
    }

    /** alignment 中心是否落在三个 finder 的 8×8 邻域（重叠即跳过）。 */
    private boolean nearFinder(int row, int col) {
        return (row <= 8 && col <= 8) || (row <= 8 && col >= size - 9)
                || (row >= size - 9 && col <= 8);
    }

    // ---------------------------------------------------------------- 数据布置

    private void placeData(byte[] data, byte[] ec) {
        boolean[] stream = new boolean[(data.length + ec.length) * 8 + REMAINDER_BITS[version - 1]];
        int at = 0;
        for (byte b : data) {
            at = writeBits(stream, at, b & 0xFF, 8);
        }
        for (byte b : ec) {
            at = writeBits(stream, at, b & 0xFF, 8);
        }
        int bitAt = 0;
        int col = size - 1;
        boolean upward = true;
        while (col > 0) {
            if (col == 6) {
                col--; // 跳过 timing 列
            }
            for (int step = 0; step < size; step++) {
                int row = upward ? size - 1 - step : step;
                for (int offset = 0; offset < 2; offset++) {
                    int c = col - offset;
                    if (reserved[row][c]) {
                        continue;
                    }
                    matrix[row][c] = bitAt < stream.length && stream[bitAt];
                    bitAt++;
                }
            }
            upward = !upward;
            col -= 2;
        }
    }

    // ---------------------------------------------------------------- 掩码与格式信息

    private static boolean maskCondition(int mask, int row, int col) {
        switch (mask) {
            case 0:
                return (row + col) % 2 == 0;
            case 1:
                return row % 2 == 0;
            case 2:
                return col % 3 == 0;
            case 3:
                return (row + col) % 3 == 0;
            case 4:
                return (row / 2 + col / 3) % 2 == 0;
            case 5:
                return (row * col) % 2 + (row * col) % 3 == 0;
            case 6:
                return ((row * col) % 2 + (row * col) % 3) % 2 == 0;
            default:
                return ((row + col) % 2 + (row * col) % 3) % 2 == 0;
        }
    }

    private void applyMask(int mask) {
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (!reserved[row][col] && maskCondition(mask, row, col)) {
                    matrix[row][col] = !matrix[row][col];
                }
            }
        }
    }

    /**
     * 格式信息：15 位 = 5 数据位（级别 + 掩码）BCH(15,5) 后异或 0x5412，两份布置。
     * 位置（row, col）与规范一致：
     * 第一份 bits0-5→(i,8)、bit6→(7,8)、bit7→(8,8)、bit8→(8,7)、bits9-14→(8,14-i)；
     * 第二份 bits0-7→(8,size-1-i)、bits8-14→(size-15+i,8)。
     */
    private void drawFormat(int mask) {
        int data = (EC_LEVEL_BITS << 3) | mask;
        int remainder = data << 10;
        for (int i = 14; i >= 10; i--) {
            if (((remainder >> i) & 1) == 1) {
                remainder ^= 0x537 << (i - 10);
            }
        }
        int bits = ((data << 10) | remainder) ^ 0x5412;
        for (int i = 0; i < 15; i++) {
            boolean bit = ((bits >> i) & 1) == 1;
            if (i < 6) {
                matrix[i][8] = bit;
            } else if (i == 6) {
                matrix[7][8] = bit;
            } else if (i == 7) {
                matrix[8][8] = bit;
            } else if (i == 8) {
                matrix[8][7] = bit;
            } else {
                matrix[8][14 - i] = bit;
            }
            if (i < 8) {
                matrix[8][size - 1 - i] = bit;
            } else {
                matrix[size - 15 + i][8] = bit;
            }
        }
    }

    /** 版本信息（v≥7）：6 位版本号 BCH(18,6)（生成多项式 0x1F25），两份对称布置。 */
    private void drawVersionInfo() {
        int remainder = version << 12;
        for (int i = 17; i >= 12; i--) {
            if (((remainder >> i) & 1) == 1) {
                remainder ^= 0x1F25 << (i - 12);
            }
        }
        int bits = (version << 12) | remainder;
        for (int i = 0; i < 18; i++) {
            boolean bit = ((bits >> i) & 1) == 1;
            int a = size - 11 + i % 3;
            int b = i / 3;
            matrix[b][a] = bit;
            matrix[a][b] = bit;
        }
    }

    // ---------------------------------------------------------------- 掩码择优

    private static int bestMask(byte[] content, int version) {
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            long penalty = penalty(encode(content, version, mask));
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = mask;
            }
        }
        return best;
    }

    /** 标准四条惩罚规则（N1 行列连续 / N2 2×2 块 / N3 类 finder 图案 / N4 深色占比）。 */
    private static long penalty(boolean[][] m) {
        int size = m.length;
        long penalty = 0;
        for (int axis = 0; axis < 2; axis++) {
            for (int line = 0; line < size; line++) {
                int run = 1;
                for (int j = 1; j < size; j++) {
                    boolean prev = axis == 0 ? m[line][j - 1] : m[j - 1][line];
                    boolean cur = axis == 0 ? m[line][j] : m[j][line];
                    if (cur == prev) {
                        run++;
                        if (j == size - 1 && run >= 5) {
                            penalty += 3 + (run - 5);
                        }
                    } else {
                        if (run >= 5) {
                            penalty += 3 + (run - 5);
                        }
                        run = 1;
                    }
                }
            }
        }
        for (int i = 0; i < size - 1; i++) {
            for (int j = 0; j < size - 1; j++) {
                if (m[i][j] == m[i][j + 1] && m[i][j] == m[i + 1][j]
                        && m[i][j] == m[i + 1][j + 1]) {
                    penalty += 3;
                }
            }
        }
        int[] pattern = {1, 0, 1, 1, 1, 0, 1};
        for (int axis = 0; axis < 2; axis++) {
            for (int line = 0; line < size; line++) {
                for (int start = 0; start <= size - pattern.length; start++) {
                    if (matchesPattern(m, axis, line, start, pattern)) {
                        penalty += 40;
                    }
                }
            }
        }
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean cell : row) {
                if (cell) {
                    dark++;
                }
            }
        }
        penalty += Math.abs(dark * 20 - size * size * 10) * 10 / (size * size);
        return penalty;
    }

    private static boolean matchesPattern(boolean[][] m, int axis, int line, int start,
                                          int[] pattern) {
        for (int k = 0; k < pattern.length; k++) {
            boolean dark = axis == 0 ? m[line][start + k] : m[start + k][line];
            if (dark != (pattern[k] == 1)) {
                return false;
            }
        }
        return true;
    }
}
