package com.alianga.jkit.sql.auto.boot2;

import com.alianga.jkit.sql.auto.SqlAutoSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jkit.sql.auto.*} 绑定。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@ConfigurationProperties(prefix = "jkit.sql.auto")
public class SqlAutoProperties extends SqlAutoSettings {
}
