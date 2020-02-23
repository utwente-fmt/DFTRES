package nl.utwente.ewi.fmt.EXPRES;
import java.util.Map;
import nl.utwente.ewi.fmt.EXPRES.JaniModel.JaniType;
import nl.utwente.ewi.fmt.EXPRES.JaniModel.JaniBaseType;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

class JaniUtils {
	public static final int DEFAULT_INT_BITS = 7;
	public static final int DEFAULT_INT_MIN = 0;
	public static final int DEFAULT_INT_MAX = (1 << DEFAULT_INT_BITS) - 1;

	public static int safeToInteger(Number num) {
		if (num instanceof Integer)
			return (Integer)num;
		if (num instanceof Long)
			return Math.toIntExact((Long)num);
		if ((num instanceof Float) || (num instanceof Double)) {
			double d = num.doubleValue();
			if (Math.floor(d) != d)
				throw new ArithmeticException(d + " cannot be exactly converted to an integer");
			if (d > Integer.MAX_VALUE || d < Integer.MIN_VALUE)
				throw new ArithmeticException(d + " is too big to be exactly converted to an integer");
			return (int)d;
		}
		throw new UnsupportedOperationException("Cannot convert type " + num.getClass() + " to integer");
	}

	public static Number getConstantNum(Object exp,
	                                    Map<String, Number> constants)
	{
		return getConstantDouble(exp, constants);
	}

	public static Number getConstantDouble(Object exp,
	                                       Map<String, Number> constants)
	{
		if (exp instanceof Number)
			return (Number)exp;
		else if (exp instanceof Boolean)
			return ((Boolean)exp) ? 1 : 0;
		else if (exp instanceof String) {
			String name = (String)exp;
			Number c = constants.get(name);
			if (c == null)
				throw new IllegalArgumentException("Unknown identifier: " + name);
			return c;
		} else if (exp instanceof Map) {
			Map<?, ?> m = (Map<?, ?>)exp;
			if (m.containsKey("constant")) {
				if ("π".equals(m.get("constant")))
					return Math.PI;
				if ("e".equals(m.get("constant")))
					return Math.E;
				throw new UnsupportedOperationException("Unknown constant value: " + m.get("constant"));
			}
			if (m.containsKey("exp"))
				exp = m.get("exp");
			Expression e = Expression.fromJani(exp);
			Number val = e.evaluate(constants);
			if (val != null)
				return val;
			throw new UnsupportedOperationException("Unable to evaluate " + e + " in " + constants);
		} else {
			throw new UnsupportedOperationException("Expected constant literal, found: " + exp);
		}
	}

	public static long getConstantLong(Object exp,
	                                   Map<String, Number> constants)
	{
		if (exp instanceof Long) {
			return ((Long)exp);
		} else if (exp instanceof Boolean) {
			return ((Boolean)exp) ? 1 : 0;
		} else if (exp instanceof String) {
			String name = (String)exp;
			Number c = constants.get(name);
			if (c == null)
				throw new IllegalArgumentException("Unknown identifier: " + name);
			if (!(c instanceof Long))
				throw new UnsupportedOperationException("Expected constant, but: " + exp + " is not an integer");
			return (Long)c;
		} else if (exp instanceof Map) {
			Expression e = Expression.fromJani(exp);
			Number val = e.evaluate(constants);
			if (val instanceof Long || val instanceof Integer)
				return val.longValue();
			throw new UnsupportedOperationException("Expected constant integer literal, found: " + exp);
		} else {
			throw new UnsupportedOperationException("Expected constant integer literal, found: " + exp);
		}
	}

	public static JaniType parseType(Object t, Map<String, Number> consts)
	{
		if ("bool".equals(t))
			return new JaniType(JaniBaseType.BOOLEAN, 0, 1);
		if ("int".equals(t))
			return new JaniType(JaniBaseType.INTEGER,
			                    DEFAULT_INT_MIN, DEFAULT_INT_MAX);
		if (t instanceof Map) {
			Number min = DEFAULT_INT_MIN;
			Number max = DEFAULT_INT_MAX;
			Map<?, ?> tm = (Map<?, ?>)t;
			Object baseO = tm.get("base");
			JaniBaseType base;
			if ("int".equals(baseO)) {
				base = JaniBaseType.INTEGER;
			} else if ("real".equals(baseO)) {
				base = JaniBaseType.REAL;
			} else {
				throw new IllegalArgumentException(baseO.toString() + " type variables are currently not supported.");
			}
			if (!"bounded".equals(tm.get("kind")))
				throw new IllegalArgumentException("Bounded type without 'bounded' kind not supported.");
			Object lb = tm.get("lower-bound");
			if (lb != null)
				min = getConstantDouble(lb, consts);
			Object ub = tm.get("upper-bound");
			if (ub != null)
				max = getConstantDouble(ub, consts);
			return new JaniType(JaniBaseType.INTEGER, min, max);
		}
		throw new IllegalArgumentException("Type " + t.toString() + " is not supported.");
	}
}
