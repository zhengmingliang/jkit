package com.alianga.jkit.sql.entity;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.entity.fixture.JpaAuth;
import com.alianga.jkit.sql.entity.fixture.JpaOrg;
import com.alianga.jkit.sql.entity.fixture.JpaUser;
import com.alianga.jkit.sql.entity.fixture.MbAliasUser;
import com.alianga.jkit.sql.entity.fixture.MpUser;
import com.alianga.jkit.sql.entity.sample.DemoUser;
import com.alianga.jkit.sql.schema.model.CanonicalType;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 实体扫描与 DDL/DML 生成。
 *
 * @author 郑明亮
 */
public class SqlEntitiesTest {

    @Test
    public void commentsAndOracleSequence() {
        @SqlTable(name = "cmt", comment = "用户表")
        class Cmt {
            @SqlId
            @SqlGenerated
            long id;
            @SqlColumn(comment = "用户名")
            String name;
        }
        String mysql = SqlEntities.createTable(Cmt.class, SqlDialect.MYSQL);
        assertTrue(mysql, mysql.contains("COMMENT '用户名'"));
        assertTrue(mysql, mysql.contains("COMMENT '用户表'"));
        String h2 = SqlEntities.createTable(Cmt.class, SqlDialect.H2);
        assertTrue(h2, h2.contains("COMMENT '用户名'"));
        assertTrue(h2, h2.contains("COMMENT ON TABLE"));

        String pg = SqlEntities.createTable(Cmt.class, SqlDialect.POSTGRES);
        assertTrue(pg, pg.contains("COMMENT ON TABLE cmt IS '用户表'"));
        assertTrue(pg, pg.contains("COMMENT ON COLUMN cmt.name IS '用户名'"));
        assertFalse(pg, pg.contains("COMMENT '用户名'"));

        String oracle = SqlEntities.createTable(Cmt.class, SqlDialect.ORACLE);
        assertTrue(oracle, oracle.contains("CREATE SEQUENCE cmt_id_seq"));
        assertTrue(oracle, oracle.toUpperCase().contains("TRIGGER"));
        assertTrue(oracle, oracle.contains("COMMENT ON TABLE cmt"));

        String sqlserver = SqlEntities.createTable(Cmt.class, SqlDialect.SQLSERVER);
        assertTrue(sqlserver, sqlserver.contains("sp_addextendedproperty"));
        assertTrue(sqlserver, sqlserver.contains("MS_Description"));
        assertTrue(sqlserver, sqlserver.contains("用户表"));
        assertTrue(sqlserver, sqlserver.contains("用户名"));

        String hive = SqlEntities.createTable(Cmt.class, SqlDialect.HIVE);
        assertTrue(hive, hive.contains("COMMENT '用户表'"));
        assertTrue(hive, hive.contains("COMMENT '用户名'"));

        String clickhouse = SqlEntities.createTable(Cmt.class, SqlDialect.CLICKHOUSE);
        assertTrue(clickhouse, clickhouse.contains("COMMENT '用户表'"));
        assertTrue(clickhouse, clickhouse.contains("COMMENT '用户名'"));

        String presto = SqlEntities.createTable(Cmt.class, SqlDialect.PRESTO);
        assertTrue(presto, presto.contains("WITH (comment = '用户表')"));
        assertTrue(presto, presto.contains("COMMENT '用户名'"));

        String sqlite = SqlEntities.createTable(Cmt.class, SqlDialect.SQLITE);
        assertFalse(sqlite, sqlite.toUpperCase().contains("COMMENT"));

        String db2 = SqlEntities.createTable(Cmt.class, SqlDialect.DB2);
        assertTrue(db2, db2.contains("COMMENT ON TABLE cmt IS '用户表'"));
        assertTrue(db2, db2.contains("COMMENT ON COLUMN cmt.name IS '用户名'"));

        String ansi = SqlEntities.createTable(Cmt.class, SqlDialect.ANSI);
        assertTrue(ansi, ansi.contains("COMMENT ON TABLE cmt"));
        assertTrue(ansi, ansi.contains("COMMENT ON COLUMN cmt.name"));

        assertTrue(SqlEntities.needsSequenceFallback(SqlDialect.ORACLE));
        assertFalse(SqlEntities.needsSequenceFallback(SqlDialect.ORACLE12));
        assertFalse(SqlEntities.needsSequenceFallback(SqlDialect.DAMENG));
        assertFalse(SqlEntities.needsSequenceFallback(SqlDialect.MYSQL));
        assertEquals("cmt_id_seq", SqlEntities.sequenceName("cmt", "id"));
        assertTrue(SqlEntities.sequenceSql("cmt", SqlEntities.inspect(Cmt.class).idColumn(),
                SqlDialect.ORACLE).contains("CREATE SEQUENCE"));
        assertNull(SqlEntities.sequenceSql("cmt", SqlEntities.inspect(Cmt.class).idColumn(),
                SqlDialect.MYSQL));
    }

