package com.alianga.jkit.storage.trash;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 回收站语义：软删进批次、列出、恢复（含同名自动编号）、过期清理、rename 不支持回退。
 *
 * @author 郑明亮
 */
public class TrashTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private FileStorage local() {
        return FileStorages.of("local", StorageConfig.empty().set("basePath",
                tmp.getRoot().getAbsolutePath()));
    }

    @Test
    public void moveToTrashBatchesAndHidesFromRoot() throws Exception {
        FileStorage storage = local();
        try {
            storage.upload("a.txt", "A".getBytes("UTF-8"));
            storage.upload("dir/b.txt", "B".getBytes("UTF-8"));
            String batch = Trash.newBatchName();
            assertTrue("批次名应为 yyyyMMdd-HHmmss", batch.matches("\\d{8}-\\d{6}"));

            List<Trash.Item> items = Trash.moveToTrash(storage,
                    Arrays.asList("a.txt", "dir"), batch, true);
            assertEquals(2, items.size());
            assertEquals(Trash.Outcome.TRASHED, items.get(0).outcome());
            assertEquals(Trash.Outcome.TRASHED, items.get(1).outcome());
            assertNull(items.get(0).error());

            assertFalse("根下不应再有 a.txt", storage.exists("a.txt"));
            assertFalse("根下不应再有 dir", storage.exists("dir"));
            assertTrue("批次内应有 a.txt",
                    storage.exists(Trash.TRASH_DIR + "/" + batch + "/a.txt"));
            assertEquals("A", new String(storage.readBytes(
                    Trash.TRASH_DIR + "/" + batch + "/a.txt"), "UTF-8"));
        } finally {
            storage.close();
        }
    }

    @Test
    public void listBatchesNewestFirst() throws Exception {
        FileStorage storage = local();
        try {
            storage.upload("x.txt", "X".getBytes("UTF-8"));
            storage.upload("y.txt", "Y".getBytes("UTF-8"));
            Trash.moveToTrash(storage, Arrays.asList("x.txt"), "20260101-000000", true);
            Trash.moveToTrash(storage, Arrays.asList("y.txt"), "20260202-000000", true);
            List<Trash.Batch> batches = Trash.list(storage);
            assertEquals(2, batches.size());
            assertEquals("20260202-000000", batches.get(0).name());
            assertEquals("20260101-000000", batches.get(1).name());
            assertEquals(1, batches.get(0).entries().size());
            assertEquals("y.txt", batches.get(0).entries().get(0).name());
        } finally {
            storage.close();
        }
    }

    @Test
    public void restoreWithConflictAutoNumbering() throws Exception {
        FileStorage storage = local();
        try {
            storage.upload("note.txt", "V1".getBytes("UTF-8"));
            Trash.moveToTrash(storage, Arrays.asList("note.txt"), "20260303-000000", true);
            // 恢复时目标已有一个 note.txt（用户重新建的）：自动编号
            storage.upload("note.txt", "NEW".getBytes("UTF-8"));
            List<String> restored = Trash.restore(storage, "20260303-000000",
                    Arrays.asList("note.txt"), "");
            assertEquals(Arrays.asList("note (2).txt"), restored);
            assertEquals("NEW", new String(storage.readBytes("note.txt"), "UTF-8"));
            assertEquals("V1", new String(storage.readBytes("note (2).txt"), "UTF-8"));
        } finally {
            storage.close();
        }
    }

    @Test
    public void cleanupRemovesExpiredBatchesOnly() throws Exception {
        FileStorage storage = local();
        try {
            storage.upload("old.txt", "O".getBytes("UTF-8"));
            storage.upload("new.txt", "N".getBytes("UTF-8"));
            Trash.moveToTrash(storage, Arrays.asList("old.txt"), "20200101-000000", true);
            Trash.moveToTrash(storage, Arrays.asList("new.txt"), "20991231-235959", true);
            int removed = Trash.cleanup(storage, Duration.ofDays(30));
            assertEquals(1, removed);
            assertFalse(storage.exists(Trash.TRASH_DIR + "/20200101-000000"));
            assertTrue(storage.exists(Trash.TRASH_DIR + "/20991231-235959"));
        } finally {
            storage.close();
        }
    }

    @Test
    public void unsupportedRenameFallsBackToDeleteWhenAllowed() throws Exception {
        FileStorage storage = noRenameProxy(local());
        try {
            storage.upload("hard.txt", "H".getBytes("UTF-8"));
            List<Trash.Item> items = Trash.moveToTrash(storage, Arrays.asList("hard.txt"),
                    "20260404-000000", true);
            assertEquals(Trash.Outcome.DELETED, items.get(0).outcome());
            assertFalse(storage.exists("hard.txt"));
            assertFalse("回退路径不应留下批次目录", storage.exists(Trash.TRASH_DIR));

            List<Trash.Item> strict = Trash.moveToTrash(storage, Arrays.asList("hard.txt"),
                    "20260404-000001", false);
            assertEquals(Trash.Outcome.FAILED, strict.get(0).outcome());
            assertTrue(strict.get(0).error().contains("rename"));
        } finally {
            storage.close();
        }
    }

    @Test
    public void onlyUnsupportedRenameFallsBackOtherFailuresRecordFailed() throws Exception {
        // rename 的 IO_ERROR 绝不能当成「不支持」而硬删（历史回归：只认 UNSUPPORTED）
        FileStorage ioFail = proxyFailing(local(), "rename", StorageErrorCode.IO_ERROR);
        List<Trash.Item> items = Trash.moveToTrash(ioFail, Arrays.asList("f.txt"),
                "20260505-000000", true);
        assertEquals(Trash.Outcome.FAILED, items.get(0).outcome());
        assertFalse(items.get(0).error().isEmpty());

        // 批次目录创建失败：显性 FAILED 并带原因，不静默跳过
        FileStorage mkdirFail = (FileStorage) java.lang.reflect.Proxy.newProxyInstance(
                FileStorage.class.getClassLoader(), new Class<?>[]{FileStorage.class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getName().equals("exists")) {
                            return Boolean.FALSE;
                        }
                        if (method.getName().equals("mkdir")) {
                            throw StorageException.invalidConfig("permission denied");
                        }
                        throw new AssertionError("Unexpected storage call: " + method.getName());
                    }
                });
        items = Trash.moveToTrash(mkdirFail, Arrays.asList("f.txt"), "20260505-000001", true);
        assertEquals(Trash.Outcome.FAILED, items.get(0).outcome());
        assertTrue(items.get(0).error().contains("permission denied"));
    }

    /** 指定操作抛指定错误码的代理（其余转发到本地盘）。 */
    private static FileStorage proxyFailing(FileStorage target, String operation,
                                            StorageErrorCode code) throws Exception {
        return (FileStorage) java.lang.reflect.Proxy.newProxyInstance(
                FileStorage.class.getClassLoader(), new Class<?>[]{FileStorage.class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args)
                            throws Throwable {
                        if (method.getName().equals(operation)) {
                            throw new StorageException(code, "注入的失败", "-");
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    @Test
    public void noTrashDirListsEmpty() throws Exception {
        FileStorage storage = local();
        try {
            assertTrue(Trash.list(storage).isEmpty());
            assertEquals(0, Trash.cleanup(storage, Duration.ofDays(1)));
        } finally {
            storage.close();
        }
    }

    /** rename 一律抛 UNSUPPORTED 的代理（模拟 Git/IMAP 等不支持 rename 的引擎）。 */
    private static FileStorage noRenameProxy(FileStorage target) {
        return (FileStorage) Proxy.newProxyInstance(FileStorage.class.getClassLoader(),
                new Class<?>[]{FileStorage.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("rename")) {
                            throw StorageException.unsupported("该平台不支持 rename（测试桩）");
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }
}
