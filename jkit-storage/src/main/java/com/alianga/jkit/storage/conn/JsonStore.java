package com.alianga.jkit.storage.conn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 工具数据的 JSON 文本读写（Java 8 版 toolbox JsonFiles 等价实现）：写入同目录临时文件后
 * {@code ATOMIC_MOVE} 替换，避免进程崩溃留在盘上半截 JSON 把下一次启动拖垮。
 *
 * <p>只读一定程度容错：读不到就返回空串，由调用方决定默认值——持久化失败不该阻断工具本身。</p>
 *
 * @author 郑明亮
 */
final class JsonStore {
    private JsonStore() {
    }

    /**
     * UTF-8 原子写入：先落临时文件再替换目标文件。
     *
     * @param file 目标文件
     * @param text 文本
     * @throws IOException 写入失败
     */
    static void writeString(Path file, String text) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp-"
                + UUID.randomUUID() + "-" + System.nanoTime());
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * UTF-8 读取。
     *
     * @param file 目标文件
     * @return 文件文本；不存在或读取失败返回空串
     */
    static String readStringOrEmpty(Path file) {
        try {
            if (!Files.exists(file)) {
                return "";
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
