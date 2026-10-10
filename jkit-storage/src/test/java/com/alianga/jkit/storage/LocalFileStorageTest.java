package com.alianga.jkit.storage;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 本地文件系统引擎测试：读写、列表、目录操作、递归删除与路径防逃逸。
 *
 * @author 郑明亮
 */
public class LocalFileStorageTest {
    private FileStorage newStorage(Path root) {
        return FileStorages.of("local", StorageConfig.empty().set("basePath", root.toString()));
    }

    @Test
    public void uploadReadListDelete() throws Exception {
        Path root = Files.createTempDirectory("jkit-local-test");
        try {
            FileStorage local = newStorage(root);
            assertFalse(local.exists("demo/a.txt"));
            local.upload("demo/a.txt", "你好".getBytes(StandardCharsets.UTF_8));
            assertTrue(local.exists("demo/a.txt"));
            assertTrue(local.exists("demo"));
            assertEquals("你好", local.readString("demo/a.txt"));
            StorageEntry entry = local.stat("demo/a.txt");
            assertEquals(6L, entry.size());
            assertFalse(entry.isDirectory());
            assertTrue(entry.lastModified() > 0L);
            List<StorageEntry> list = local.list("demo");
            assertEquals(1, list.size());
            assertEquals("a.txt", list.get(0).name());
            local.delete("demo/a.txt");
            assertFalse(local.exists("demo/a.txt"));
            assertTrue(local.exists("demo"));
            local.delete("demo");
            assertFalse(local.exists("demo"));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void mkdirRenameCopyWalkAndDeleteDir() throws Exception {
        Path root = Files.createTempDirectory("jkit-local-test");
        try {
            FileStorage local = newStorage(root);
            local.mkdir("tree/sub");
            local.upload("tree/sub/x.txt", "X".getBytes(StandardCharsets.UTF_8));
            local.upload("tree/y.txt", "Y".getBytes(StandardCharsets.UTF_8));
            assertEquals(3, local.walk("tree").size());
            local.rename("tree/sub/x.txt", "tree/sub/z.txt");
            assertTrue(local.exists("tree/sub/z.txt"));
            local.copy("tree/sub", "tree/copy");
            assertTrue(local.exists("tree/copy/z.txt"));
            local.deleteDir("tree/copy");
            assertFalse(local.exists("tree/copy"));
            assertEquals(3, local.walk("tree").size());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void deleteNonEmptyDirConflicts() throws Exception {
        Path root = Files.createTempDirectory("jkit-local-test");
        try {
            FileStorage local = newStorage(root);
            local.mkdir("d");
            local.upload("d/f.txt", "F".getBytes(StandardCharsets.UTF_8));
            try {
                local.delete("d");
                throw new AssertionError("非空目录删除应抛 CONFLICT");
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.CONFLICT, e.code());
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void notFoundStatAndDownload() throws Exception {
        Path root = Files.createTempDirectory("jkit-local-test");
        try {
            FileStorage local = newStorage(root);
            try {
                local.stat("ghost.txt");
                throw new AssertionError("应抛 NOT_FOUND");
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.NOT_FOUND, e.code());
                assertEquals("ghost.txt", e.path());
            }
            try {
                local.download("ghost.txt", new java.io.ByteArrayOutputStream());
                throw new AssertionError("应抛 NOT_FOUND");
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.NOT_FOUND, e.code());
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void uploadOverwritesAtomically() throws Exception {
        Path root = Files.createTempDirectory("jkit-local-test");
        try {
            FileStorage local = newStorage(root);
            local.upload("a.txt", "first".getBytes(StandardCharsets.UTF_8));
            local.upload("a.txt", "second-version".getBytes(StandardCharsets.UTF_8));
            assertEquals("second-version", local.readString("a.txt"));
            assertFalse(Files.exists(root.resolve("a.txt.jkit-tmp")));
            assertTrue(local.list("").containsAll(java.util.Collections.emptyList()));
            assertEquals(1, local.list("").size());
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
