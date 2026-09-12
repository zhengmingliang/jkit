package com.alianga.jkit.sql.entity;

import com.alianga.jkit.sql.schema.model.CanonicalType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 把实体类解析成 {@link SqlEntityModel}。认 jkit 注解，并用反射认（无编译依赖）：
 * JPA {@code javax/jakarta.persistence}、MyBatis-Plus {@code com.baomidou.mybatisplus.annotation}、
 * MyBatis {@code org.apache.ibatis.type.Alias}（仅作表名回退）、
 * 任意简单名为 {@code Comment} 的注解（类=表注释、字段=列注释，读 {@code value}/{@code comment}）、
 * Hibernate {@code ColumnDefault}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlEntityMapper {
    private SqlEntityMapper() {
    }

    /**
     * @param type 实体类
     * @return 映射
     */
    public static SqlEntityModel inspect(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("entity class is null");
        }
        String table = tableName(type);
        List<Field> fields = declaredFields(type);
        List<SqlEntityColumn> columns = new ArrayList<SqlEntityColumn>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (skip(f)) {
                continue;
            }
            if (isEmbedded(f)) {
                columns.addAll(expandEmbedded(f));
                continue;
            }
            columns.add(column(f));
        }
        return new SqlEntityModel(type, table, columns, tableIndexes(type), tableComment(type));
    }

    /**
     * @param type 类
     * @return 是否带 {@link SqlTable}、JPA {@code @Entity}、MyBatis-Plus {@code @TableName}/{@code @TableId}
     */
    public static boolean isEntity(Class<?> type) {
        if (type == null || type.isInterface() || type.isAnnotation() || type.isEnum()
                || Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        if (type.getAnnotation(SqlTable.class) != null) {
            return true;
        }
        if (namedAnnotation(type, "javax.persistence.Entity") != null
                || namedAnnotation(type, "jakarta.persistence.Entity") != null
                || namedAnnotation(type, "com.baomidou.mybatisplus.annotation.TableName") != null) {
            return true;
        }
        Field[] fs = type.getDeclaredFields();
        for (int i = 0; i < fs.length; i++) {
            if (namedAnnotation(fs[i], "com.baomidou.mybatisplus.annotation.TableId") != null) {
                return true;
            }
        }
        return false;
    }

    private static String tableName(Class<?> type) {
        SqlTable sqlTable = type.getAnnotation(SqlTable.class);
        if (sqlTable != null && sqlTable.name() != null && sqlTable.name().length() > 0) {
            return sqlTable.name();
        }
        Object jpaTable = namedAnnotation(type, "javax.persistence.Table");
        if (jpaTable == null) {
            jpaTable = namedAnnotation(type, "jakarta.persistence.Table");
        }
        if (jpaTable != null) {
            String n = stringAttr(jpaTable, "name");
            if (n != null && n.length() > 0) {
                return n;
            }
        }
        Object mp = namedAnnotation(type, "com.baomidou.mybatisplus.annotation.TableName");
        if (mp != null) {
            String n = stringAttr(mp, "value");
            if (n != null && n.length() > 0) {
                return n;
            }
        }
        Object alias = namedAnnotation(type, "org.apache.ibatis.type.Alias");
        if (alias != null) {
            String n = stringAttr(alias, "value");
            if (n != null && n.length() > 0) {
                return n;
            }
        }
        return snake(simpleName(type.getSimpleName()));
    }

    private static SqlEntityColumn column(Field field) {
        Object tableId = namedAnnotation(field, "com.baomidou.mybatisplus.annotation.TableId");
        boolean id = field.getAnnotation(SqlId.class) != null
                || namedAnnotation(field, "javax.persistence.Id") != null
                || namedAnnotation(field, "jakarta.persistence.Id") != null
                || tableId != null;
        boolean generated = field.getAnnotation(SqlGenerated.class) != null
                || namedAnnotation(field, "javax.persistence.GeneratedValue") != null
                || namedAnnotation(field, "jakarta.persistence.GeneratedValue") != null
                || isMpAutoId(tableId);
        SqlColumn col = field.getAnnotation(SqlColumn.class);
        Object jpaCol = namedAnnotation(field, "javax.persistence.Column");
        if (jpaCol == null) {
            jpaCol = namedAnnotation(field, "jakarta.persistence.Column");
        }
        Object tableField = namedAnnotation(field, "com.baomidou.mybatisplus.annotation.TableField");
        String name = field.getName();
        int length = 255;
        int precision = 0;
        int scale = 0;
        boolean nullable = !id;
        boolean unique = false;
        if (col != null) {
            if (col.name().length() > 0) {
                name = col.name();
            }
            length = col.length();
            precision = col.precision();
            scale = col.scale();
            nullable = col.nullable();
            unique = col.unique();
        } else if (jpaCol != null) {
            String n = stringAttr(jpaCol, "name");
            if (n != null && n.length() > 0) {
                name = n;
            }
            Integer len = intAttr(jpaCol, "length");
            if (len != null) {
                length = len.intValue();
            }
            Integer p = intAttr(jpaCol, "precision");
            if (p != null) {
                precision = p.intValue();
            }
            Integer s = intAttr(jpaCol, "scale");
            if (s != null) {
                scale = s.intValue();
            }
            Boolean nu = boolAttr(jpaCol, "nullable");
            if (nu != null) {
                nullable = nu.booleanValue();
            }
            Boolean uq = boolAttr(jpaCol, "unique");
            if (uq != null) {
                unique = uq.booleanValue();
            }
        } else if (tableField != null && notEmpty(stringAttr(tableField, "value"))) {
            name = stringAttr(tableField, "value");
        } else if (tableId != null && notEmpty(stringAttr(tableId, "value"))) {
            name = stringAttr(tableId, "value");
        } else {
            name = snake(field.getName());
        }
        String rawType = null;
        if (col != null && col.columnDefinition().length() > 0) {
            rawType = col.columnDefinition();
        } else if (jpaCol != null) {
            String cd = stringAttr(jpaCol, "columnDefinition");
            if (cd != null && cd.length() > 0) {
                rawType = cd;
            }
        }
        boolean lob = namedAnnotation(field, "javax.persistence.Lob") != null
                || namedAnnotation(field, "jakarta.persistence.Lob") != null;
        CanonicalType canonical = javaType(field.getType(), lob, field);
        String refTable = null;
        String refCol = null;
        if (rawType == null && SqlEntityMapper.isEntity(field.getType()) && field.getType() != field.getDeclaringClass()) {
            SqlEntityModel ref = inspect(field.getType());
            SqlEntityColumn refId = ref.idColumn();
            canonical = refId == null ? CanonicalType.BIGINT : refId.canonical();
            refTable = ref.tableName();
            refCol = refId == null ? "id" : refId.columnName();
            if (col == null || col.name().length() == 0) {
                Object join = namedAnnotation(field, "javax.persistence.JoinColumn");
                if (join == null) {
                    join = namedAnnotation(field, "jakarta.persistence.JoinColumn");
                }
                String jn = join == null ? null : stringAttr(join, "name");
                name = jn != null && jn.length() > 0 ? jn : snake(field.getName()) + "_id";
            }
        }
        Integer prec = null;
        Integer sc = null;
        if (canonical.requiresPrecision()) {
            prec = Integer.valueOf(length > 0 ? length : 255);
        }
        if (canonical.requiresScale()) {
            prec = Integer.valueOf(precision > 0 ? precision : 10);
            sc = Integer.valueOf(scale > 0 ? scale : 2);
        }
        if (id) {
            nullable = false;
        }
        String comment = null;
        if (col != null && col.comment() != null && col.comment().length() > 0) {
            comment = col.comment();
        } else {
            comment = annotationText(namedBySimpleName(field, "Comment"));
        }
        String defaultValue = annotationText(
                namedAnnotation(field, "org.hibernate.annotations.ColumnDefault"));
        return new SqlEntityColumn(name, canonical, prec, sc, nullable, id, generated, unique,
                rawType, refTable, refCol, comment, defaultValue, field);
    }

    private static String tableComment(Class<?> type) {
        SqlTable sqlTable = type.getAnnotation(SqlTable.class);
        if (sqlTable != null && sqlTable.comment() != null && sqlTable.comment().length() > 0) {
            return sqlTable.comment();
        }
        return annotationText(namedBySimpleName(type, "Comment"));
    }

    private static String annotationText(Object annotation) {
        if (annotation == null) {
            return null;
        }
        String v = stringAttr(annotation, "value");
        if (v == null || v.trim().isEmpty()) {
            v = stringAttr(annotation, "comment");
        }
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.length() == 0 ? null : v;
    }

    private static List<String> tableIndexes(Class<?> type) {
        List<String> out = new ArrayList<String>(2);
        SqlTable sqlTable = type.getAnnotation(SqlTable.class);
        if (sqlTable != null) {
            String[] ix = sqlTable.indexes();
            for (int i = 0; i < ix.length; i++) {
                if (ix[i] != null && ix[i].length() > 0) {
                    out.add(ix[i]);
                }
            }
        }
        addJpaIndexes(type, "javax.persistence.Table", out);
        addJpaIndexes(type, "jakarta.persistence.Table", out);
        return out;
    }

    private static void addJpaIndexes(Class<?> type, String tableAnn, List<String> out) {
        Object table = namedAnnotation(type, tableAnn);
        if (table == null) {
            return;
        }
        Object indexes = invoke(table, "indexes");
        if (indexes == null || !indexes.getClass().isArray()) {
            return;
        }
        int n = Array.getLength(indexes);
        for (int i = 0; i < n; i++) {
            Object idx = Array.get(indexes, i);
            if (idx == null) {
                continue;
            }
            String cols = stringAttr(idx, "columnList");
            if (cols == null || cols.isEmpty()) {
                continue;
            }
            cols = cols.replace(" ", "");
            String name = stringAttr(idx, "name");
            if (name != null && name.length() > 0) {
                out.add(name + ":" + cols);
            } else {
                out.add(cols);
            }
        }
    }

    private static CanonicalType javaType(Class<?> type, boolean lob, Field field) {
        if (type == java.util.UUID.class) {
            return CanonicalType.UUID;
        }
        if (type == String.class) {
            return lob ? CanonicalType.TEXT : CanonicalType.VARCHAR;
        }
        if (type == Integer.class || type == Integer.TYPE) {
            return CanonicalType.INT;
        }
        if (type == Long.class || type == Long.TYPE || type == BigInteger.class) {
            return CanonicalType.BIGINT;
        }
        if (type == Short.class || type == Short.TYPE) {
            return CanonicalType.SMALLINT;
        }
        if (type == Byte.class || type == Byte.TYPE) {
            return CanonicalType.TINYINT;
        }
        if (type == Boolean.class || type == Boolean.TYPE) {
            return CanonicalType.BOOLEAN;
        }
        if (type == Float.class || type == Float.TYPE) {
            return CanonicalType.FLOAT;
        }
        if (type == Double.class || type == Double.TYPE) {
            return CanonicalType.DOUBLE;
        }
        if (type == BigDecimal.class) {
            return CanonicalType.DECIMAL;
        }
        if (type == byte[].class) {
            return CanonicalType.BLOB;
        }
        if (type == Character.class || type == Character.TYPE) {
            return CanonicalType.CHAR;
        }
        String n = type.getName();
        if (type == Date.class || type == java.sql.Timestamp.class || type == Calendar.class
                || "java.time.LocalDateTime".equals(n) || "java.time.Instant".equals(n)
                || "java.time.OffsetDateTime".equals(n)) {
            return CanonicalType.DATETIME;
        }
        if (type == java.sql.Date.class || "java.time.LocalDate".equals(n)) {
            return CanonicalType.DATE;
        }
        if (type == java.sql.Time.class || "java.time.LocalTime".equals(n)) {
            return CanonicalType.TIME;
        }
        if (type.isEnum()) {
            if (isEnumeratedOrdinal(field)) {
                return CanonicalType.INT;
            }
            return CanonicalType.VARCHAR;
        }
        return CanonicalType.VARCHAR;
    }

    private static boolean isEnumeratedOrdinal(Field field) {
        if (field == null) {
            return false;
        }
        Object en = namedAnnotation(field, "javax.persistence.Enumerated");
        if (en == null) {
            en = namedAnnotation(field, "jakarta.persistence.Enumerated");
        }
        if (en == null) {
            return false;
        }
        Object v = invoke(en, "value");
        return v != null && "ORDINAL".equals(enumName(v));
    }

    private static boolean skip(Field field) {
        int m = field.getModifiers();
        if (Modifier.isStatic(m) || Modifier.isTransient(m) || field.isSynthetic()) {
            return true;
        }
        if (field.getAnnotation(SqlTransient.class) != null) {
            return true;
        }
        if (namedAnnotation(field, "javax.persistence.Transient") != null
                || namedAnnotation(field, "jakarta.persistence.Transient") != null) {
            return true;
        }
        Object tf = namedAnnotation(field, "com.baomidou.mybatisplus.annotation.TableField");
        if (tf != null) {
            Boolean exist = boolAttr(tf, "exist");
            if (exist != null && !exist.booleanValue()) {
                return true;
            }
        }
        if (namedAnnotation(field, "javax.persistence.OneToMany") != null
                || namedAnnotation(field, "jakarta.persistence.OneToMany") != null
                || namedAnnotation(field, "javax.persistence.ManyToMany") != null
                || namedAnnotation(field, "jakarta.persistence.ManyToMany") != null
                || namedAnnotation(field, "javax.persistence.ElementCollection") != null
                || namedAnnotation(field, "jakarta.persistence.ElementCollection") != null) {
            return true;
        }
        if (field.getAnnotation(SqlColumn.class) != null || tf != null
                || namedAnnotation(field, "javax.persistence.Column") != null
                || namedAnnotation(field, "jakarta.persistence.Column") != null) {
            return false;
        }
        Class<?> t = field.getType();
        if (t.isArray() && t != byte[].class) {
            return true;
        }
        return Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t);
    }

    private static boolean isEmbedded(Field field) {
        return namedAnnotation(field, "javax.persistence.Embedded") != null
                || namedAnnotation(field, "jakarta.persistence.Embedded") != null
                || namedAnnotation(field.getType(), "javax.persistence.Embeddable") != null
                || namedAnnotation(field.getType(), "jakarta.persistence.Embeddable") != null;
    }

    private static List<SqlEntityColumn> expandEmbedded(Field field) {
        List<Field> nested = declaredFields(field.getType());
        List<SqlEntityColumn> out = new ArrayList<SqlEntityColumn>(nested.size());
        for (int i = 0; i < nested.size(); i++) {
            Field n = nested.get(i);
            if (skip(n) || isEmbedded(n)) {
                continue;
            }
            out.add(column(n));
        }
        return out;
    }

    private static boolean isMpAutoId(Object tableId) {
        if (tableId == null) {
            return false;
        }
        Object type = invoke(tableId, "type");
        return type != null && "AUTO".equals(enumName(type));
    }

    private static boolean notEmpty(String s) {
        return s != null && s.length() > 0;
    }

    private static String enumName(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        return String.valueOf(value);
    }

    private static List<Field> declaredFields(Class<?> type) {
        List<Field> all = new ArrayList<Field>(8);
        Class<?> cur = type;
        while (cur != null && cur != Object.class) {
            Field[] fs = cur.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                all.add(fs[i]);
            }
            cur = cur.getSuperclass();
        }
        return all;
    }

    static String snake(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append((char) (c + 32));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String simpleName(String name) {
        if (name.endsWith("Entity") && name.length() > 6) {
            return name.substring(0, name.length() - 6);
        }
        return name;
    }

    private static Annotation namedAnnotation(Class<?> type, String name) {
        Annotation[] anns = type.getAnnotations();
        for (int i = 0; i < anns.length; i++) {
            if (name.equals(anns[i].annotationType().getName())) {
                return anns[i];
            }
        }
        return null;
    }

    private static Annotation namedAnnotation(Field field, String name) {
        Annotation[] anns = field.getAnnotations();
        for (int i = 0; i < anns.length; i++) {
            if (name.equals(anns[i].annotationType().getName())) {
                return anns[i];
            }
        }
        return null;
    }

    private static Annotation namedBySimpleName(Class<?> type, String simpleName) {
        Annotation[] anns = type.getAnnotations();
        for (int i = 0; i < anns.length; i++) {
            if (simpleName.equals(anns[i].annotationType().getSimpleName())) {
                return anns[i];
            }
        }
        return null;
    }

    private static Annotation namedBySimpleName(Field field, String simpleName) {
        Annotation[] anns = field.getAnnotations();
        for (int i = 0; i < anns.length; i++) {
            if (simpleName.equals(anns[i].annotationType().getSimpleName())) {
                return anns[i];
            }
        }
        return null;
    }

    private static String stringAttr(Object ann, String method) {
        Object v = invoke(ann, method);
        return v instanceof String ? (String) v : null;
    }

    private static Integer intAttr(Object ann, String method) {
        Object v = invoke(ann, method);
        return v instanceof Integer ? (Integer) v : null;
    }

    private static Boolean boolAttr(Object ann, String method) {
        Object v = invoke(ann, method);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    private static Object invoke(Object ann, String method) {
        try {
            return ann.getClass().getMethod(method).invoke(ann);
        } catch (Exception e) {
            return null;
        }
    }
}
