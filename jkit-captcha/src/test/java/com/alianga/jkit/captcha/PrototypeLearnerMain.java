package com.alianga.jkit.captcha;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从带标注的验证码数据集学习字形原型，产出 {@code learned-glyphs.bin}。
 *
 * <p>逐张分割（浅色/深色按底色分流，同识别主链路），字形数与期望文本等长时按
 * x 序号配对标签；每个标签做贪心覆盖聚类，每个原型覆盖 dice≥{@code --coverage}
 * 的样本，最多 {@code --per-label} 个，挑不出覆盖王的剩余孤立样本直接丢弃
 * （单样本原型是噪声源，实测会让准确率掉头向下）。产出的位压文件拷到
 * jkit-captcha 的 resources 里随包分发。
 *
 * <pre>
 * java -cp jkit-captcha/target/test-classes:jkit-captcha/target/classes:... \
 *     com.alianga.jkit.captcha.PrototypeLearnerMain \
 *     --manifest target/captcha-hard/manifest.tsv --images target/captcha-hard --out learned-glyphs.bin
 * </pre>
 */
public final class PrototypeLearnerMain {
    private static final int MAGIC = 0x4A4C4742;
    private static final int PACKED = 24 * 32 / 8;

    /** 一个学习原型：标签、学出时的字形宽高、24×32 归一化网格。 */
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

    private PrototypeLearnerMain() {
    }

    /**
     * @param args 命令行参数
     * @throws Exception 读图或写文件失败时
     */
    public static void main(String[] args) throws Exception {
        File manifest = new File(arg(args, "--manifest", "target/captcha-hard/manifest.tsv"));
        File images = new File(arg(args, "--images", "target/captcha-hard"));
        File out = new File(arg(args, "--out", "learned-glyphs.bin"));
        int perLabel = Integer.parseInt(arg(args, "--per-label", "20"));
        double coverage = Double.parseDouble(arg(args, "--coverage", "0.78"));
        int limit = Integer.parseInt(arg(args, "--limit", "-1"));

        Map<Character, List<RawGlyph>> byLabel = new HashMap<Character, List<RawGlyph>>();
        BufferedReader reader = new BufferedReader(
                new FileReader(manifest, StandardCharsets.UTF_8));
        int used = 0;
        int skipped = 0;
        try {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                if (limit >= 0 && used >= limit) {
                    break;
                }
                String[] parts = line.split("\t");
                if (parts.length < 2 || parts[1].isEmpty()) {
                    continue;
                }
                BufferedImage image = ImageIO.read(new File(images, parts[0]));
                if (image == null) {
                    skipped++;
                    continue;
                }
                List<RawGlyph> glyphs = segment(image);
                String expected = parts[1];
                if (glyphs.size() != expected.length()) {
                    skipped++;
                    continue;
                }
                for (int i = 0; i < glyphs.size(); i++) {
                    byLabel.computeIfAbsent(expected.charAt(i), k -> new ArrayList<RawGlyph>())
                            .add(glyphs.get(i));
                }
                used++;
                if (used % 10000 == 0) {
                    System.out.println("  已学习 " + used + " 张");
                }
            }
        } finally {
            reader.close();
        }

