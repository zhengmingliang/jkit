package com.alianga.jkit.storage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 路径规范化与工具方法测试。
 *
 * @author 郑明亮
 */
public class StoragePathsTest {
    @Test
    public void normalize() {
        assertEquals("", StoragePaths.normalize(null));
        assertEquals("", StoragePaths.normalize(""));
        assertEquals("", StoragePaths.normalize("/"));
        assertEquals("", StoragePaths.normalize("///"));
        assertEquals("a/b/c", StoragePaths.normalize("/a/b/c/"));
        assertEquals("a/b/c", StoragePaths.normalize("a//b///c"));
        assertEquals("a/b/c", StoragePaths.normalize("\\a\\b\\c\\"));
        assertEquals("a/b/c", StoragePaths.normalize("/a/./b/./c"));
        assertEquals("a/c", StoragePaths.normalize("/a/b/../c"));
        assertEquals("c", StoragePaths.normalize("/../../c"));
        assertEquals("a b/中文", StoragePaths.normalize("a b/中文"));
    }

    @Test
    public void joinParentName() {
        assertEquals("a/b", StoragePaths.join("a", "b"));
        assertEquals("a/b", StoragePaths.join("/a/", "/b"));
        assertEquals("b", StoragePaths.join("", "b"));
        assertEquals("a", StoragePaths.join("a", ""));
        assertEquals("a", StoragePaths.parent("a/b"));
        assertEquals("", StoragePaths.parent("a"));
        assertEquals("", StoragePaths.parent(""));
        assertEquals("c", StoragePaths.name("a/b/c"));
        assertEquals("a", StoragePaths.name("a"));
        assertEquals("", StoragePaths.name(""));
    }

    @Test
    public void extensionAndUnder() {
        assertEquals("txt", StoragePaths.extension("a/b.TXT"));
        assertEquals("gz", StoragePaths.extension("a/b.tar.gz"));
        assertEquals("", StoragePaths.extension("noext"));
        assertEquals("", StoragePaths.extension(".hidden"));
        assertTrue(StoragePaths.isUnder("a", "a/b/c"));
        assertTrue(StoragePaths.isUnder("a", "a"));
        assertTrue(StoragePaths.isUnder("", "anything"));
        assertFalse(StoragePaths.isUnder("a", "ab"));
        assertEquals("b/c", StoragePaths.relativize("a", "a/b/c"));
        assertEquals(null, StoragePaths.relativize("a", "ab/c"));
        assertEquals("a", StoragePaths.relativize("", "a"));
    }
}