    @Test
    public void hibernateCommentAndColumnDefault() {
        @Comment("授权表")
        class HbAuth {
            @SqlId
            String id;
            @Comment("资源id")
            @ColumnDefault("''")
            String resourceId;
            @ColumnDefault("0")
            Integer status;
            @ColumnDefault("CURRENT_TIMESTAMP")
            java.util.Date createdAt;
        }
        SqlEntityModel model = SqlEntities.inspect(HbAuth.class);
        assertEquals("授权表", model.comment());
        SqlEntityColumn resource = model.columns().get(1);
        assertEquals("资源id", resource.comment());
        assertEquals("''", resource.defaultValue());
        assertEquals("0", model.columns().get(2).defaultValue());
        assertEquals("CURRENT_TIMESTAMP", model.columns().get(3).defaultValue());

        String mysql = SqlEntities.createTable(HbAuth.class, SqlDialect.MYSQL);
        assertTrue(mysql, mysql.contains("COMMENT '授权表'"));
        assertTrue(mysql, mysql.contains("COMMENT '资源id'"));
        assertTrue(mysql, mysql.contains("DEFAULT ''"));
        assertTrue(mysql, mysql.contains("DEFAULT 0"));
        assertTrue(mysql, mysql.contains("DEFAULT CURRENT_TIMESTAMP"));

        String pg = SqlEntities.createTable(HbAuth.class, SqlDialect.POSTGRES);
        assertTrue(pg, pg.contains("COMMENT ON TABLE"));
        assertTrue(pg, pg.contains("DEFAULT ''"));
        assertTrue(pg, pg.contains("COMMENT ON COLUMN"));
    }

    @Test
    public void inspectMapsJavaTypesAndAnnotations() {
        SqlEntityModel model = SqlEntities.inspect(DemoUser.class);
        assertEquals("demo_user", model.tableName());
        assertEquals(5, model.columns().size());
        SqlEntityColumn id = model.idColumn();
        assertNotNull(id);
        assertEquals("id", id.columnName());
        assertTrue(id.primaryKey());
        assertTrue(id.autoIncrement());
        assertEquals(CanonicalType.BIGINT, id.canonical());
        assertEquals("user_name", model.columns().get(1).columnName());
        assertFalse(model.columns().get(1).nullable());
        assertEquals(CanonicalType.VARCHAR, model.columns().get(1).canonical());
        assertEquals(Integer.valueOf(32), model.columns().get(1).precision());
        assertTrue(model.columns().get(2).unique());
        assertEquals(CanonicalType.DECIMAL, model.columns().get(4).canonical());
    }

    @Test
    public void scanFindsSqlTableEntities() {
        List<Class<?>> found = SqlEntities.scan("com.alianga.jkit.sql.entity.sample");
        assertEquals(1, found.size());
        assertEquals(DemoUser.class, found.get(0));
    }

    @Test
    public void createTableMysqlAndPostgres() {
        String mysql = SqlEntities.createTable(DemoUser.class, SqlDialect.MYSQL);
        String u = mysql.toUpperCase();
        assertTrue(mysql, u.contains("CREATE TABLE"));
        assertTrue(mysql, mysql.contains("demo_user"));
        assertTrue(mysql, u.contains("AUTO_INCREMENT"));
        assertTrue(mysql, u.contains("PRIMARY KEY"));
        assertTrue(mysql, u.contains("BIGINT"));
        assertTrue(mysql, u.contains("VARCHAR(32)"));
        assertTrue(mysql, u.contains("DECIMAL(10,2)"));
        assertFalse(mysql, mysql.contains("cache"));
        SQL.parse(mysql, SqlDialect.MYSQL);

        String pg = SqlEntities.createTable(DemoUser.class, SqlDialect.POSTGRES);
        String pu = pg.toUpperCase();
        assertTrue(pg, pu.contains("GENERATED"));
        assertTrue(pg, pu.contains("IDENTITY"));
        assertTrue(pg, pu.contains("INTEGER"));
        assertFalse(pg, pu.contains("AUTO_INCREMENT"));
        SQL.parse(pg, SqlDialect.POSTGRES);

        String sqlite = SqlEntities.createTable(DemoUser.class, SqlDialect.SQLITE);
        String su = sqlite.toUpperCase();
        assertTrue(sqlite, su.contains("PRIMARY KEY"));
        int pk = su.indexOf("PRIMARY KEY");
        int auto = su.indexOf("AUTOINCREMENT");
        assertTrue(sqlite, auto > pk);
        SQL.parse(sqlite, SqlDialect.SQLITE);
    }

