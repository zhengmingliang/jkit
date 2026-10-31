package com.alianga.jkit.storage.cap;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.engine.LocalFileStorage;
import com.alianga.jkit.storage.StorageConfig;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 能力元数据注册表：界面按能力渲染的依据，回归锁引擎级声明的读取路径。
 *
 * @author 郑明亮
 */
public class StorageCapabilitiesTest {

    @Test
    public void unknownEngineFallsBackToStandard() {
        StorageCapabilities caps = FileStorages.capabilitiesOf("no-such-engine");
        assertFalse(caps.isReadOnly());
        assertFalse(caps.hasNativeRecycleBin());
        assertFalse(caps.supportsPresignUpload());
        assertFalse(caps.supportsRange());
        assertEquals(-1L, caps.maxUploadBytes());
    }

    @Test
    public void readOnlyEnginesAreDeclared() {
        assertTrue("qqmail-attach 只读",
                FileStorages.capabilitiesOf("qqmail-attach").isReadOnly());
        assertFalse("s3 可写", FileStorages.capabilitiesOf("s3").isReadOnly());
    }

    @Test
    public void nativeRecycleBinEnginesAreDeclared() {
        assertTrue(FileStorages.capabilitiesOf("yun139").hasNativeRecycleBin());
        assertTrue(FileStorages.capabilitiesOf("cmcloud").hasNativeRecycleBin());
        assertFalse(FileStorages.capabilitiesOf("ftp").hasNativeRecycleBin());
    }

    @Test
    public void gitDeclaresInlineUploadLimit() {
        assertEquals(20L * 1024L * 1024L,
                FileStorages.capabilitiesOf("git").maxUploadBytes());
        assertEquals(-1L, FileStorages.capabilitiesOf("s3").maxUploadBytes());
    }

    @Test
    public void instanceCapabilitiesMergeInstanceRange() throws Exception {
        LocalFileStorage local = new LocalFileStorage(
                StorageConfig.empty().set("basePath",
                        java.nio.file.Files.createTempDirectory("caps-test").toString()));
        assertTrue("本地文件系统支持断点续传", local.capabilities().supportsRange());
        assertFalse("引擎注册默认无回收站", local.capabilities().hasNativeRecycleBin());
        local.close();
    }

    @Test
    public void registerOverridesAndIsIdempotent() {
        StorageCapabilities custom = StorageCapabilities.standard()
                .withReadOnly().withMaxUploadBytes(7L);
        FileStorages.registerCapabilities("caps-test-engine", custom);
        try {
            assertTrue(FileStorages.capabilitiesOf("caps-test-engine").isReadOnly());
            assertEquals(7L, FileStorages.capabilitiesOf("caps-test-engine").maxUploadBytes());
            FileStorages.registerCapabilities("caps-test-engine",
                    StorageCapabilities.standard());
            assertFalse(FileStorages.capabilitiesOf("caps-test-engine").isReadOnly());
        } finally {
            // 恢复为未注册态，避免影响其它用例
            FileStorages.registerCapabilities("caps-test-engine",
                    StorageCapabilities.standard().withMaxUploadBytes(-1L));
        }
    }
}
