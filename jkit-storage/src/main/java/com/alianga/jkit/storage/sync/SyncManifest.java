package com.alianga.jkit.storage.sync;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StoragePaths;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双向同步的基线清单，记在同步目录下的 {@code .jkit-sync/manifest.txt}。
 * 两侧都有一份合法清单时，才能把「上次双方都有、这次单侧消失」判成删除；
 * 清单缺失或损坏时不做删除，只补齐文件。
 *
 * @author 郑明亮
 */
final class SyncManifest {
    static final String DIR = ".jkit-sync";
    static final String FILE = ".jkit-sync/manifest.txt";
    private static final String MAGIC = "jkit-sync-manifest-1";

    private SyncManifest() {
    }

    /** 引擎保留目录，不参与用户文件的对比、复制和镜像删除。 */
    static boolean isMeta(String relative) {
        return DIR.equals(relative) || (relative != null && relative.startsWith(DIR + "/"));
    }

    /**
     * 读取清单。文件不存在、魔法头不对或任一行损坏时返回 null，调用方不得据此删除。
     *
     * @param storage 存储
     * @param dir     同步目录
     * @return 相对路径 → 当时的修改时间；未知时间为 {@link StorageEntry#MTIME_UNKNOWN}
     */
    static Map<String, Long> read(FileStorage storage, String dir) {
        String path = StoragePaths.join(dir, FILE);
        try {
            if (!storage.exists(path)) {
                return null;
            }
            return parse(storage.readString(path));
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    /**
     * 把当前用户文件写成清单。路径含换行或制表符的条目跳过，避免破坏行格式。
     *
     * @param storage 存储
     * @param dir     同步目录
     * @param files   相对路径 → 条目
     */
    static void write(FileStorage storage, String dir, Map<String, StorageEntry> files) {
        List<String> paths = new ArrayList<String>(files.keySet());
        Collections.sort(paths);
        StringBuilder body = new StringBuilder();
        body.append(MAGIC).append('\n');
        for (int i = 0; i < paths.size(); i++) {
            String relative = paths.get(i);
            if (relative.indexOf('\n') >= 0 || relative.indexOf('\t') >= 0 || isMeta(relative)) {
                continue;
            }
            StorageEntry entry = files.get(relative);
            body.append(relative).append('\t').append(entry.lastModified()).append('\n');
        }
        String metaDir = StoragePaths.join(dir, DIR);
        if (!storage.exists(metaDir)) {
            storage.mkdir(metaDir);
        }
        storage.writeString(StoragePaths.join(dir, FILE), body.toString());
    }

    static Map<String, Long> parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int newline = normalized.indexOf('\n');
        String header = newline < 0 ? normalized : normalized.substring(0, newline);
        if (!MAGIC.equals(header.trim())) {
            return null;
        }
        Map<String, Long> paths = new LinkedHashMap<String, Long>();
        int start = newline < 0 ? normalized.length() : newline + 1;
        while (start <= normalized.length()) {
            int end = normalized.indexOf('\n', start);
            if (end < 0) {
                end = normalized.length();
            }
            String line = normalized.substring(start, end);
            start = end + 1;
            if (line.isEmpty()) {
                if (end == normalized.length()) {
                    break;
                }
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) {
                return null;
            }
            String relative = line.substring(0, tab);
            try {
                paths.put(relative, Long.valueOf(line.substring(tab + 1).trim()));
            } catch (NumberFormatException bad) {
                return null;
            }
            if (end == normalized.length()) {
                break;
            }
        }
        return paths;
    }
}
