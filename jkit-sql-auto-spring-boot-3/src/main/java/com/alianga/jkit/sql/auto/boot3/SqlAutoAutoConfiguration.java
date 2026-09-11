package com.alianga.jkit.sql.auto.boot3;

import com.alianga.jkit.sql.auto.SqlAuto;
import com.alianga.jkit.sql.auto.SqlAutoOptions;
import com.alianga.jkit.sql.auto.SqlAutoPlan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Spring Boot 3：应用就绪后按实体同步表结构。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({SqlAuto.class, DataSource.class})
@ConditionalOnProperty(prefix = "jkit.sql.auto", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SqlAutoProperties.class)
public class SqlAutoAutoConfiguration {
    /**
     * @param properties 绑定配置
     * @param dataSource 数据源
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
            runNow();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        /**
         * 立刻同步一次。
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
