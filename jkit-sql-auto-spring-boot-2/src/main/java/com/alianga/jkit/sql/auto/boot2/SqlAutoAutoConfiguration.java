package com.alianga.jkit.sql.auto.boot2;

import com.alianga.jkit.log.Log;
import com.alianga.jkit.sql.auto.SqlAuto;
import com.alianga.jkit.sql.auto.SqlAutoOptions;
import com.alianga.jkit.sql.auto.SqlAutoPlan;
import org.springframework.beans.factory.InitializingBean;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring Boot 2：按实体同步表结构。
 *
 * <p>默认 {@code jkit.sql.auto.phase=eager}：在上下文刷新期（DataSource 就绪后、
 * Web 端口开放前）执行，同步失败时应用在开始接收流量前就失败；配置
 * {@code phase=ready} 恢复 2.0.1 的「应用就绪事件后执行」行为。
 * 没有可用的 DataSource 且未配置 {@code jkit.sql.auto.url} 时跳过，不让应用启动失败。</p>
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
     * 按配置的阶段执行一次 {@link SqlAuto#run}。
     */
    public static final class SqlAutoStartupListener
            implements ApplicationListener<ApplicationReadyEvent>, InitializingBean, Ordered {
        private static final Log LOG = Log.get(SqlAutoStartupListener.class);

        private final SqlAutoProperties properties;
        private final DataSource dataSource;
        private final AtomicBoolean ran = new AtomicBoolean();

        /**
         * @param properties 配置
         * @param dataSource 数据源
         */
        public SqlAutoStartupListener(SqlAutoProperties properties, DataSource dataSource) {
            this.properties = properties;
            this.dataSource = dataSource;
        }

        /**
         * eager 阶段：bean 初始化即执行，此时 Web 容器尚未开始接收请求。
         */
        @Override
        public void afterPropertiesSet() {
            if (isEager()) {
                runNow();
            }
        }

        /**
         * ready 阶段：应用就绪事件后执行；eager 已执行过则跳过。
         */
        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            if (!ran.get()) {
                runNow();
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
         * 立刻同步一次（重复调用幂等）。
         *
         * @return 计划；没有可用数据源时返回空计划
         */
        public SqlAutoPlan runNow() {
            if (!ran.compareAndSet(false, true)) {
                return SqlAutoPlan.empty();
            }
            SqlAutoOptions options = properties.toOptions();
            if (dataSource == null && (options.url() == null || options.url().isEmpty())) {
                // 组件化引入 starter、又没配数据源的场景：跳过而不是让应用启动失败
                LOG.warn("jkit-sql-auto: no DataSource bean and no jkit.sql.auto.url configured, skipped");
                return SqlAutoPlan.empty();
            }
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

        private boolean isEager() {
            String phase = properties.getPhase();
            return phase == null || phase.trim().isEmpty() || !"ready".equalsIgnoreCase(phase.trim());
        }
    }
}
