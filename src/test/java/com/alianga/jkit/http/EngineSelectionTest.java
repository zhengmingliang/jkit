package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 守卫测试：确认测试环境实际加载到的引擎。
 * <p>
 * JDK11 引擎编译到 {@code META-INF/versions/11}，只有在多版本 JAR 或把该目录显式加进
 * classpath 时才可加载（pom 的 surefire 已配 additionalClasspathElements）。
 * 一旦这个配置被误删，测试会静默退回 HttpURLConnection，JDK 11+ 的实现就再没人覆盖了。
 *
 * @author 郑明亮
 */
public class EngineSelectionTest {
    @Test
    public void jdkHttpClientEngineIsExercisedOnJdk11Plus() {
        String version = System.getProperty("java.version");
        String engine = HttpUtils.getHttpEngine().name();
        System.out.println("[EngineSelectionTest] java=" + version + ", engine=" + engine);
        int major = major(version);
        if (major >= 11) {
            assertEquals("JDK 11+ 下测试必须跑在 java.net.http.HttpClient 引擎上，"
                            + "否则 src/main/java11 的实现没有任何测试覆盖"
                            + "（检查 pom 里 surefire 的 additionalClasspathElements）",
                    HttpEngines.JDK_HTTP_CLIENT, engine);
        } else {
            assertEquals(HttpEngines.URL_CONNECTION, engine);
        }
    }

    @Test
    public void urlConnectionEngineStaysAvailableAsJdk8Path() {
        HttpEngine engine = HttpEngines.urlConnection();
        assertEquals(HttpEngines.URL_CONNECTION, engine.name());
        assertTrue(engine instanceof UrlConnectionHttpEngine);
    }

    private static int major(String version) {
        String value = version == null ? "" : version;
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int dot = value.indexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}