    @Test
    public void createTablesFromPackage() {
        String ddl = SqlEntities.createTables("com.alianga.jkit.sql.entity.sample", SqlDialect.MYSQL);
        assertTrue(ddl, ddl.toUpperCase().contains("CREATE TABLE"));
        SQL.parse(ddl, SqlDialect.MYSQL);
    }

    @Test
    public void dmlFromInstance() {
        DemoUser u = new DemoUser();
        u.setName("alice");
        u.setEmail("a@b.c");
        u.setAge(Integer.valueOf(18));
        u.setAmount(new BigDecimal("1.50"));
        String ins = SqlEntities.insert(u, SqlDialect.MYSQL);
        assertTrue(ins.toUpperCase(), ins.toUpperCase().contains("INSERT"));
        assertTrue(ins, ins.contains("alice"));
        assertFalse(ins, ins.contains("id"));
        SQL.parse(ins, SqlDialect.MYSQL);

        u.setId(Long.valueOf(9));
        String upd = SqlEntities.updateById(u, SqlDialect.MYSQL);
        assertTrue(upd.toUpperCase(), upd.toUpperCase().contains("UPDATE"));
        assertTrue(upd, upd.contains("user_name") || upd.contains("alice"));
        SQL.parse(upd, SqlDialect.MYSQL);

        String del = SqlEntities.deleteById(DemoUser.class, Long.valueOf(9), SqlDialect.MYSQL);
        assertTrue(del.toUpperCase(), del.toUpperCase().contains("DELETE"));
        SQL.parse(del, SqlDialect.MYSQL);

        String sel = SqlEntities.selectById(DemoUser.class, Long.valueOf(9), SqlDialect.MYSQL);
        assertTrue(sel.toUpperCase(), sel.toUpperCase().contains("SELECT"));
        SQL.parse(sel, SqlDialect.MYSQL);

        String all = SqlEntities.selectAll(DemoUser.class, SqlDialect.POSTGRES);
        SQL.parse(all, SqlDialect.POSTGRES);

        String ph = SqlEntities.insertPlaceholders(DemoUser.class, SqlDialect.MYSQL);
        assertTrue(ph, ph.contains("?"));
        SQL.parse(ph, SqlDialect.MYSQL);
    }

    @Test
    public void insertBatchAndIndexes() {
        DemoUser a = new DemoUser();
        a.setName("a");
        DemoUser b = new DemoUser();
        b.setName("b");
        String batch = SqlEntities.insertBatch(java.util.Arrays.asList(a, b), SqlDialect.MYSQL);
        assertTrue(batch, batch.contains("INSERT"));
        assertTrue(batch.split(";").length >= 2);
        String ddl = SqlEntities.createTable(DemoUser.class, SqlDialect.POSTGRES);
        assertTrue(ddl, ddl.toUpperCase().contains("CREATE INDEX"));
    }

    @Test
    public void columnSqlAndCreateIndex() {
        SqlEntityModel model = SqlEntities.inspect(DemoUser.class);
        String col = SqlEntities.columnSql(model.columns().get(1), SqlDialect.MYSQL, false);
        assertTrue(col, col.contains("user_name"));
        assertTrue(col.toUpperCase(), col.toUpperCase().contains("VARCHAR"));
        assertFalse(col.toUpperCase(), col.toUpperCase().contains("PRIMARY KEY"));
        String type = SqlEntities.columnTypeSql(model.idColumn(), SqlDialect.POSTGRES);
        assertTrue(type, type.toUpperCase().contains("BIGINT") || type.toUpperCase().contains("INT"));
        String idx = SqlEntities.createIndex("demo_user", "idx_email:email");
        assertTrue(idx, idx.toUpperCase().contains("CREATE INDEX"));
        assertTrue(idx, idx.contains("idx_email"));
        assertEquals("idx_email", SqlEntities.indexName("demo_user", "idx_email:email"));
        assertEquals("demo_user_email_idx", SqlEntities.indexName("demo_user", "email"));
    }

