package com.alianga.jkit.sql.entity;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.entity.sample.DemoUser;
import com.alianga.jkit.sql.schema.model.CanonicalType;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 实体扫描与 DDL/DML 生成。
 *
 * @author 郑明亮
 */
public class SqlEntitiesTest {

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
}
