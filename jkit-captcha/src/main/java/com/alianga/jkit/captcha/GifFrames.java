package com.alianga.jkit.captcha;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 读取 GIF 的每一帧，再按横坐标把各帧里最清晰的字拼成一整串。
 */
final class GifFrames {
    private GifFrames() {
    }

    static List<BufferedImage> read(byte[] data) throws IOException {
        if (data.length >= 6 && isGif(data)) {
            return readGif(data);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("unreadable image: bytes");
        }
        List<BufferedImage> frames = new ArrayList<BufferedImage>();
        frames.add(image);
        return frames;
    }

    static List<GlyphHit> merge(List<GlyphHit> hits, int imageHeight) {
        int minHeight = Math.max(12, imageHeight * 35 / 100);
        List<GlyphHit> tall = new ArrayList<GlyphHit>();
        for (int i = 0; i < hits.size(); i++) {
            GlyphHit hit = hits.get(i);
            if (hit.getHeight() >= minHeight && hit.getConfidence() >= 0.48) {
                tall.add(hit);
            }
        }
        Collections.sort(tall, new Comparator<GlyphHit>() {
            @Override
            public int compare(GlyphHit left, GlyphHit right) {
                return left.getX() - right.getX();
            }
        });
        int gap = Math.max(12, imageHeight / 3);
        List<GlyphHit> merged = new ArrayList<GlyphHit>();
        List<GlyphHit> cluster = new ArrayList<GlyphHit>();
        int center = 0;
        for (int i = 0; i < tall.size(); i++) {
            GlyphHit hit = tall.get(i);
            int hitCenter = hit.getX() + hit.getWidth() / 2;
            if (!cluster.isEmpty() && Math.abs(hitCenter - center) > gap) {
                merged.add(vote(cluster));
                cluster.clear();
            }
            if (cluster.isEmpty()) {
                center = hitCenter;
            }
            cluster.add(hit);
        }
        if (!cluster.isEmpty()) {
            merged.add(vote(cluster));
        }
        return merged;
    }

    /**
     * 字符在多帧里反复变深，干扰线通常只出现一帧。保留至少两帧都偏暗的像素。
     */
    static BufferedImage composite(List<BufferedImage> frames) {
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dark = 0;
                for (int i = 0; i < frames.size(); i++) {
                    if (RgbImage.luminance(frames.get(i).getRGB(x, y)) < 200) {
                        dark++;
                    }
                }
                image.setRGB(x, y, dark >= 2 ? 0x202020 : 0xffffff);
            }
        }
        return image;
    }

    private static GlyphHit vote(List<GlyphHit> cluster) {
        Map<Character, Integer> counts = new HashMap<Character, Integer>();
        Map<Character, GlyphHit> best = new HashMap<Character, GlyphHit>();
        for (int i = 0; i < cluster.size(); i++) {
            GlyphHit hit = cluster.get(i);
            Character label = Character.valueOf(hit.getValue());
            Integer count = counts.get(label);
            counts.put(label, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            GlyphHit previous = best.get(label);
            if (previous == null || hit.getConfidence() > previous.getConfidence()) {
                best.put(label, hit);
            }
        }
        GlyphHit chosen = cluster.get(0);
        int chosenCount = 0;
        for (Map.Entry<Character, Integer> entry : counts.entrySet()) {
            GlyphHit hit = best.get(entry.getKey());
            int count = entry.getValue().intValue();
            if (count > chosenCount || count == chosenCount && hit.getConfidence() > chosen.getConfidence()) {
                chosen = hit;
                chosenCount = count;
            }
        }
        return chosen;
    }

    private static List<BufferedImage> readGif(byte[] data) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
        if (stream == null) {
            throw new IOException("unreadable image: gif");
        }
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("unreadable image: gif");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                int count = reader.getNumImages(true);
                List<BufferedImage> frames = new ArrayList<BufferedImage>();
                for (int i = 0; i < count; i++) {
                    frames.add(reader.read(i));
                }
                if (frames.isEmpty()) {
                    throw new IOException("unreadable image: gif");
                }
                return frames;
            } finally {
                reader.dispose();
            }
        } finally {
            stream.close();
        }
    }

    private static boolean isGif(byte[] data) {
        return data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
    }
}