        List<Proto> protos = new ArrayList<Proto>();
        Map<Character, Integer> perLabelCount = new TreeMap<Character, Integer>();
        for (Map.Entry<Character, List<RawGlyph>> entry : byLabel.entrySet()) {
            List<RawGlyph> glyphs = entry.getValue();
            List<byte[]> grids = new ArrayList<byte[]>(glyphs.size());
            for (RawGlyph glyph : glyphs) {
                grids.add(GlyphNormalizer.toGrid(glyph.pixels, glyph.width, glyph.height));
            }
            boolean[] covered = new boolean[grids.size()];
            int uncovered = grids.size();
            int count = 0;
            for (int p = 0; p < perLabel && uncovered > 0; p++) {
                int bestAt = -1;
                int bestCover = 0;
                for (int i = 0; i < grids.size(); i++) {
                    if (covered[i]) {
                        continue;
                    }
                    int cover = 0;
                    for (int j = 0; j < grids.size(); j++) {
                        if (!covered[j] && GlyphNormalizer.dice(grids.get(i), grids.get(j)) >= coverage) {
                            cover++;
                        }
                    }
                    if (cover > bestCover) {
                        bestCover = cover;
                        bestAt = i;
                    }
                }
                if (bestAt < 0 || bestCover <= 1) {
                    break;
                }
                RawGlyph source = glyphs.get(bestAt);
                protos.add(new Proto(entry.getKey(), source.width, source.height, grids.get(bestAt)));
                count++;
                for (int j = 0; j < grids.size(); j++) {
                    if (!covered[j] && GlyphNormalizer.dice(grids.get(bestAt), grids.get(j)) >= coverage) {
                        covered[j] = true;
                        uncovered--;
                    }
                }
            }
            perLabelCount.put(entry.getKey(), count);
        }

        File reportFile = new File(out.getParentFile() == null ? new File(".") : out.getParentFile(),
                out.getName() + ".report.txt");
        try (BufferedWriter report = new BufferedWriter(
                new FileWriter(reportFile, StandardCharsets.UTF_8))) {
            for (Map.Entry<Character, Integer> entry : perLabelCount.entrySet()) {
                report.write(entry.getKey() + " " + entry.getValue());
                report.newLine();
            }
        }

        try (FileOutputStream stream = new FileOutputStream(out)) {
            writeInt(stream, MAGIC);
            writeInt(stream, protos.size());
            for (Proto proto : protos) {
                stream.write(proto.label);
                stream.write(Math.max(0, Math.min(255, proto.width)));
                stream.write(Math.max(0, Math.min(255, proto.height)));
                for (int i = 0; i < PACKED; i++) {
                    int bits = 0;
                    for (int b = 0; b < 8; b++) {
                        int index = i * 8 + b;
                        bits |= proto.grid[index] << b;
                    }
                    stream.write(bits);
                }
            }
        }
        System.out.printf(Locale.ROOT, "学习 %d 张（跳过 %d 张），原型 %d 个 -> %s（报告 %s）%n",
                Integer.valueOf(used), Integer.valueOf(skipped), Integer.valueOf(protos.size()),
                out.getAbsolutePath(), reportFile.getName());
    }

    /** 与识别主链路同构的分割：按底色分流浅/深，重叠抑制，按 x 排序。 */
    private static List<RawGlyph> segment(BufferedImage image) {
        RgbImage rgb = RgbImage.from(image);
        int background = rgb.borderMedianLuminance();
        List<RawGlyph> glyphs = background < 80
                ? new DarkSegmenter().segment(rgb)
                : new LightSegmenter().segment(rgb);
        List<RawGlyph> kept = new ArrayList<RawGlyph>();
        outer:
        for (int i = 0; i < glyphs.size(); i++) {
            for (int j = 0; j < glyphs.size(); j++) {
                if (i == j) {
                    continue;
                }
                RawGlyph left = glyphs.get(i);
                RawGlyph right = glyphs.get(j);
                int overlapX = Math.min(left.x + left.width, right.x + right.width) - Math.max(left.x, right.x);
                int overlapY = Math.min(left.y + left.height, right.y + right.height) - Math.max(left.y, right.y);
                if (overlapX > 0 && overlapY > 0
                        && overlapX * 10 > Math.min(left.width, right.width) * 6
                        && overlapY * 10 > Math.min(left.height, right.height) * 6
                        && left.area < right.area) {
                    continue outer;
                }
            }
            kept.add(glyphs.get(i));
        }
        kept.sort((a, b) -> Integer.compare(a.x, b.x));
        return kept;
    }

    private static void writeInt(FileOutputStream stream, int value) throws IOException {
        stream.write(value >> 24);
        stream.write(value >> 16);
        stream.write(value >> 8);
        stream.write(value);
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
