package com.alianga.jkit.storage;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 工厂门面测试：平台清单、配置驱动创建、必填校验与规划中平台的提示。
 *
 * @author 郑明亮
 */
public class FileStoragesTest {
    @Test
    public void builtinPlatformsPresent() {
        List<StoragePlatform> platforms = FileStorages.platforms();
        assertTrue("内置平台应不少于 30 个: " + platforms.size(), platforms.size() >= 30);
        Map<String, String> expect = new LinkedHashMap<String, String>();
        expect.put("local", "local");
        expect.put("ftp", "ftp");
        expect.put("sftp", "sftp");
        expect.put("webdav", "webdav");
        expect.put("amazon-s3", "s3");
        expect.put("amazon-s3-v2", "s3");
        expect.put("aliyun-oss", "oss");
        expect.put("azure-blob", "azure-blob");
        expect.put("go-fastdfs", "go-fastdfs");
        expect.put("cloudflare-r2", "s3");
        expect.put("minio", "s3");
        for (Map.Entry<String, String> entry : expect.entrySet()) {
            StoragePlatform platform = FileStorages.platform(entry.getKey());
            assertNotNull("缺少平台 " + entry.getKey(), platform);
            assertEquals("平台 " + entry.getKey() + " 引擎不符", entry.getValue(), platform.engine());
        }
    }

    @Test
    public void unknownPlatformFails() {
        try {
            FileStorages.of("no-such-platform", StorageConfig.empty());
            throw new AssertionError("应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void missingRequiredFieldFails() {
        try {
            FileStorages.of("aliyun-oss", StorageConfig.empty().set("bucket", "b"));
            throw new AssertionError("应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("缺少必填配置"));
        }
    }

    @Test
    public void plannedPlatformFailsWithUnsupported() {
        try {
            FileStorages.of("fastdfs", StorageConfig.empty()
                    .set("endpoint", "http://x").set("bucket", "b")
                    .set("accessKey", "a").set("secretKey", "s"));
            throw new AssertionError("规划中平台应抛 UNSUPPORTED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        }
    }

    @Test
    public void configDrivenCreation() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("platform", "local");
        values.put("basePath", System.getProperty("java.io.tmpdir"));
        FileStorage storage = FileStorages.of(values);
        assertEquals("local", storage.platform());
        try {
            FileStorages.of(new LinkedHashMap<String, String>());
            throw new AssertionError("缺少 platform 应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void platformDefaultsMerged() {
        StoragePlatform minio = FileStorages.platform("minio");
        assertEquals("true", minio.defaults().get("pathStyle"));
        StoragePlatform cos = FileStorages.platform("tencent-cos");
        assertEquals("true", cos.defaults().get("md5Secret"));
        StoragePlatform ks3 = FileStorages.platform("kingsoft-ks3");
        assertEquals("ksyun", ks3.defaults().get("authStyle"));
        StoragePlatform s3v2 = FileStorages.platform("amazon-s3-v2");
        assertEquals("2", s3v2.defaults().get("listVersion"));
        assertNull(FileStorages.platform("ghost"));
    }
}
