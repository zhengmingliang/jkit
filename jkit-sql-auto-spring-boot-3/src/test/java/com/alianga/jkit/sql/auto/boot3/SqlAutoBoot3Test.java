package com.alianga.jkit.sql.auto.boot3;

import com.alianga.jkit.sql.auto.boot3.fixture.BootUser;

import org.junit.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.Assert.assertTrue;

/**
 * Spring Boot 3 自动配置在 H2 上建表。
 *
 * @author 郑明亮
 */
public class SqlAutoBoot3Test {

    @Test
    public void createsTableOnStartup() throws Exception {
        String url = "jdbc:h2:mem:boot3_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true";
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("spring.datasource.url", url);
        props.put("spring.datasource.username", "sa");
        props.put("spring.datasource.password", "");
        props.put("spring.datasource.driver-class-name", "org.h2.Driver");
        props.put("jkit.sql.auto.entities", BootUser.class.getName());
        props.put("jkit.sql.auto.show-sql", "false");
        SpringApplication app = new SpringApplication(App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(props);
        ConfigurableApplicationContext ctx = app.run();
        try {
            DataSource ds = ctx.getBean(DataSource.class);
            Connection c = ds.getConnection();
            try {
                ResultSet rs = c.getMetaData().getTables(null, null, "BOOT3_USER", new String[] {"TABLE"});
                try {
                    assertTrue("BOOT3_USER should exist", rs.next());
                } finally {
                    rs.close();
                }
            } finally {
                c.close();
            }
        } finally {
            ctx.close();
        }
    }

    /**
     * 最小启动类。
     */
    @SpringBootApplication
    public static class App {
    }

    @Test
    public void skipsGracefullyWithoutDataSourceAndUrl() {
        // 没有 DataSource bean、也没配 jkit.sql.auto.url：上下文应正常启动并跳过同步。
        // 用 @ImportAutoConfiguration 精确装配，避免全量自动配置对 DataSource 的依赖。
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("jkit.sql.auto.entities", BootUser.class.getName());
        props.put("jkit.sql.auto.show-sql", "false");
        props.put("jkit.sql.auto.phase", "ready");
        SpringApplication app = new SpringApplication(NoDataSourceApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(props);
        ConfigurableApplicationContext ctx = app.run();
        try {
            SqlAutoAutoConfiguration.SqlAutoStartupListener listener =
                    ctx.getBean(SqlAutoAutoConfiguration.SqlAutoStartupListener.class);
            assertTrue(listener.runNow().isEmpty());
        } finally {
            ctx.close();
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration(SqlAutoAutoConfiguration.class)
    static class NoDataSourceApp {
    }
}
