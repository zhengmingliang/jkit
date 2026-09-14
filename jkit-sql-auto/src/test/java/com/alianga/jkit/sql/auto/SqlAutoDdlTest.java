package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.auto.fixture.AutoUser;
import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlEntities;
import com.alianga.jkit.sql.entity.SqlEntityColumn;
import com.alianga.jkit.sql.entity.SqlEntityModel;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;
import com.alianga.jkit.sql.schema.model.CanonicalType;

import org.junit.Test;

import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DDL 规划（不连库）。
 *
 * @author 郑明亮
 */
public class SqlAutoDdlTest {

    @Test
    public void postgresCommentsAreExtraChanges() {
        @SqlTable(name = "cmt_t", comment = "t")
        class CmtT {
            @SqlId
            @SqlGenerated
            long id;
            @SqlColumn(comment = "n")
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(CmtT.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.POSTGRES,
                SqlAutoOptions.defaults());
        assertTrue(kind(changes, SqlAutoChange.Kind.CREATE_TABLE));
        assertTrue(kind(changes, SqlAutoChange.Kind.COMMENT));
    }

    @Test
    public void sqlServerCommentsAreExtraChanges() {
        @SqlTable(name = "cmt_t", comment = "t")
        class CmtT {
            @SqlId
            @SqlGenerated
            long id;
            @SqlColumn(comment = "n")
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(CmtT.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.SQLSERVER,
                SqlAutoOptions.defaults().createIndex(false));
        assertTrue(kind(changes, SqlAutoChange.Kind.CREATE_TABLE));
        assertTrue(kind(changes, SqlAutoChange.Kind.COMMENT));
        String sql = sql(changes);
        assertTrue(sql, sql.contains("sp_addextendedproperty"));
        assertFalse(sql, sql.contains("CREATE SEQUENCE"));
        assertFalse("CREATE TABLE 不应夹带附录 COMMENT",
                changes.get(0).sql().contains("sp_addextendedproperty"));
    }

    @Test
    public void hiveInlinesComments() {
        @SqlTable(name = "cmt_t", comment = "t")
        class CmtT {
            @SqlId
            long id;
            @SqlColumn(comment = "n")
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(CmtT.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.HIVE,
                SqlAutoOptions.defaults().createIndex(false));
        assertTrue(kind(changes, SqlAutoChange.Kind.CREATE_TABLE));
        assertFalse(kind(changes, SqlAutoChange.Kind.COMMENT));
        assertTrue(changes.get(0).sql(), changes.get(0).sql().contains("COMMENT 't'"));
    }

    @Test
    public void oracleSequenceExtra() {
        @SqlTable(name = "seq_t")
        class SeqT {
            @SqlId
            @SqlGenerated
            long id;
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(SeqT.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.ORACLE,
                SqlAutoOptions.defaults());
        assertTrue(kind(changes, SqlAutoChange.Kind.SEQUENCE));
        boolean sawSeq = false;
        boolean sawTrigger = false;
        for (int i = 0; i < changes.size(); i++) {
            String s = changes.get(i).sql().toUpperCase();
            if (s.contains("CREATE SEQUENCE")) {
                sawSeq = true;
            }
            if (s.contains("TRIGGER")) {
                sawTrigger = true;
            }
        }
        assertTrue(sawSeq);
        assertTrue(sawTrigger);
        List<String> drops = SqlAutoDdl.dropSequenceSql(model, SqlDialect.ORACLE);
        assertEquals(1, drops.size());
        assertTrue(drops.get(0), drops.get(0).contains("DROP SEQUENCE seq_t_id_seq"));
        assertTrue(SqlAutoDdl.dropSequenceSql(model, SqlDialect.MYSQL).isEmpty());
        assertTrue(SqlAutoDdl.dropSequenceSql(model, SqlDialect.DAMENG).isEmpty());
    }

    @Test
    public void damengUsesIdentityNotSequence() {
        @SqlTable(name = "seq_t")
        class SeqT {
            @SqlId
            @SqlGenerated
            long id;
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(SeqT.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.DAMENG,
                SqlAutoOptions.defaults().createIndex(false));
        assertFalse(kind(changes, SqlAutoChange.Kind.SEQUENCE));
        String create = changes.get(0).sql().toUpperCase();
        assertTrue(changes.get(0).sql(), create.contains("IDENTITY"));
        assertFalse(changes.get(0).sql(), create.contains("GENERATED"));
        int pk = create.indexOf("PRIMARY KEY");
        int idn = create.indexOf("IDENTITY");
        assertTrue(changes.get(0).sql(), pk >= 0 && idn > pk);
    }

    @Test
    public void missingTableCreates() {
        SqlEntityModel model = SqlEntities.inspect(AutoUser.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.H2,
                SqlAutoOptions.defaults().mode(SqlAutoMode.UPDATE));
        assertTrue(kind(changes, SqlAutoChange.Kind.CREATE_TABLE));
        assertTrue(kind(changes, SqlAutoChange.Kind.CREATE_INDEX));
        assertTrue(changes.get(0).sql().toUpperCase().contains("CREATE TABLE"));
    }

    @Test
    public void missingTableValidate() {
        SqlEntityModel model = SqlEntities.inspect(AutoUser.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.MYSQL,
                SqlAutoOptions.defaults().mode(SqlAutoMode.VALIDATE));
        assertEquals(1, changes.size());
        assertEquals(SqlAutoChange.Kind.VALIDATE, changes.get(0).kind());
    }

    @Test
    public void existingTableSyncsCommentsWhenDifferent() {
        @SqlTable(name = "cmt_t", comment = "users")
        class CmtT {
            @SqlId
            long id;
            @SqlColumn(comment = "name")
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(CmtT.class);
        Map<String, SqlAutoLiveColumn> cols = new LinkedHashMap<String, SqlAutoLiveColumn>();
        cols.put("id", new SqlAutoLiveColumn("id", "BIGINT", Types.BIGINT, 19, 0, 0, null));
        cols.put("name", new SqlAutoLiveColumn("name", "VARCHAR", Types.VARCHAR, 255, 0, 1, "old"));
        SqlAutoLiveTable live = new SqlAutoLiveTable("cmt_t", cols,
                Collections.<String, SqlAutoLiveIndex>emptyMap(), "old-table");
        List<SqlAutoChange> pg = SqlAutoDdl.planTable(model, live, SqlDialect.POSTGRES,
                SqlAutoOptions.defaults().createIndex(false));
        assertTrue(sql(pg), kind(pg, SqlAutoChange.Kind.COMMENT));
        assertTrue(sql(pg), sql(pg).contains("COMMENT ON TABLE cmt_t IS 'users'"));
        assertTrue(sql(pg), sql(pg).contains("COMMENT ON COLUMN cmt_t.name IS 'name'"));

        List<SqlAutoChange> mysql = SqlAutoDdl.planTable(model, live, SqlDialect.MYSQL,
                SqlAutoOptions.defaults().createIndex(false));
        assertTrue(sql(mysql), sql(mysql).contains("ALTER TABLE cmt_t COMMENT 'users'"));
        assertTrue(sql(mysql), sql(mysql).toUpperCase().contains("MODIFY"));
    }

    @Test
    public void existingTableSkipsCommentWhenAlreadyEqual() {
        @SqlTable(name = "cmt_t", comment = "users")
        class CmtT {
            @SqlId
            long id;
            @SqlColumn(comment = "name")
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(CmtT.class);
        Map<String, SqlAutoLiveColumn> cols = new LinkedHashMap<String, SqlAutoLiveColumn>();
        cols.put("id", new SqlAutoLiveColumn("id", "BIGINT", Types.BIGINT, 19, 0, 0, null));
        cols.put("name", new SqlAutoLiveColumn("name", "VARCHAR", Types.VARCHAR, 255, 0, 1, "name"));
        SqlAutoLiveTable live = new SqlAutoLiveTable("cmt_t", cols,
                Collections.<String, SqlAutoLiveIndex>emptyMap(), "users");
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, live, SqlDialect.POSTGRES,
                SqlAutoOptions.defaults().createIndex(false));
        assertFalse(sql(changes), kind(changes, SqlAutoChange.Kind.COMMENT));
    }

    @Test
    public void existingTableDoesNotWipeLiveCommentWhenEntityHasNone() {
        @SqlTable(name = "cmt_t")
        class CmtT {
            @SqlId
            long id;
            String name;
        }
        SqlEntityModel model = SqlEntities.inspect(CmtT.class);
        Map<String, SqlAutoLiveColumn> cols = new LinkedHashMap<String, SqlAutoLiveColumn>();
        cols.put("id", new SqlAutoLiveColumn("id", "BIGINT", Types.BIGINT, 19, 0, 0, "pk"));
        cols.put("name", new SqlAutoLiveColumn("name", "VARCHAR", Types.VARCHAR, 255, 0, 1, "n"));
        SqlAutoLiveTable live = new SqlAutoLiveTable("cmt_t", cols,
                Collections.<String, SqlAutoLiveIndex>emptyMap(), "keep-me");
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, live, SqlDialect.POSTGRES,
                SqlAutoOptions.defaults().createIndex(false));
        assertFalse(sql(changes), kind(changes, SqlAutoChange.Kind.COMMENT));
    }

    @Test
    public void addMissingColumn() {
        SqlEntityModel model = SqlEntities.inspect(AutoUser.class);
        Map<String, SqlAutoLiveColumn> cols = new LinkedHashMap<String, SqlAutoLiveColumn>();
        cols.put("id", col("id", "BIGINT", Types.BIGINT, 19));
        cols.put("user_name", col("user_name", "VARCHAR", Types.VARCHAR, 32));
        SqlAutoLiveTable live = new SqlAutoLiveTable("auto_user", cols,
                Collections.<String, SqlAutoLiveIndex>emptyMap());
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, live, SqlDialect.H2,
                SqlAutoOptions.defaults().createIndex(false));
        assertTrue(sql(changes), kind(changes, SqlAutoChange.Kind.ADD_COLUMN));
        assertTrue(sql(changes), sql(changes).toUpperCase().contains("ADD"));
        assertTrue(sql(changes), sql(changes).contains("email") || sql(changes).contains("age"));
    }

    @Test
    public void typeMismatchSkippedUnlessAlterColumn() {
        SqlEntityModel model = SqlEntities.inspect(AutoUser.class);
        SqlEntityColumn name = model.columns().get(1);
        assertEquals("user_name", name.columnName());
        Map<String, SqlAutoLiveColumn> cols = fullLive(model);
        cols.put("user_name", col("user_name", "VARCHAR", Types.VARCHAR, 8));
        SqlAutoLiveTable live = new SqlAutoLiveTable("auto_user", cols,
                Collections.<String, SqlAutoLiveIndex>emptyMap());
        List<SqlAutoChange> noAlter = SqlAutoDdl.planTable(model, live, SqlDialect.MYSQL,
                SqlAutoOptions.defaults().createIndex(false));
        assertFalse(sql(noAlter), kind(noAlter, SqlAutoChange.Kind.ALTER_COLUMN));

        List<SqlAutoChange> alter = SqlAutoDdl.planTable(model, live, SqlDialect.MYSQL,
                SqlAutoOptions.defaults().createIndex(false).alterColumn(true));
        assertTrue(sql(alter), kind(alter, SqlAutoChange.Kind.ALTER_COLUMN));
        assertTrue(sql(alter), sql(alter).toUpperCase().contains("MODIFY"));
    }

    @Test
    public void compatibleIntegerWidening() {
        SqlEntityColumn wanted = new SqlEntityColumn("age", CanonicalType.INT, null, null,
                true, false, false, false, null);
        SqlAutoLiveColumn live = col("age", "BIGINT", Types.BIGINT, 19);
        assertTrue(SqlAutoDdl.compatible(wanted, live, SqlDialect.H2));
        SqlAutoLiveColumn tooSmall = col("age", "TINYINT", Types.TINYINT, 3);
        assertFalse(SqlAutoDdl.compatible(wanted, tooSmall, SqlDialect.H2));
    }

    @Test
    public void noneModeEmpty() {
        SqlEntityModel model = SqlEntities.inspect(AutoUser.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.H2,
                SqlAutoOptions.defaults().mode(SqlAutoMode.NONE));
        assertTrue(changes.isEmpty());
    }

    @Test
    public void postgresDuplicateCreateTimeFromSuperclassIsDeduped() {
        class Base {
            java.util.Date createTime;
            java.util.Date updateTime;
            String tenantId;
        }
        @SqlTable(name = "file_source")
        class FileSource extends Base {
            @SqlId
            @SqlGenerated
            String id;
            String title;
            java.util.Date createTime;
            java.util.Date updateTime;
        }
        SqlEntityModel model = SqlEntities.inspect(FileSource.class);
        List<SqlAutoChange> pg = SqlAutoDdl.planTable(model, null, SqlDialect.POSTGRES,
                SqlAutoOptions.defaults());
        String sql = pg.get(0).sql();
        int first = sql.indexOf("create_time");
        assertTrue(sql, first >= 0);
        assertEquals(sql, -1, sql.indexOf("create_time", first + 1));
        assertTrue(sql, sql.contains("tenant_id"));
    }

    @Test
    public void postgresVarcharUuidPkHasNoIdentity() {
        @SqlTable(name = "file_storage")
        class FileStorage {
            @SqlId
            @SqlGenerated
            String id;
            String sourceName;
        }
        SqlEntityModel model = SqlEntities.inspect(FileStorage.class);
        List<SqlAutoChange> pg = SqlAutoDdl.planTable(model, null, SqlDialect.POSTGRES,
                SqlAutoOptions.defaults());
        String sql = pg.get(0).sql().toUpperCase();
        assertTrue(pg.get(0).sql(), sql.contains("VARCHAR"));
        assertTrue(pg.get(0).sql(), sql.contains("PRIMARY KEY"));
        assertFalse(pg.get(0).sql(), sql.contains("IDENTITY"));
        assertFalse(pg.get(0).sql(), sql.contains("SERIAL"));
        List<SqlAutoChange> oracle = SqlAutoDdl.planTable(model, null, SqlDialect.ORACLE,
                SqlAutoOptions.defaults());
        assertFalse(kind(oracle, SqlAutoChange.Kind.SEQUENCE));
        assertFalse(sql(oracle).toUpperCase().contains("IDENTITY"));
    }

    @Test
    public void unnamedIndexesDoNotShareName() {
        @SqlTable(name = "t_schedule_auth", indexes = {"resource_id", "permission_id"})
        class Auth {
            @SqlId
            String id;
        }
        SqlEntityModel model = SqlEntities.inspect(Auth.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.MYSQL,
                SqlAutoOptions.defaults());
        List<SqlAutoChange> idxs = new SqlAutoPlan(changes).ofKind(SqlAutoChange.Kind.CREATE_INDEX);
        assertEquals(2, idxs.size());
        assertTrue(idxs.get(0).sql(), idxs.get(0).sql().contains("t_schedule_auth_resource_id_idx"));
        assertTrue(idxs.get(1).sql(), idxs.get(1).sql().contains("t_schedule_auth_permission_id_idx"));
        assertFalse(idxs.get(0).sql().equals(idxs.get(1).sql()));
    }

    @Test
    public void oracleIndexNamesFitThirtyChars() {
        @SqlTable(name = "t_schedule_auth", indexes = {"resource_id", "permission_id"})
        class Auth {
            @SqlId
            String id;
        }
        SqlEntityModel model = SqlEntities.inspect(Auth.class);
        List<SqlAutoChange> changes = SqlAutoDdl.planTable(model, null, SqlDialect.ORACLE,
                SqlAutoOptions.defaults());
        List<SqlAutoChange> idxs = new SqlAutoPlan(changes).ofKind(SqlAutoChange.Kind.CREATE_INDEX);
        assertEquals(2, idxs.size());
        assertTrue(idxs.get(0).detail().length() <= 30);
        assertTrue(idxs.get(1).detail().length() <= 30);
        assertFalse(idxs.get(0).detail().equals(idxs.get(1).detail()));
        assertFalse(idxs.get(0).sql(), idxs.get(0).sql().contains("t_schedule_auth_resource_id_idx"));
        assertFalse(idxs.get(1).sql(), idxs.get(1).sql().contains("t_schedule_auth_permission_id_idx"));
        assertTrue(idxs.get(0).sql(), idxs.get(0).sql().startsWith("CREATE INDEX "));
        assertTrue(idxs.get(1).sql(), idxs.get(1).sql().startsWith("CREATE INDEX "));
    }

    private static boolean kind(List<SqlAutoChange> changes, SqlAutoChange.Kind kind) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).kind() == kind) {
                return true;
            }
        }
        return false;
    }

    private static String sql(List<SqlAutoChange> changes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(changes.get(i).sql());
        }
        return sb.toString();
    }

    private static Map<String, SqlAutoLiveColumn> fullLive(SqlEntityModel model) {
        Map<String, SqlAutoLiveColumn> cols = new LinkedHashMap<String, SqlAutoLiveColumn>();
        List<SqlEntityColumn> list = model.columns();
        for (int i = 0; i < list.size(); i++) {
            SqlEntityColumn c = list.get(i);
            int jdbc = Types.VARCHAR;
            if (c.canonical() == CanonicalType.BIGINT) {
                jdbc = Types.BIGINT;
            } else if (c.canonical() == CanonicalType.INT) {
                jdbc = Types.INTEGER;
            }
            int size = c.precision() == null ? 0 : c.precision().intValue();
            cols.put(c.columnName().toLowerCase(), col(c.columnName(), c.canonical().name(), jdbc, size));
        }
        return cols;
    }

    private static SqlAutoLiveColumn col(String name, String type, int jdbc, int size) {
        return new SqlAutoLiveColumn(name, type, jdbc, size, 0, 1);
    }
}
