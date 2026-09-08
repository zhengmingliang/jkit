package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;

import java.math.BigDecimal;

/**
 * 字面量常量折叠（EvalVisitor 子集）：仅算术/比较/逻辑，无反射、不读列。
 *
 * <p>无法求值时返回 {@code null}（与 {@code Boolean.FALSE} 区分）。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlEval {
    private SqlEval() {
    }

    /**
     * 对纯字面量表达式求值。
     *
     * @param expr 表达式
     * @return {@link Boolean} / {@link Number} / {@link String} / {@code null}（NULL 字面量或不可求值）
     */
    public static Object eval(SqlExpr expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof SqlLiteral) {
            return evalLiteral((SqlLiteral) expr);
        }
        if (expr instanceof SqlUnaryExpr) {
            return evalUnary((SqlUnaryExpr) expr);
        }
        if (expr instanceof SqlBinaryExpr) {
            return evalBinary((SqlBinaryExpr) expr);
        }
        return null;
    }

    /**
     * @param expr 表达式
     * @return 是否恒为真（可求值且为 Boolean.TRUE）
     */
    public static boolean isAlwaysTrue(SqlExpr expr) {
        return Boolean.TRUE.equals(eval(expr));
    }

    /**
     * @param expr 表达式
     * @return 是否恒为假
     */
    public static boolean isAlwaysFalse(SqlExpr expr) {
        return Boolean.FALSE.equals(eval(expr));
    }

    private static Object evalLiteral(SqlLiteral lit) {
        if (lit.kind() == null) {
            return null;
        }
        switch (lit.kind()) {
            case NULL:
                return null;
            case BOOLEAN:
                return parseBoolean(lit.value());
            case NUMBER:
                return parseNumber(lit.value());
            case STRING:
            case HEX:
            case BIT:
                return lit.value();
            case BIND:
            case NAMED_BIND:
            case VARIABLE:
            default:
                return null;
        }
    }

    private static Object evalUnary(SqlUnaryExpr unary) {
        Object v = eval(unary.expr());
        if (unary.operator() == SqlUnaryExpr.Op.NOT) {
            if (v instanceof Boolean) {
                return !((Boolean) v).booleanValue();
            }
            return null;
        }
        if (unary.operator() == SqlUnaryExpr.Op.MINUS) {
            if (v instanceof Number) {
                return negate((Number) v);
            }
            return null;
        }
        if (unary.operator() == SqlUnaryExpr.Op.PLUS) {
            return v instanceof Number ? v : null;
        }
        return null;
    }

    private static Object evalBinary(SqlBinaryExpr bin) {
        SqlBinaryOp op = bin.operator();
        if (op == SqlBinaryOp.AND || op == SqlBinaryOp.OR || op == SqlBinaryOp.XOR) {
            Object left = eval(bin.left());
            Object right = eval(bin.right());
            if (!(left instanceof Boolean) || !(right instanceof Boolean)) {
                return null;
            }
            boolean l = ((Boolean) left).booleanValue();
            boolean r = ((Boolean) right).booleanValue();
            if (op == SqlBinaryOp.AND) {
                return Boolean.valueOf(l && r);
            }
            if (op == SqlBinaryOp.OR) {
                return Boolean.valueOf(l || r);
            }
            return Boolean.valueOf(l ^ r);
        }
        Object left = eval(bin.left());
        Object right = eval(bin.right());
        if (op == SqlBinaryOp.EQ || op == SqlBinaryOp.IS) {
            return compareEquals(left, right, bin.left(), bin.right());
        }
        if (op == SqlBinaryOp.NE || op == SqlBinaryOp.IS_NOT) {
            Boolean eq = compareEquals(left, right, bin.left(), bin.right());
            return eq == null ? null : Boolean.valueOf(!eq.booleanValue());
        }
        if (op == SqlBinaryOp.PLUS || op == SqlBinaryOp.MINUS || op == SqlBinaryOp.MUL
                || op == SqlBinaryOp.DIV || op == SqlBinaryOp.MOD) {
            return arithmetic(left, right, op);
        }
        if (op == SqlBinaryOp.LT || op == SqlBinaryOp.GT || op == SqlBinaryOp.LE || op == SqlBinaryOp.GE) {
            return relational(left, right, op);
        }
        return null;
    }

    private static Boolean compareEquals(Object left, Object right, SqlExpr leftExpr, SqlExpr rightExpr) {
        if (leftExpr instanceof SqlLiteral && ((SqlLiteral) leftExpr).kind() == SqlLiteral.Kind.NULL) {
            return null;
        }
        if (rightExpr instanceof SqlLiteral && ((SqlLiteral) rightExpr).kind() == SqlLiteral.Kind.NULL) {
            return null;
        }
        if (left == null || right == null) {
            return null;
        }
        if (left instanceof Number && right instanceof Number) {
            return Boolean.valueOf(toBigDecimal((Number) left).compareTo(toBigDecimal((Number) right)) == 0);
        }
        return Boolean.valueOf(String.valueOf(left).equalsIgnoreCase(String.valueOf(right)));
    }

    private static Object arithmetic(Object left, Object right, SqlBinaryOp op) {
        if (!(left instanceof Number) || !(right instanceof Number)) {
            return null;
        }
        BigDecimal a = toBigDecimal((Number) left);
        BigDecimal b = toBigDecimal((Number) right);
        if (op == SqlBinaryOp.PLUS) {
            return a.add(b);
        }
        if (op == SqlBinaryOp.MINUS) {
            return a.subtract(b);
        }
        if (op == SqlBinaryOp.MUL) {
            return a.multiply(b);
        }
        if (op == SqlBinaryOp.DIV) {
            if (b.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return a.divide(b, 16, BigDecimal.ROUND_HALF_UP);
        }
        if (op == SqlBinaryOp.MOD) {
            if (b.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return a.remainder(b);
        }
        return null;
    }

    private static Boolean relational(Object left, Object right, SqlBinaryOp op) {
        if (!(left instanceof Number) || !(right instanceof Number)) {
            return null;
        }
        int cmp = toBigDecimal((Number) left).compareTo(toBigDecimal((Number) right));
        if (op == SqlBinaryOp.LT) {
            return Boolean.valueOf(cmp < 0);
        }
        if (op == SqlBinaryOp.GT) {
            return Boolean.valueOf(cmp > 0);
        }
        if (op == SqlBinaryOp.LE) {
            return Boolean.valueOf(cmp <= 0);
        }
        if (op == SqlBinaryOp.GE) {
            return Boolean.valueOf(cmp >= 0);
        }
        return null;
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        if ("TRUE".equalsIgnoreCase(value) || "1".equals(value)) {
            return Boolean.TRUE;
        }
        if ("FALSE".equalsIgnoreCase(value) || "0".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Number parseNumber(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            if (value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
                return new BigDecimal(value);
            }
            long n = Long.parseLong(value);
            if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) n);
            }
            return Long.valueOf(n);
        } catch (NumberFormatException ex) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static Number negate(Number n) {
        return toBigDecimal(n).negate();
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal) {
            return (BigDecimal) n;
        }
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return BigDecimal.valueOf(n.longValue());
        }
        return BigDecimal.valueOf(n.doubleValue());
    }
}
