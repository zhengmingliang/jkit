package com.alianga.jkit.storage.conn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 在存储库按「损坏即空」恢复之前，把损坏的 JSON 文件改名保留在原位，
 * 防止随后的一次保存把原文件内容永久覆盖掉。
 *
 * <p>连接库与历史库共用：任何「读不出来就当空库」的 JSON 持久化，
 * 恢复前都应先调用 {@link #preserve}。</p>
 *
 * @author 郑明亮
 */
public final class CorruptJsonBackup {
    private CorruptJsonBackup() {
    }

    /**
     * 把损坏文件以唯一名移到同级目录保留。
     *
     * @param file 损坏的 JSON 文件
     * @return 保留文件路径
     * @throws IllegalStateException 无法保留原文件（阻止继续保存以避免覆盖）
     */
    public static Path preserve(Path file) {
        String suffix = ".corrupt-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
        Path backup = file.resolveSibling(file.getFileName() + suffix);
        try {
            return Files.move(file, backup);
        } catch (IOException failure) {
            throw new IllegalStateException("无法备份损坏的配置文件，已阻止继续保存以避免覆盖原文件: "
                    + file, failure);
        }
    }
}
