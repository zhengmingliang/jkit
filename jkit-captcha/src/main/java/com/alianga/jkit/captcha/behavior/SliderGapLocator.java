package com.alianga.jkit.captcha.behavior;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑块缺口定位器。针对「背景图 + 模板条」两类输入给出缺口 x 坐标（背景图自然坐标系）。
 *
 * <p>三种策略并列输出，供上层按置信度融合：
 * <ul>
 *   <li>{@code interior}：模板条 alpha 腐蚀后的内容区与背景图做亮度差匹配。开源版
 *       tianai-captcha 的切块内容就是缺口处的原始背景，因此该策略在开源协议栈上最准。</li>
 *   <li>{@code ring}：模板条不透明边界环在背景图上扫 Sobel 梯度均值，形状环对齐处出现峰值。
 *       内容被厂商做色彩扰动时仍然可用（只依赖形状边缘）。</li>
 *   <li>{@code flatCore}：检测背景图上平铺灰芯（低饱和、局部平坦的最大连通域），按
 *       「环宽自洽公式」反推缺口位置。适用于缺口被平铺色填充的增强版渲染。</li>
 * </ul>
 * 全部为纯 Java 像素运算，无任何外部依赖。
 */
final class SliderGapLocator {
    /** 单策略候选结果：x 为背景图自然坐标，confidence ∈ [0,1] 越大越可信。 */
    static final class GapCandidate {
        final int x;
        final double confidence;
        final String strategy;

        GapCandidate(int x, double confidence, String strategy) {
            this.x = x;
            this.confidence = confidence;
            this.strategy = strategy;
        }

        @Override
        public String toString() {
            return strategy + "#x=" + x + "#conf=" + String.format("%.3f", confidence);
        }
    }

    /** 定位汇总：三种策略各自的最好候选（可能为 null）。 */
    static final class Location {
        final GapCandidate interior;
        final GapCandidate ring;
        final GapCandidate flatCore;

        Location(GapCandidate interior, GapCandidate ring, GapCandidate flatCore) {
            this.interior = interior;
            this.ring = ring;
            this.flatCore = flatCore;
        }
    }

    private static final int ALPHA_THRESHOLD = 128;
    /** 平坦度阈值：3x3 邻域亮度极差不超过该值视为局部平坦。 */
    private static final int FLAT_RANGE = 6;
    /** 平坦区饱和度上限。 */
    private static final int FLAT_SATURATION = 16;

    private SliderGapLocator() {
    }

    static Location locate(ArgbImage bg, ArgbImage template) {
        int[] box = contentBox(template);
        int cx = box[0];
        int cy = box[1];
        int cw = box[2] - box[0] + 1;
        int ch = box[3] - box[1] + 1;
        if (cw < 8 || ch < 8 || bg.width < cw || bg.height < ch) {
            return new Location(null, null, null);
        }
        GapCandidate interior = interiorMatch(bg, template, cx, cy, cw, ch);
        GapCandidate ring = ringMatch(bg, template, cx, cy, cw, ch);
        GapCandidate flatCore = flatCoreMatch(bg, cx, cw, ch);
        return new Location(interior, ring, flatCore);
    }

