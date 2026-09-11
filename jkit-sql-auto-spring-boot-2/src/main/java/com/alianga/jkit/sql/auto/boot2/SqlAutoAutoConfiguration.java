package com.alianga.jkit.sql.auto.boot2;

import com.alianga.jkit.sql.auto.SqlAuto;
import com.alianga.jkit.sql.auto.SqlAutoOptions;
import com.alianga.jkit.sql.auto.SqlAutoPlan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Spring Boot 2：应用就绪后按实体同步表结构。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@Configuration
@ConditionalOnClass({SqlAuto.class, DataSource.class})
@ConditionalOnProperty(prefix = "jkit.sql.auto", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SqlAutoProperties.class)
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
public class SqlAutoAutoConfiguration {
    /**
     * @param properties 绑定配置
     * @param dataSource 数据源，可空（没有则用 url）
     * @return 启动监听
     */
    @Bean
    @ConditionalOnMissingBean(SqlAutoStartupListener.class)
    public SqlAutoStartupListener sqlAutoStartupListener(SqlAutoProperties properties,
                                                         ObjectProvider<DataSource> dataSources) {
        return new SqlAutoStartupListener(properties, dataSources.getIfAvailable());
    }

    /**
     * 启动后执行一次 {@link SqlAuto#run}。
     */
    public static final class SqlAutoStartupListener
            implements ApplicationListener<ApplicationReadyEvent>, Ordered {
        private final SqlAutoProperties properties;
        private final DataSource dataSource;

        /**
         * @param properties 配置
         * @param dataSource 数据源
         */
        public SqlAutoStartupListener(SqlAutoProperties properties, DataSource dataSource) {
            this.properties = properties;
            this.dataSource = dataSource;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            SqlAutoOptions options = properties.toOptions();
            if (dataSource != null) {
                options.dataSource(dataSource);
            }
            if (dataSource != null) {
                Connection conn = null;
                try {
                    conn = dataSource.getConnection();
                    SqlAuto.run(conn, options);
                } catch (SQLException e) {
                    throw new IllegalStateException("jkit-sql-auto failed", e);
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException ignored) {
                            // 忽略
                        }
                    }
                }
            } else {
                SqlAuto.run(options);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        /**
         * 测试用：立刻跑一遍。
         *
         * @return 计划
         */
        public SqlAutoPlan runNow() {
            SqlAutoOptions options = properties.toOptions();
            if (dataSource != null) {
                options.dataSource(dataSource);
                Connection conn = null;
                try {
                    conn = dataSource.getConnection();
                    return SqlAuto.run(conn, options);
                } catch (SQLException e) {
                    throw new IllegalStateException("jkit-sql-auto failed", e);
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException ignored) {
                            // 忽略
                        }
                    }
                }
            }
            return SqlAuto.run(options);
        }
    }
}
