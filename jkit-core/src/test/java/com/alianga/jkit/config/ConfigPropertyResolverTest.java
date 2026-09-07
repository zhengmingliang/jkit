package com.alianga.jkit.config;

import com.alianga.jkit.PropertiesUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConfigPropertyResolverTest {
    private String previousProfiles;

    @Before
    public void setUp() {
        previousProfiles = System.getProperty("spring.profiles.active");
        System.clearProperty("spring.profiles.active");
        ConfigPropertyResolver.reset();
    }

    @After
    public void tearDown() {
        if (previousProfiles == null) {
            System.clearProperty("spring.profiles.active");
        } else {
            System.setProperty("spring.profiles.active", previousProfiles);
        }
        System.clearProperty("jkit.config.test.flag");
        System.clearProperty("server.port");
        ConfigPropertyResolver.reset();
        PropertiesUtil.removeKeys("application.yml");
        PropertiesUtil.removeKeys("jkit-custom.yml");
        PropertiesUtil.removeKeys("jkit-custom.properties");
    }

    private static ConfigLoadOptions isolated(String... names) {
        return ConfigLoadOptions.of(names)
                .locations("classpath:/", "classpath:/config/")
                .enableEnvironment(false)
                .enableSystemProperties(false);
    }

    @Test
    public void testSpringBootApplicationYmlAndProfile() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
                ConfigLoadOptions.defaults()
                        .locations("classpath:/", "classpath:/config/")
                        .enableEnvironment(false)
                        .enableSystemProperties(false));

        assertEquals("8200", resolver.getString("server.port"));
        assertEquals(Integer.valueOf(8200), resolver.getInt("server.port"));
        assertEquals("500KB", resolver.getString("server.max-http-request-header-size"));
        assertEquals("70000", resolver.getString("server.tomcat.connection-timeout"));

        assertEquals("distributed", resolver.getString("spring.mode"));
        assertEquals("sample-backend", resolver.getString("spring.application.name"));
        assertEquals("prod", resolver.getString("spring.profiles.active"));
        assertEquals("UTF-8", resolver.getString("spring.messages.encoding"));
        assertTrue(resolver.getBoolean("spring.main.allow-circular-references"));
        assertEquals("redis", resolver.getString("spring.cache.type"));
        assertEquals("classpath:ehcache/ehcache.xml", resolver.getString("spring.cache.jcache.config"));
        assertEquals("app-", resolver.getString("spring.cache.redis.key-prefix"));
        assertEquals("127.0.0.1", resolver.getString("spring.data.redis.host"));
        assertEquals(Integer.valueOf(6379), resolver.getInt("spring.data.redis.port"));
        assertEquals(Integer.valueOf(10), resolver.getInt("spring.data.redis.database"));
        assertTrue(resolver.getBoolean("spring.jackson.parser.allow-numeric-leading-zeros"));
        assertEquals("500MB", resolver.getString("spring.servlet.multipart.max-file-size"));
        assertEquals("500MB", resolver.getString("spring.servlet.multipart.max-request-size"));

        assertFalse(resolver.getBoolean("management.health.ldap.enabled"));
        assertFalse(resolver.getBoolean("management.health.redis.enabled"));
        assertEquals("*", resolver.getString("management.endpoints.web.exposure.include"));
        assertEquals("configprops,heapdump",
                resolver.getString("management.endpoints.web.exposure.exclude"));
        assertEquals("always", resolver.getString("management.endpoint.health.show-details"));

        assertTrue(resolver.getBoolean("mybatis.configuration.map-underscore-to-camel-case"));
        assertEquals("logs/app", resolver.getString("logging.file.path"));

        assertTrue(resolver.getBoolean("quartz.enabled"));
        assertEquals("syncJob", resolver.getString("quartz.scheduler-name"));
        assertEquals("org.quartz.impl.jdbcjobstore.PostgreSQLDelegate",
                resolver.getString("quartz.properties.org.quartz.jobStore.driverDelegateClass"));

        assertEquals("@project.version@", resolver.getString("app.version"));
        assertEquals(Integer.valueOf(2880), resolver.getInt("app.login_timeout"));
        assertTrue(resolver.getBoolean("app.front-distributed"));
        assertEquals("http://localhost:8080", resolver.getString("app.origin-list"));
        assertTrue(resolver.getBoolean("app.metadataSync.enable"));

        assertEquals("/actuator/**, /druid/**, /sysParameter/**", resolver.getString("whitelist.paths"));
        List<String> paths = resolver.getList("whitelist.paths", String.class);
        assertEquals(3, paths.size());
        assertEquals("/actuator/**", paths.get(0));
        assertEquals("/sysParameter/**", paths.get(2));
        assertEquals("http://127.0.0.1:9180", resolver.getString("whitelist.gateway-api.domain"));
        assertEquals("demo-gateway-key", resolver.getString("whitelist.gateway-api.key"));
        assertEquals("drivers", resolver.getString("whitelist.path.driver"));
        assertEquals("data", resolver.getString("whitelist.path.data"));
        assertEquals("ehcache", resolver.getString("whitelist.path.ehcache"));
        assertEquals("data/static-resource", resolver.getString("whitelist.path.static-resource"));
        assertTrue(resolver.getBoolean("whitelist.marketTemplate.find"));

        assertTrue(resolver.getBoolean("demo.datasource.init-demo"));
        assertFalse(resolver.getBoolean("demo.datasource.auto-sync"));
        assertEquals("auth-service/sso", resolver.getString("demo.feign.sso"));

        assertEquals("/swagger-ui.html", resolver.getString("springdoc.swagger-ui.path"));
        assertEquals("alpha", resolver.getString("springdoc.swagger-ui.tags-sorter"));
        assertEquals("/v3/api-docs", resolver.getString("springdoc.api-docs.path"));
        assertTrue(resolver.getBoolean("knife4j.enable"));
        assertEquals("zh_cn", resolver.getString("knife4j.setting.language"));
        assertFalse(resolver.getBoolean("knife4j.setting.enable-swagger-models"));

        assertEquals("admin", resolver.getString("druid.monitor.login-username"));
        assertEquals("admin123", resolver.getString("druid.monitor.login-password"));
        assertFalse(resolver.getBoolean("druid.monitor.reset-enable"));
        assertEquals("*.js,*.gif,*.jpg,*.png,*.css,*.ico,/druid/*",
                resolver.getString("druid.monitor.exclusions"));

        assertTrue(resolver.getBoolean("spring.cloud.nacos.discovery.enabled"));
        assertEquals("127.0.0.1:8848",
                resolver.getString("spring.cloud.nacos.discovery.server-addr"));
        assertTrue(resolver.getString("spring.datasource.url").startsWith("jdbc:postgresql://"));
        assertEquals("demo", resolver.getString("spring.datasource.username"));
        assertEquals("secret", resolver.getString("spring.datasource.password"));
        assertEquals("i18n/core,i18n/messages",
                resolver.getString("spring.messages.basename"));
        assertFalse(resolver.getBoolean("spring.flyway.enabled"));
        assertEquals("classpath:/db/change-log/master.xml",
                resolver.getString("spring.liquibase.change-log"));
        assertTrue(resolver.getBoolean("spring.liquibase.enabled"));
        assertTrue(resolver.getBoolean("spring.liquibase.clear-checksums"));

        assertEquals("auth-server",
                resolver.getString("spring.security.oauth2.client.registration.internal-client.provider"));
        assertEquals("demo-client",
                resolver.getString("spring.security.oauth2.client.registration.internal-client.client-id"));
        assertEquals("demo-secret",
                resolver.getString(
                        "spring.security.oauth2.client.registration.internal-client.client-secret"));
        assertEquals("client_secret_post", resolver.getString(
                "spring.security.oauth2.client.registration.internal-client.client-authentication-method"));
        assertEquals("client_credentials", resolver.getString(
                "spring.security.oauth2.client.registration.internal-client.authorization-grant-type"));
        assertEquals("manager",
                resolver.getString("spring.security.oauth2.client.registration.internal-client.scope"));
        assertEquals("http://auth-service/sso/oauth/token",
                resolver.getString("spring.security.oauth2.client.provider.auth-server.token-uri"));
        assertEquals("classpath:mybatis/*.xml", resolver.getString("mybatis-plus.mapper-locations"));

        assertEquals("drivers", resolver.getExp("${whitelist.path.driver:/opt/drivers}"));
        assertEquals("/opt/missing", resolver.getExp("${not.exist:/opt/missing}"));
    }

    @Test
    public void testServerPortFromSystemProperty() {
        System.setProperty("server.port", "9991");
        try {
            assertEquals(Integer.valueOf(9991), ConfigPropertyResolver.load().getInt("server.port"));
        } finally {
            System.clearProperty("server.port");
        }
    }

    @Test
    public void testClasspathConfigOverridesRoot() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(isolated("jkit-prio"));
        assertEquals("classpath-config", resolver.getString("jkit.source"));
        assertEquals("from-root", resolver.getString("jkit.shared"));
        assertEquals(Integer.valueOf(2), resolver.getInt("jkit.port"));
        assertEquals("root-a", resolver.getString("jkit.list[0]"));
        assertEquals("root-b", resolver.getString("jkit.list[1]"));
    }

    @Test
    public void testProfileFileOverridesBase() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
                isolated("jkit-prio").activeProfiles("dev"));
        assertEquals("profile-dev", resolver.getString("jkit.source"));
        assertEquals("from-root", resolver.getString("jkit.shared"));
        assertEquals(Integer.valueOf(3), resolver.getInt("jkit.port"));
        assertEquals("yes", resolver.getString("jkit.profile-only"));
    }

    @Test
    public void addLocationAppendsExpandsHomeAndSkipsDuplicates() {
        String home = System.getProperty("user.home");
        ConfigLoadOptions options = ConfigLoadOptions.defaults()
                .addLocation("~/jkit-extra-loc")
                .addLocation("  ", "~/jkit-extra-loc");
        List<String> locations = options.getLocations();
        assertTrue(locations.contains("classpath:/"));
        assertTrue(locations.contains("file:./"));
        String expected = "file:" + home + "/jkit-extra-loc";
        String expectedWin = "file:" + home + "\\jkit-extra-loc";
        assertTrue(locations.contains(expected) || locations.contains(expectedWin));
        int count = 0;
        for (String location : locations) {
            if (location.endsWith("jkit-extra-loc")) {
                count++;
            }
        }
        assertEquals(1, count);
    }

    @Test
    public void addLocationFileDirectoryOverridesClasspath() throws Exception {
        File dir = Files.createTempDirectory("jkit-config-").toFile();
        dir.deleteOnExit();
        File yml = new File(dir, "jkit-prio.yml");
        Files.write(yml.toPath(), "jkit:\n  source: added-dir\n".getBytes(StandardCharsets.UTF_8));
        yml.deleteOnExit();

        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
                isolated("jkit-prio").addLocation(dir));
        assertEquals("added-dir", resolver.getString("jkit.source"));
        assertEquals("from-root", resolver.getString("jkit.shared"));
    }

    @Test
    public void addLocationFileUsesParentWhenPathIsFile() throws Exception {
        File dir = Files.createTempDirectory("jkit-config-").toFile();
        dir.deleteOnExit();
        File yml = new File(dir, "jkit-prio.yml");
        Files.write(yml.toPath(), "jkit:\n  source: parent-of-file\n".getBytes(StandardCharsets.UTF_8));
        yml.deleteOnExit();

        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
                isolated("jkit-prio").addLocation(yml));
        assertEquals("parent-of-file", resolver.getString("jkit.source"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void addLocationRejectsEmpty() {
        ConfigLoadOptions.defaults().addLocation();
    }

    @Test
    public void testFileLocationOverridesClasspath() throws Exception {
        File dir = Files.createTempDirectory("jkit-config-").toFile();
        dir.deleteOnExit();
        File yml = new File(dir, "jkit-prio.yml");
        Files.write(yml.toPath(), "jkit:\n  source: file-dir\n  port: 9\n".getBytes(StandardCharsets.UTF_8));
        yml.deleteOnExit();

        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
                isolated("jkit-prio").locations(
                        "classpath:/",
                        "classpath:/config/",
                        "file:" + dir.getAbsolutePath() + "/"));
        assertEquals("file-dir", resolver.getString("jkit.source"));
        assertEquals(Integer.valueOf(9), resolver.getInt("jkit.port"));
        assertEquals("from-root", resolver.getString("jkit.shared"));
    }

    @Test
    public void testExtensionPriorityYamlOverProperties() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(isolated("jkit-ext"));
        assertEquals("from-yaml", resolver.getString("jkit.ext"));
        assertEquals("props-only", resolver.getString("jkit.only-props"));
        assertEquals("yml-only", resolver.getString("jkit.only-yml"));
        assertEquals("yaml-only", resolver.getString("jkit.only-yaml"));
    }

    @Test
    public void testSystemPropertyOverridesFile() {
        System.setProperty("jkit.source", "from-system");
        try {
            ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
                    ConfigLoadOptions.of("jkit-prio")
                            .locations("classpath:/", "classpath:/config/")
                            .enableEnvironment(false)
                            .enableSystemProperties(true));
            assertEquals("from-system", resolver.getString("jkit.source"));
        } finally {
            System.clearProperty("jkit.source");
        }
    }

    @Test
    public void testCustomNamedFileWithoutSpringLayout() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.loadFile("jkit-custom.yml");
        assertEquals("yaml-custom", resolver.getString("custom.name"));
        assertEquals(Integer.valueOf(30), resolver.getInt("custom.timeout"));
        assertTrue(resolver.getBoolean("custom.enabled"));
        List<String> items = resolver.getList("custom.items", String.class);
        assertEquals(3, items.size());
        assertEquals("three", items.get(2));
        List<Integer> ids = resolver.getList("custom.ids", Integer.class);
        assertEquals(3, ids.size());
        assertEquals(Integer.valueOf(2), ids.get(1));
        assertEquals("a", resolver.getString("custom.nested[0].name"));
        assertEquals("20", resolver.getString("custom.nested[1].value"));
        assertEquals("http://localhost:30", resolver.getString("custom.url"));
    }

    @Test
    public void testLoadFileLaterOverridesEarlier() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.loadFile(
                "jkit-custom.properties", "jkit-custom.yml");
        assertEquals("yaml-custom", resolver.getString("custom.name"));
        assertEquals("true", resolver.getString("custom.fromProps"));
    }

    @Test
    public void testPlaceholders() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(isolated("jkit-placeholders"));
        assertEquals("http://localhost:9999", resolver.getString("app.url"));
        assertEquals("fallback", resolver.getString("app.missing"));
        assertEquals("http://localhost:9999/api", resolver.getString("app.nested"));
        assertEquals("9999", resolver.getExp("${app.port}"));
    }

    @Test
    public void testCircularPlaceholder() {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("a", "${b}");
        props.put("b", "${a}");
        try {
            PlaceholderResolver.resolveAll(props);
            throw new AssertionError("expected circular placeholder exception");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("circular"));
        }
    }

    @Test
    public void testResolveValueNullAndDefault() {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("host", "127.0.0.1");
        assertNull(PlaceholderResolver.resolveValue(null, props, new HashSet<String>()));
        assertEquals("127.0.0.1",
                PlaceholderResolver.resolveValue("${host}", props, new HashSet<String>()));
        assertEquals("none",
                PlaceholderResolver.resolveValue("${missing:none}", props, new HashSet<String>()));
        assertEquals("",
                PlaceholderResolver.resolveValue("${missing}", props, new HashSet<String>()));
    }

    @Test
    public void testGettersDefaultAndMissing() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(isolated("jkit-custom"));
        assertEquals("fallback", resolver.getString("not.exist", "fallback"));
        assertEquals(Integer.valueOf(8), resolver.getInt("not.exist", 8));
        assertEquals(Long.valueOf(9L), resolver.getLong("not.exist", 9L));
        assertEquals(Double.valueOf(1.5), resolver.getDouble("not.exist", 1.5));
        assertEquals(Boolean.TRUE, resolver.getBoolean("not.exist", true));
        assertNull(resolver.get("not.exist"));
        assertFalse(resolver.contains("not.exist"));
        assertTrue(resolver.contains("custom.name"));
        assertTrue(resolver.keys().contains("custom.name"));
        assertEquals("yaml-custom", resolver.toProperties().getProperty("custom.name"));
    }

    @Test
    public void testPropertiesUtilSharesYamlReader() throws Exception {
        assertTrue(PropertiesUtil.loadFromClassPath("jkit-custom.yml"));
        assertEquals("yaml-custom", PropertiesUtil.getString("custom.name"));
        assertEquals("30", PropertiesUtil.getString("custom.timeout"));
        assertEquals("one", PropertiesUtil.getString("custom.items[0]"));

        assertTrue(PropertiesUtil.loadFromClassPath("application.yml"));
        assertEquals("8200", PropertiesUtil.getString("server.port"));
        assertEquals("standalone", PropertiesUtil.getString("spring.mode"));

        File yaml = File.createTempFile("jkit-props-", ".yml");
        yaml.deleteOnExit();
        Files.write(yaml.toPath(), "path-key: from-file\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(PropertiesUtil.loadFromPath(yaml.getAbsolutePath(), false));
        assertEquals("from-file", PropertiesUtil.getString("path-key"));
        PropertiesUtil.removeKeys(yaml.getAbsolutePath());
    }

    @Test
    public void testRelaxedEnvKey() {
        assertEquals("server.port", ConfigPropertyResolver.toRelaxedKey("SERVER_PORT"));
        assertEquals("spring.profiles.active",
                ConfigPropertyResolver.toRelaxedKey("SPRING_PROFILES_ACTIVE"));
    }

    @Test
    public void testInstanceReset() {
        ConfigPropertyResolver first = ConfigPropertyResolver.instance();
        assertNotNull(first.getString("server.port"));
        ConfigPropertyResolver.reset();
        ConfigPropertyResolver second = ConfigPropertyResolver.instance();
        assertNotNull(second.getString("spring.application.name"));
    }

    public static class RedisBind {
        private String host;
        private int port;
        private int database;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }
    }

    public static class AppBind {
        private String version;
        private int loginTimeout;
        private boolean frontDistributed;
        private String originList;

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public int getLoginTimeout() {
            return loginTimeout;
        }

        public void setLoginTimeout(int loginTimeout) {
            this.loginTimeout = loginTimeout;
        }

        public boolean isFrontDistributed() {
            return frontDistributed;
        }

        public void setFrontDistributed(boolean frontDistributed) {
            this.frontDistributed = frontDistributed;
        }

        public String getOriginList() {
            return originList;
        }

        public void setOriginList(String originList) {
            this.originList = originList;
        }
    }

    public static class NestedItem {
        private String name;
        private int value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    public static class CustomBind {
        private String name;
        private int timeout;
        private boolean enabled;
        private List<String> items;
        private List<NestedItem> nested;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getItems() {
            return items;
        }

        public void setItems(List<String> items) {
            this.items = items;
        }

        public List<NestedItem> getNested() {
            return nested;
        }

        public void setNested(List<NestedItem> nested) {
            this.nested = nested;
        }
    }

    @Test
    public void testBindPrefixToObject() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.load(
                ConfigLoadOptions.defaults()
                        .locations("classpath:/", "classpath:/config/")
                        .enableEnvironment(false)
                        .enableSystemProperties(false));

        RedisBind redis = resolver.getObject("spring.data.redis", RedisBind.class);
        assertNotNull(redis);
        assertEquals("127.0.0.1", redis.getHost());
        assertEquals(6379, redis.getPort());
        assertEquals(10, redis.getDatabase());

        AppBind app = resolver.getObject("app", AppBind.class);
        assertEquals("@project.version@", app.getVersion());
        assertEquals(2880, app.getLoginTimeout());
        assertTrue(app.isFrontDistributed());
        assertEquals("http://localhost:8080", app.getOriginList());

        Map<String, Object> demoDs = resolver.getMap("demo.datasource");
        assertEquals("true", String.valueOf(demoDs.get("init-demo")));
        assertEquals("false", String.valueOf(demoDs.get("auto-sync")));
        assertNull(resolver.getObject("not.exist.prefix", RedisBind.class));
    }

    @Test
    public void testBindCustomFilePrefix() {
        ConfigPropertyResolver resolver = ConfigPropertyResolver.loadFile("jkit-custom.yml");
        CustomBind custom = resolver.getObject("custom", CustomBind.class);
        assertEquals("yaml-custom", custom.getName());
        assertEquals(30, custom.getTimeout());
        assertTrue(custom.isEnabled());
        assertEquals(3, custom.getItems().size());
        assertEquals("two", custom.getItems().get(1));
        assertEquals(2, custom.getNested().size());
        assertEquals("a", custom.getNested().get(0).getName());
        assertEquals(20, custom.getNested().get(1).getValue());
    }
}