    @Test
    public void unnamedJpaIndexesGetDistinctNames() {
        SqlEntityModel model = SqlEntities.inspect(JpaAuth.class);
        assertEquals(2, model.indexes().size());
        String i0 = SqlEntities.createIndex(model.tableName(), model.indexes().get(0));
        String i1 = SqlEntities.createIndex(model.tableName(), model.indexes().get(1));
        assertTrue(i0, i0.contains("t_schedule_auth_resource_id_idx"));
        assertTrue(i1, i1.contains("t_schedule_auth_permission_id_idx"));
        assertFalse(i0 + " vs " + i1, i0.equals(i1));
        String ddl = SqlEntities.createTable(JpaAuth.class, SqlDialect.MYSQL);
        assertTrue(ddl, ddl.contains("t_schedule_auth_resource_id_idx"));
        assertTrue(ddl, ddl.contains("t_schedule_auth_permission_id_idx"));
        assertFalse(ddl, ddl.contains("t_schedule_auth_idx ON"));
    }

    @Test
    public void dropTable() {
        String sql = SqlEntities.dropTable(DemoUser.class, SqlDialect.MYSQL);
        assertTrue(sql.toUpperCase().contains("DROP TABLE"));
        assertTrue(sql.contains("demo_user"));
    }

    @Test
    public void snakeCaseDefaultTableName() {
        @SqlTable
        class OrderItem {
            @SqlId
            long id;
            String skuCode;
        }
        SqlEntityModel m = SqlEntities.inspect(OrderItem.class);
        assertEquals("order_item", m.tableName());
        assertEquals("sku_code", m.columns().get(1).columnName());
    }

    @Test
    public void mybatisPlusAnnotations() {
        SqlEntityModel m = SqlEntities.inspect(MpUser.class);
        assertEquals("mp_user", m.tableName());
        assertEquals("uid", m.idColumn().columnName());
        assertTrue(m.idColumn().autoIncrement());
        assertEquals("user_name", m.columns().get(1).columnName());
        assertEquals(2, m.columns().size());
        String ddl = SqlEntities.createTable(MpUser.class, SqlDialect.MYSQL);
        assertTrue(ddl, ddl.contains("uid"));
        assertFalse(ddl, ddl.contains("cache"));
        SQL.parse(ddl, SqlDialect.MYSQL);
    }

    @Test
    public void mybatisAliasTableNameFallback() {
        SqlEntityModel m = SqlEntities.inspect(MbAliasUser.class);
        assertEquals("mb_alias_user", m.tableName());
        assertTrue(m.idColumn().primaryKey());
    }

    @Test
    public void jpaIndexEnumEmbeddedSkipCollectionAndFkOrder() {
        SqlEntityModel user = SqlEntities.inspect(JpaUser.class);
        boolean sawStatus = false;
        boolean sawCity = false;
        boolean sawTags = false;
        boolean sawChildren = false;
        boolean sawOrgFk = false;
        for (int i = 0; i < user.columns().size(); i++) {
            SqlEntityColumn c = user.columns().get(i);
            if ("status".equals(c.columnName())) {
                sawStatus = true;
                assertEquals(CanonicalType.INT, c.canonical());
            }
            if ("city".equals(c.columnName()) || "street".equals(c.columnName())) {
                sawCity = true;
            }
            if ("tags".equals(c.columnName())) {
                sawTags = true;
            }
            if ("children".equals(c.columnName())) {
                sawChildren = true;
            }
            if ("jpa_org".equals(c.referencesTable()) || "org_id".equals(c.columnName())) {
                sawOrgFk = true;
            }
        }
        assertTrue("ordinal enum", sawStatus);
        assertTrue("embedded city/street", sawCity);
        assertFalse("skip List tags", sawTags);
        assertFalse("skip OneToMany", sawChildren);
        assertTrue("org fk", sawOrgFk);
        SqlEntityModel org = SqlEntities.inspect(JpaOrg.class);
        assertEquals(1, org.indexes().size());
        assertTrue(org.indexes().get(0), org.indexes().get(0).contains("code"));

        String ddl = SqlEntities.createTables(
                java.util.Arrays.<Class<?>>asList(JpaUser.class, JpaOrg.class), SqlDialect.MYSQL);
        int orgAt = ddl.indexOf("jpa_org");
        int userAt = ddl.indexOf("jpa_user");
        assertTrue(ddl, orgAt >= 0 && userAt >= 0 && orgAt < userAt);
        SQL.parse(ddl, SqlDialect.MYSQL);
    }
}
