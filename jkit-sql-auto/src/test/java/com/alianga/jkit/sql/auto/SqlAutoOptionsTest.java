package com.alianga.jkit.sql.auto;

import com.alianga.jkit.config.ConfigPropertyResolver;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions;
import com.alianga.jkit.sql.auto.fixture.AutoUser;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 配置绑定。
 *
 * @author 郑明亮
 */
public class SqlAutoOptionsTest {

    @Test
    public void fromPropertiesFile() throws Exception {
        File f = File.createTempFile("jkit-sql-auto", ".properties");
        f.deleteOnExit();
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"));
        try {
            w.write("jkit.sql.auto.enabled=true\n");
            w.write("jkit.sql.auto.mode=validate\n");
            w.write("jkit.sql.auto.packages=com.example.a,com.example.b\n");
            w.write("jkit.sql.auto.dialect=postgres\n");
            w.write("jkit.sql.auto.url=jdbc:postgresql://localhost/db\n");
            w.write("jkit.sql.auto.username=u\n");
            w.write("jkit.sql.auto.fail-fast=false\n");
            w.write("jkit.sql.auto.alter-column=true\n");
            w.write("jkit.sql.auto.create-index=false\n");
            w.write("jkit.sql.auto.dry-run=true\n");
            w.write("jkit.sql.auto.foreign-keys=false\n");
            w.write("jkit.sql.auto.auto-increment=false\n");
            w.write("jkit.sql.auto.postgres-identity-style=serial\n");
            w.write("jkit.sql.auto.lock=false\n");
            w.write("jkit.sql.auto.history=true\n");
            w.write("jkit.sql.auto.history-table=my_history\n");
            w.write("jkit.sql.auto.export=target/schema.sql\n");
        } finally {
            w.close();
        }
        ConfigPropertyResolver resolver = ConfigPropertyResolver.loadFile(f.getAbsolutePath());
        SqlAutoOptions o = SqlAutoOptions.fromConfig(resolver);
        assertTrue(o.enabled());
        assertEquals(SqlAutoMode.VALIDATE, o.mode());
        assertEquals(SqlDialect.POSTGRES, o.dialect());
        assertEquals("jdbc:postgresql://localhost/db", o.url());
        assertEquals("u", o.username());
        assertFalse(o.failFast());
        assertTrue(o.alterColumn());
        assertFalse(o.createIndex());
        assertTrue(o.dryRun());
        assertTrue(o.packages().toString(), o.packages().contains("com.example.a"));
        assertTrue(o.packages().toString(), o.packages().contains("com.example.b"));
        assertFalse(o.foreignKeys());
        assertFalse(o.autoIncrement());
        assertEquals(SqlSchemaConvertOptions.PostgresIdentityStyle.SERIAL, o.postgresIdentityStyle());
        assertFalse(o.lock());
        assertTrue(o.history());
        assertEquals("my_history", o.historyTable());
        assertEquals("target/schema.sql", o.export());
    }

    @Test
    public void defaultsForNewOptions() {
        SqlAutoOptions o = SqlAutoOptions.defaults();
        assertTrue(o.foreignKeys());
        assertTrue(o.autoIncrement());
        assertTrue(o.lock());
        assertFalse(o.history());
        assertEquals("jkit_schema_history", o.historyTable());
        assertEquals(null, o.export());
    }

    @Test
    public void settingsCarryNewOptions() {
        SqlAutoSettings settings = new SqlAutoSettings();
        settings.setForeignKeys(false);
        settings.setAutoIncrement(false);
        settings.setPostgresIdentityStyle("SERIAL");
        settings.setPhase("ready");
        settings.setLock(false);
        settings.setHistory(true);
        settings.setHistoryTable("audit_history");
        settings.setExport("out/ddl.sql");
        SqlAutoOptions o = settings.toOptions();
        assertFalse(o.foreignKeys());
        assertFalse(o.autoIncrement());
        assertEquals(SqlSchemaConvertOptions.PostgresIdentityStyle.SERIAL, o.postgresIdentityStyle());
        assertFalse(o.lock());
        assertTrue(o.history());
        assertEquals("audit_history", o.historyTable());
        assertEquals("out/ddl.sql", o.export());
    }

    @Test
    public void fallbackSpringDatasourceUrl() throws Exception {
        File f = File.createTempFile("jkit-sql-auto-spring", ".properties");
        f.deleteOnExit();
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"));
        try {
            w.write("spring.datasource.url=jdbc:h2:mem:cfg\n");
            w.write("spring.datasource.username=sa\n");
            w.write("jkit.sql.auto.package=com.alianga.jkit.sql.auto.fixture\n");
        } finally {
            w.close();
        }
        SqlAutoOptions o = SqlAutoOptions.fromConfig(ConfigPropertyResolver.loadFile(f.getAbsolutePath()));
        assertEquals("jdbc:h2:mem:cfg", o.url());
        assertEquals("sa", o.username());
        assertEquals("com.alianga.jkit.sql.auto.fixture", o.packages().get(0));
    }

    @Test
    public void collectEntitiesFromClassList() {
        SqlAutoOptions o = SqlAutoOptions.defaults().entities(AutoUser.class);
        List<Class<?>> types = SqlAuto.collectEntities(o);
        assertEquals(1, types.size());
        assertEquals(AutoUser.class, types.get(0));
    }

    @Test
    public void disabledSkips() {
        SqlAutoPlan plan = SqlAuto.run(SqlAutoOptions.defaults().enabled(false).url("jdbc:h2:mem:x"));
        assertTrue(plan.isEmpty());
        plan = SqlAuto.run(SqlAutoOptions.defaults().mode(SqlAutoMode.NONE).url("jdbc:h2:mem:x"));
        assertTrue(plan.isEmpty());
    }
}
