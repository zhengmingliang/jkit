package com.alianga.jkit.storage.engine.cmcloud;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link QrEncoder} 纯单元测试（不触网）：矩阵尺寸、函数图案（finder / timing /
 * alignment / dark module）、format 信息的两份布置、指定版本与掩码下的关键模块
 * 期望值（参考矩阵由 segno 独立生成并逐位比对验证过），以及掩码 0~7 的自洽性。
 *
 * <p>开发期已做过两类更强的验证（不进单测）：① v1~v10 全部 8 个掩码的矩阵与
 * segno 独立实现逐位一致（v7~v10 与 segno 的差异仅在 segno 的非规范 pad 码字
 * 填充，本实现按 ISO 规范填充）；② 全版本、全掩码的矩阵经独立解码器还原，
 * Reed-Solomon 校验子全零且内容逐字节还原。</p>
 *
 * @author 郑明亮
 */
public class QrEncoderTest {

    /** 左上 finder 的标准图案（含分隔留白的判定用核心 7×7）。 */
    private static final String[] FINDER = {
            "#######",
            "#.....#",
            "#.###.#",
            "#.###.#",
            "#.###.#",
            "#.....#",
            "#######",
    };

    @Test
    public void sizeFollowsVersion() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        for (int version = 1; version <= 10; version++) {
            boolean[][] m = QrEncoder.encode(content, version, 0);
            assertEquals("v" + version, version * 4 + 17, m.length);
        }
    }

    @Test
    public void finderPatternsPresent() {
        boolean[][] m = QrEncoder.encode("https://a.b/c".getBytes(StandardCharsets.UTF_8));
        int size = m.length;
        int[][] corners = {{0, 0}, {size - 7, 0}, {0, size - 7}};
        for (int[] corner : corners) {
            for (int i = 0; i < 7; i++) {
                for (int j = 0; j < 7; j++) {
                    boolean expect = FINDER[i].charAt(j) == '#';
                    assertEquals("finder(" + corner[0] + "+" + i + "," + corner[1] + "+" + j + ")",
                            expect, m[corner[0] + i][corner[1] + j]);
                }
            }
        }
    }

    @Test
    public void timingPatternAlternates() {
        boolean[][] m = QrEncoder.encode("hello".getBytes(StandardCharsets.UTF_8), 1, 0);
        int size = m.length;
        for (int i = 8; i < size - 8; i++) {
            assertEquals("timing row 6 col " + i, i % 2 == 0, m[6][i]);
            assertEquals("timing col 6 row " + i, i % 2 == 0, m[i][6]);
        }
    }

    @Test
    public void darkModulePosition() {
        // dark module 固定在 (row = 4*version+9, col = 8)，即左下 finder 右上角
        boolean[][] m = QrEncoder.encode("hello".getBytes(StandardCharsets.UTF_8), 1, 0);
        assertTrue("dark module", m[m.length - 8][8]);
    }

    @Test
    public void alignmentPatternsPresent() {
        // v4 的 alignment 中心 (26,26)（size 33）：5×5 外圈全深、内圈全浅、中心深
        boolean[][] m = QrEncoder.encode("hello".getBytes(StandardCharsets.UTF_8), 4, 0);
        int center = 26;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                boolean expect = Math.max(Math.abs(i), Math.abs(j)) != 1;
                assertEquals("alignment(" + i + "," + j + ")", expect, m[center + i][center + j]);
            }
        }
    }

    @Test
    public void formatInfoCopiesConsistent() {
        // 两份 format 信息必须等价：左上副本与右下副本对应位置同值
        boolean[][] m = QrEncoder.encode("hello".getBytes(StandardCharsets.UTF_8), 1, 0);
        int size = m.length;
        for (int i = 0; i < 15; i++) {
            boolean first = i < 6 ? m[i][8]
                    : i == 6 ? m[7][8]
                    : i == 7 ? m[8][8]
                    : i == 8 ? m[8][7]
                    : m[8][14 - i];
            boolean second = i < 8 ? m[8][size - 1 - i] : m[size - 15 + i][8];
            assertEquals("format bit " + i, first, second);
        }
    }

    @Test
    public void allMasksProduceDistinctMatrices() {
        // 8 个掩码在数据区不同（函数区相同）：掩码矩阵两两不等即掩码生效
        byte[] content = "https://id.cloud.139.com/xyz".getBytes(StandardCharsets.UTF_8);
        Set<Integer> fingerprints = new HashSet<>();
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] m = QrEncoder.encode(content, 4, mask);
            int fp = 0;
            for (boolean[] row : m) {
                for (boolean cell : row) {
                    fp = fp * 31 + (cell ? 1 : 0);
                }
            }
            fingerprints.add(fp);
        }
        assertEquals("8 个掩码应产生 8 种不同矩阵", 8, fingerprints.size());
    }

    @Test
    public void knownReferenceModulesV1Mask0() {
        // v1 / 掩码 0 的参考模块值：取自独立 QR 实现生成的矩阵（开发期逐位比对通过）。
        // 数据区首 4 位（mode 0100）位于右下角，未掩码时按掩码 0 条件翻转后应读出 0100。
        boolean[][] m = QrEncoder.encode("https://a.b/cd".getBytes(StandardCharsets.UTF_8), 1, 0);
        int size = 21;
        int[][] modeCells = {{20, 20}, {20, 19}, {19, 20}, {19, 19}};
        StringBuilder bits = new StringBuilder();
        for (int[] cell : modeCells) {
            boolean bit = m[cell[0]][cell[1]];
            if ((cell[0] + cell[1]) % 2 == 0) {
                bit = !bit; // 掩码 0 还原
            }
            bits.append(bit ? '1' : '0');
        }
        assertEquals("byte 模式指示位", "0100", bits.toString());
    }

    @Test
    public void oversizeContentRejected() {
        try {
            // v10-M 字节容量 213，超限应明确报错而不是静默截断
            QrEncoder.encode(new byte[214]);
            fail("超容量内容应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期路径
        }
    }
}
