package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link FileStorage#walk} 的递归枚举实现（Java 8 接口没有私有方法，落到这里）。
 *
 * @author 郑明亮
 */
public final class StorageWalker {
    private StorageWalker() {
    }

    /**
     * 递归枚举目录下全部条目（含所有层级）。
     *
     * @param storage 存储实例
     * @param dir     目录路径（已规范化，根目录为空串）
     * @return 全部后代条目
     */
    public static List<StorageEntry> walk(FileStorage storage, String dir) {
        List<StorageEntry> all = new ArrayList<StorageEntry>();
        collect(storage, dir, all);
        return all;
    }

    private static void collect(FileStorage storage, String dir, List<StorageEntry> all) {
        for (StorageEntry entry : storage.list(dir)) {
            all.add(entry);
            if (entry.isDirectory()) {
                collect(storage, entry.path(), all);
            }
        }
    }
}
