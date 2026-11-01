package com.alianga.jkit.captcha;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 学习字形原型库：从带标注的真实验证码里学出来的归一化字形，作为渲染模板之外的候选池。
 *
 * <p>渲染模板和真实风格（旋转、噪点、深色底、裂笔）有差距，真实字形学出来的原型
 * 在同风格图上判别力更强。原型文件由 jkit-captcha-bench 的 PrototypeLearnerMain
 * 从标注数据集生成（{@code learned-glyphs.bin}，位压存储），随 jar 分发；
 * 生产上可以用真实流量持续重学替换。文件不存在时本库静默退化为空池。
 *
 * <p>原型带有学出时的字形宽高，查询时先按宽高过滤再算 dice，避免全量比对。
 * 只在匹配风格的图上启用（调用方按画布高度门控），候选与渲染模板同池按分竞争。
 */
final class LearnedGlyphBank {
    private static final String RESOURCE = "learned-glyphs.bin";
    private static final int MAGIC = 0x4A4C4742; // JLGB
    /** 查询时允许的宽高偏差：学出字形的裁切框随旋转噪点抖动，不能按像素卡死。 */
    private static final int DIM_SLACK = 5;

    private final Object lock = new Object();
    private List<Proto> protos;
    private boolean failed;

    /** 一个原型：标签 + 学出时的字形宽高 + 24×32 归一化网格。 */
    private static final class Proto {
        final char label;
        final int width;
        final int height;
        final byte[] grid;

        Proto(char label, int width, int height, byte[] grid) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.grid = grid;
        }
    }

    /**
     * 在原型池里找与查询网格最像的候选。
     *
     * @param grid      24×32 归一化网格
     * @param glyphWidth  字形裁切框宽，过滤用
     * @param glyphHeight 字形裁切框高，过滤用
     * @param alphabet  只返回这个字符集里的标签
     * @return 按分数降序的候选，池为空或加载失败时为空列表
     */
    List<TemplateBank.Scored> rank(byte[] grid, int glyphWidth, int glyphHeight, String alphabet) {
        List<Proto> pool = pool();
        List<TemplateBank.Scored> scored = new ArrayList<TemplateBank.Scored>();
        if (pool.isEmpty()) {
            return scored;
        }
        for (int i = 0; i < pool.size(); i++) {
            Proto proto = pool.get(i);
            if (Math.abs(proto.width - glyphWidth) > DIM_SLACK
                    || Math.abs(proto.height - glyphHeight) > DIM_SLACK
                    || alphabet.indexOf(proto.label) < 0) {
                continue;
            }
            scored.add(new TemplateBank.Scored(proto.label, GlyphNormalizer.dice(grid, proto.grid)));
        }
        scored.sort((left, right) -> Double.compare(right.score, left.score));
        return scored;
    }

    int size() {
        return pool().size();
    }

    private List<Proto> pool() {
        synchronized (lock) {
            if (protos == null && !failed) {
                try {
                    protos = load();
                } catch (IOException ex) {
                    // 原型文件损坏或缺席都不该挡住识别：记一次失败，之后按空池工作
                    failed = true;
                    protos = new ArrayList<Proto>();
                }
            }
            return protos;
        }
    }

    private List<Proto> load() throws IOException {
        InputStream raw = LearnedGlyphBank.class.getResourceAsStream(RESOURCE);
        if (raw == null) {
            return new ArrayList<Proto>();
        }
        List<Proto> loaded = new ArrayList<Proto>();
        try (BufferedInputStream stream = new BufferedInputStream(raw)) {
            if (readInt(stream) != MAGIC) {
                throw new IOException("bad magic");
            }
            int count = readInt(stream);
            int packed = GlyphNormalizer.WIDTH * GlyphNormalizer.HEIGHT / 8;
            byte[] packedGrid = new byte[packed];
            for (int i = 0; i < count; i++) {
                int label = stream.read();
                int width = stream.read();
                int height = stream.read();
                if (readFully(stream, packedGrid)) {
                    break;
                }
                loaded.add(new Proto((char) label, width, height, unpack(packedGrid)));
            }
        }
        return loaded;
    }

    private static byte[] unpack(byte[] packed) {
        byte[] grid = new byte[GlyphNormalizer.WIDTH * GlyphNormalizer.HEIGHT];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = (byte) ((packed[i >> 3] >> (i & 7)) & 1);
        }
        return grid;
    }

    private static int readInt(InputStream stream) throws IOException {
        int b0 = stream.read();
        int b1 = stream.read();
        int b2 = stream.read();
        int b3 = stream.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new IOException("truncated");
        }
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static boolean readFully(InputStream stream, byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int read = stream.read(buffer, off, buffer.length - off);
            if (read < 0) {
                return true;
            }
            off += read;
        }
        return false;
    }

    /** @return 调试信息 */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "LearnedGlyphBank[%d]", size());
    }
}