    /** 模板条 alpha>128 的内容包围盒 {left, top, right, bottom}。 */
    private static int[] contentBox(ArgbImage template) {
        int left = template.width;
        int top = template.height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < template.height; y++) {
            int rowBase = y * template.width;
            for (int x = 0; x < template.width; x++) {
                if (template.alpha(rowBase + x) > ALPHA_THRESHOLD) {
                    if (x < left) {
                        left = x;
                    }
                    if (x > right) {
                        right = x;
                    }
                    if (y < top) {
                        top = y;
                    }
                    if (y > bottom) {
                        bottom = y;
                    }
                }
            }
        }
        return new int[]{left, top, right, bottom};
    }

    /**
     * 策略一：内容区亮度差匹配。模板内容区（腐蚀掉边框环）与背景图逐列求平均绝对亮度差，
     * 最小值处即缺口。返回候选带 0..1 置信度：由「次峰与主峰的差」与「主峰绝对差」联合估计。
     */
    private static GapCandidate interiorMatch(ArgbImage bg, ArgbImage template,
                                              int cx, int cy, int cw, int ch) {
        boolean[] interior = erodedInterior(template, cx, cy, cw, ch);
        int[] sampleX = new int[cw * ch];
        int[] sampleY = new int[cw * ch];
        int[] sampleLum = new int[cw * ch];
        int n = 0;
        for (int y = 0; y < ch; y += 2) {
            int rowBase = (cy + y) * template.width;
            for (int x = 0; x < cw; x += 2) {
                if (interior[y * cw + x]) {
                    sampleX[n] = cx + x;
                    sampleY[n] = cy + y;
                    sampleLum[n] = ArgbImage.luminance(template.pixels[rowBase + cx + x]);
                    n++;
                }
            }
        }
        if (n < 64) {
            return null;
        }
        int maxD = bg.width - cw;
        if (maxD < 1) {
            return null;
        }
        double[] scores = new double[maxD + 1];
        for (int d = 0; d <= maxD; d++) {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                int bgLum = ArgbImage.luminance(bg.pixels[sampleY[i] * bg.width + d + sampleX[i]]);
                int diff = bgLum - sampleLum[i];
                sum += diff < 0 ? -diff : diff;
            }
            scores[d] = sum / (double) n;
        }
        int best = 0;
        for (int d = 1; d <= maxD; d++) {
            if (scores[d] < scores[best]) {
                best = d;
            }
        }
        // 次峰：与主峰距离超过内容宽一半的最小值，衡量主峰是否显著
        double second = Double.MAX_VALUE;
        for (int d = 0; d <= maxD; d++) {
            if (Math.abs(d - best) > cw / 2 && scores[d] < second) {
                second = scores[d];
            }
        }
        if (second == Double.MAX_VALUE) {
            second = scores[best];
        }
        double absolute = 1.0 - Math.min(1.0, scores[best] / 64.0);
        double separation = 1.0 - Math.min(1.0, scores[best] / Math.max(second, 1e-6));
        double confidence = Math.max(0.0, Math.min(1.0, 0.6 * absolute + 0.4 * separation));
        return new GapCandidate(best, confidence, "interior");
    }

    /**
     * 以 Chebyshev 距离腐蚀 alpha 内容区：窗口内全不透明才算内容。用 summed-area table
     * 做到 O(N)，腐蚀半径取内容短边的 1/4（夹在 8..24），保证剔除边框环的同时保留足够样本。
     */
    private static boolean[] erodedInterior(ArgbImage template, int cx, int cy, int cw, int ch) {
        int radius = Math.max(8, Math.min(24, Math.min(cw, ch) / 4));
        // sat[cw][ch]：(0..y, 0..x) 范围内不透明像素计数
        int[][] sat = new int[ch + 1][cw + 1];
        for (int y = 0; y < ch; y++) {
            int rowCount = 0;
            for (int x = 0; x < cw; x++) {
                if (template.alpha((cy + y) * template.width + (cx + x)) > ALPHA_THRESHOLD) {
                    rowCount++;
                }
                sat[y + 1][x + 1] = sat[y][x + 1] + rowCount;
            }
        }
        boolean[] interior = new boolean[cw * ch];
        int window = 2 * radius + 1;
        int full = window * window;
        for (int y = radius; y < ch - radius; y++) {
            for (int x = radius; x < cw - radius; x++) {
                int count = sat[y + radius + 1][x + radius + 1] - sat[y - radius][x + radius + 1]
                        - sat[y + radius + 1][x - radius] + sat[y - radius][x - radius];
                // x/y 已保证窗口不越界，count 等于窗口面积即窗口内全不透明
                interior[y * cw + x] = count == full;
            }
        }
        return interior;
    }

    /**
     * 策略二：形状环梯度匹配。沿模板不透明边界环在背景图上取 Sobel 梯度幅值均值，
     * 环与缺口轮廓重合处出现峰值。
     */
    private static GapCandidate ringMatch(ArgbImage bg, ArgbImage template,
                                          int cx, int cy, int cw, int ch) {
        // 背景图亮度 + Sobel 梯度幅值
        int w = bg.width;
        int h = bg.height;
        int[] lum = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            lum[i] = ArgbImage.luminance(bg.pixels[i]);
        }
        double[] grad = new double[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int tl = lum[(y - 1) * w + x - 1];
                int t = lum[(y - 1) * w + x];
                int tr = lum[(y - 1) * w + x + 1];
                int l = lum[y * w + x - 1];
                int r = lum[y * w + x + 1];
                int bl = lum[(y + 1) * w + x - 1];
                int b = lum[(y + 1) * w + x];
                int br = lum[(y + 1) * w + x + 1];
                int gx = -tl - 2 * l - bl + tr + 2 * r + br;
                int gy = -tl - 2 * t - tr + bl + 2 * b + br;
                grad[y * w + x] = Math.sqrt((double) gx * gx + (double) gy * gy);
            }
        }
        // 边界环：不透明且 4 邻域存在透明
        List<int[]> ring = new ArrayList<>();
        for (int y = 1; y < ch - 1; y++) {
            for (int x = 1; x < cw - 1; x++) {
                int base = (cy + y) * template.width + cx + x;
                if (template.alpha(base) <= ALPHA_THRESHOLD) {
                    continue;
                }
                boolean boundary = template.alpha(base - 1) <= ALPHA_THRESHOLD
                        || template.alpha(base + 1) <= ALPHA_THRESHOLD
                        || template.alpha(base - template.width) <= ALPHA_THRESHOLD
                        || template.alpha(base + template.width) <= ALPHA_THRESHOLD;
                if (boundary) {
                    ring.add(new int[]{x, y});
                }
            }
        }
        if (ring.size() < 32) {
            return null;
        }
        int maxD = bg.width - cw;
        double[] scores = new double[maxD + 1];
        for (int d = 0; d <= maxD; d++) {
            double sum = 0;
            for (int[] p : ring) {
                sum += grad[(cy + p[1]) * w + d + cx + p[0]];
            }
            scores[d] = sum / ring.size();
        }
        int best = 0;
        for (int d = 1; d <= maxD; d++) {
            if (scores[d] > scores[best]) {
                best = d;
            }
        }
        double second = -1;
        for (int d = 0; d <= maxD; d++) {
            if (Math.abs(d - best) > cw / 2 && scores[d] > second) {
                second = scores[d];
            }
        }
        if (second < 0) {
            second = scores[best];
        }
        double separation = 1.0 - Math.min(1.0, second / Math.max(scores[best], 1e-6));
        return new GapCandidate(best, Math.max(0.0, separation), "ring");
    }

    /**
     * 策略三：平铺灰芯检测。找背景图上低饱和、局部平坦的最大连通域，按
     * {@code x = coreLeft - contentOffset - (contentWidth - coreWidth) / 2} 反推缺口位置。
     * 环宽自洽：内容宽与芯宽之差的一半即单侧边框环宽。
     */
    private static GapCandidate flatCoreMatch(ArgbImage bg, int cx, int cw, int ch) {
        int w = bg.width;
        int h = bg.height;
        boolean[] flat = new boolean[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int index = y * w + x;
                int packed = bg.pixels[index];
                if (ArgbImage.saturation(packed) > FLAT_SATURATION) {
                    continue;
                }
                int min = 255;
                int max = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int l = ArgbImage.luminance(bg.pixels[index + dy * w + dx]);
                        if (l < min) {
                            min = l;
                        }
                        if (l > max) {
                            max = l;
                        }
                    }
                }
                flat[index] = max - min <= FLAT_RANGE;
            }
        }
        // 最大连通域（迭代式 BFS，避免递归栈溢出）
        int[] stack = new int[w * h];
        boolean[] visited = new boolean[w * h];
        int bestArea = 0;
        int bestLeft = -1;
        int bestRight = -1;
        int bestTop = -1;
        int bestBottom = -1;
        for (int start = 0; start < w * h; start++) {
            if (!flat[start] || visited[start]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = start;
            visited[start] = true;
            int area = 0;
            int left = w;
            int right = -1;
            int top = h;
            int bottom = -1;
            while (sp > 0) {
                int index = stack[--sp];
                int x = index % w;
                int y = index / w;
                area++;
                if (x < left) {
                    left = x;
                }
                if (x > right) {
                    right = x;
                }
                if (y < top) {
                    top = y;
                }
                if (y > bottom) {
                    bottom = y;
                }
                if (x > 0 && flat[index - 1] && !visited[index - 1]) {
                    visited[index - 1] = true;
                    stack[sp++] = index - 1;
                }
                if (x < w - 1 && flat[index + 1] && !visited[index + 1]) {
                    visited[index + 1] = true;
                    stack[sp++] = index + 1;
                }
                if (y > 0 && flat[index - w] && !visited[index - w]) {
                    visited[index - w] = true;
                    stack[sp++] = index - w;
                }
                if (y < h - 1 && flat[index + w] && !visited[index + w]) {
                    visited[index + w] = true;
                    stack[sp++] = index + w;
                }
            }
            // 芯的合理尺寸：面积占内容的 15%~85%（边框环占两侧），宽高不超过内容宽高，
            // 且形状（宽高比）与内容相似，排除天空/草地等大面积平坦背景
            int regionWidth = right - left + 1;
            int regionHeight = bottom - top + 1;
            double coreRatio = regionWidth / (double) regionHeight;
            double contentRatio = cw / (double) ch;
            if (area >= cw * ch * 15 / 100 && area <= cw * ch * 85 / 100 && bestArea < area
                    && regionWidth <= cw && regionHeight <= ch
                    && Math.abs(coreRatio - contentRatio) <= 0.35 * contentRatio) {
                bestArea = area;
                bestLeft = left;
                bestRight = right;
                bestTop = top;
                bestBottom = bottom;
            }
        }
        if (bestArea <= 0) {
            return null;
        }
        int coreWidth = bestRight - bestLeft + 1;
        int coreHeight = bestBottom - bestTop + 1;
        // 面积法环宽：芯面积 ≈ (内容宽-2e)(内容高-2e)，解出 e 后取水平项
        double erode = Math.max(0, (cw - coreWidth) / 2.0);
        double conf = Math.min(1.0, bestArea / (0.85 * (double) cw * ch));
        int x = (int) Math.round(bestLeft - cx - erode);
        if (x < 0) {
            x = 0;
        }
        return new GapCandidate(x, conf, "flatCore");
    }
}
