package com.alianga.jkit.config;

import com.alianga.jkit.io.PropertiesLoaderUtils;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigReloaderTest {

    @Test
    public void testReloadPropertiesAndYaml() throws Exception {
        File dir = Files.createTempDirectory("jkit-reload-").toFile();
        dir.deleteOnExit();
        File props = new File(dir, "sys-app.properties");
        File yaml = new File(dir, "sys-app.yml");
        Files.write(props.toPath(), "alpha=one\n".getBytes(StandardCharsets.UTF_8));
        Files.write(yaml.toPath(), "beta: two\n".getBytes(StandardCharsets.UTF_8));
        props.deleteOnExit();
        yaml.deleteOnExit();

        Map<String, String> target = new ConcurrentHashMap<String, String>();
        ConfigReloader reloader = ConfigReloader.builder()
                .directory(dir)
                .target(target)
                .build();
        assertEquals(2, reloader.reload());
        assertEquals("one", target.get("alpha"));
        assertEquals("two", target.get("beta"));

        Files.write(props.toPath(), "alpha=three\n".getBytes(StandardCharsets.UTF_8));
        // some FS have 1s mtime resolution
        props.setLastModified(System.currentTimeMillis() + 1000L);
        assertEquals(1, reloader.reload());
        assertEquals("three", target.get("alpha"));
        assertEquals("two", target.get("beta"));
    }

    @Test
    public void testSkipUnmatchedFiles() throws Exception {
        File dir = Files.createTempDirectory("jkit-reload-skip-").toFile();
        dir.deleteOnExit();
        File other = new File(dir, "other.properties");
        Files.write(other.toPath(), "x=1\n".getBytes(StandardCharsets.UTF_8));
        other.deleteOnExit();

        Map<String, String> target = new ConcurrentHashMap<String, String>();
        ConfigReloader reloader = ConfigReloader.builder().directory(dir).target(target).build();
        assertEquals(0, reloader.reload());
        assertTrue(target.isEmpty());
    }

    @Test
    public void testLoadPropertiesFileNoRecursion() throws Exception {
        File file = File.createTempFile("jkit-props-", ".properties");
        file.deleteOnExit();
        Files.write(file.toPath(), "hello=world\n中文=值\n".getBytes(StandardCharsets.UTF_8));
        Properties properties = PropertiesLoaderUtils.loadProperties(file);
        assertEquals("world", properties.getProperty("hello"));
        assertEquals("值", properties.getProperty("中文"));
    }
}
