package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.DialectTypeForm;
import com.alianga.jkit.sql.schema.registry.LossyMapping;
import com.alianga.jkit.sql.schema.registry.RegistryValidator;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 内置注册表 CI 自检：覆盖全部方言 × canonical，未声明碰撞必须失败。
 *
 * @author 郑明亮
 */
public class RegistryValidationTest {

    @Test
    public void builtinsPassValidation() {
        RegistryValidator.ValidationResult result =
                RegistryValidator.validate(SqlDataTypeRegistry.builtins());
        if (!result.ok()) {
            fail(result.errors().toString());
        }
        assertTrue(result.errors().isEmpty());
    }

    @Test
    public void everyCanonicalHasEveryDialect() {
        SqlDataTypeRegistry registry = SqlDataTypeRegistry.builtins();
        CanonicalType[] types = CanonicalType.values();
        SqlDialect[] dialects = SqlDialect.values();
        for (int t = 0; t < types.length; t++) {
            CanonicalType type = types[t];
            if (type == CanonicalType.UNKNOWN) {
                continue;
            }
            for (int d = 0; d < dialects.length; d++) {
                assertTrue(type + "/" + dialects[d],
                        registry.form(type, dialects[d]) != null);
            }
        }
    }

    @Test
    public void undeclaredCollisionFailsValidation() {
        SqlDataTypeRegistry custom = new SqlDataTypeRegistry();
        custom.register(CanonicalType.INT, SqlDialect.MYSQL, DialectTypeForm.of("INT"));
        custom.register(CanonicalType.BIGINT, SqlDialect.MYSQL, DialectTypeForm.of("INT"));
        SqlDialect[] all = SqlDialect.values();
        CanonicalType[] types = CanonicalType.values();
        for (int t = 0; t < types.length; t++) {
            if (types[t] == CanonicalType.UNKNOWN) {
                continue;
            }
            for (int d = 0; d < all.length; d++) {
                if (custom.form(types[t], all[d]) == null) {
                    custom.register(types[t], all[d], DialectTypeForm.of(types[t].name() + "_" + all[d]));
                }
            }
        }
        RegistryValidator.ValidationResult result = RegistryValidator.validate(custom);
        assertFalse(result.ok());
        boolean found = false;
        List<String> errors = result.errors();
        for (int i = 0; i < errors.size(); i++) {
            if (errors.get(i).contains("undeclared reverse collision")) {
                found = true;
                break;
            }
        }
        assertTrue(errors.toString(), found);
    }

    @Test
    public void declaredCollisionPasses() {
        SqlDataTypeRegistry custom = new SqlDataTypeRegistry();
        SqlDialect[] all = SqlDialect.values();
        CanonicalType[] types = CanonicalType.values();
        for (int t = 0; t < types.length; t++) {
            if (types[t] == CanonicalType.UNKNOWN) {
                continue;
            }
            for (int d = 0; d < all.length; d++) {
                custom.register(types[t], all[d], DialectTypeForm.of(types[t].name() + "_" + all[d]));
            }
        }
        custom.register(CanonicalType.INT, SqlDialect.MYSQL, DialectTypeForm.of("SAME"));
        custom.register(CanonicalType.BIGINT, SqlDialect.MYSQL, DialectTypeForm.of("SAME"));
        custom.registerLossyMapping(new LossyMapping(SqlDialect.MYSQL, "SAME", CanonicalType.INT,
                EnumSet.of(CanonicalType.INT, CanonicalType.BIGINT)));
        RegistryValidator.ValidationResult result = RegistryValidator.validate(custom);
        assertTrue(result.errors().toString(), result.ok());
        assertEquals(CanonicalType.INT, custom.fromDialect("SAME", SqlDialect.MYSQL));
    }

    @Test
    public void nullRegistryFails() {
        RegistryValidator.ValidationResult result = RegistryValidator.validate(null);
        assertFalse(result.ok());
    }
}
