package nl.utwente.ewi.fmt.EXPRES;
import java.util.Map;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

class JaniUtils {
	public static final int DEFAULT_INT_BITS = 7;
	public static final int DEFAULT_INT_MIN = 0;
	public static final int DEFAULT_INT_MAX = (1 << DEFAULT_INT_BITS) - 1;
	public static double getConstantDouble(Object exp,
	                                       Map<String, Number> constants)
	{
		if (exp instanceof Number)
			return ((Number)exp).doubleValue();
		else if (exp instanceof String) {
			String name = (String)exp;
			Number c = constants.get(name);
			if (c == null)
				throw new IllegalArgumentException("Unknown identifier: " + name);
			return c.doubleValue();
		} else if (exp instanceof Map) {
			Map m = (Map)exp;
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
				return val.doubleValue();
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
		} else {
			throw new UnsupportedOperationException("Expected constant integer literal, found: " + exp);
		}
	}

	public static int[] typeBounds(Object t)
	{
		if ("bool".equals(t))
			return new int[]{0, 1};
		if ("int".equals(t))
			return new int[]{DEFAULT_INT_MIN, DEFAULT_INT_MAX};
		if (t instanceof Map) {
			int ret[] = new int[]{DEFAULT_INT_MIN, DEFAULT_INT_MAX};
			Map tm = (Map)t;
			Object base = tm.get("base");
			if (!"int".equals(base))
				throw new IllegalArgumentException(base.toString() + " type variables are currently not supported.");
			if (!"bounded".equals(tm.get("kind")))
				throw new IllegalArgumentException("Bounded type without 'bounded' kind not supported.");
			Object lb = tm.get("lower-bound");
			if (lb instanceof Long) {
				long l = (Long) lb;
				if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
					throw new IllegalArgumentException("Type bound should fit in 32 bits.");
				ret[0] = (int)l;
			}
			Object ub = tm.get("upper-bound");
			if (ub instanceof Long) {
				long u = (Long) ub;
				if (u < Integer.MIN_VALUE || u > Integer.MAX_VALUE)
					throw new IllegalArgumentException("Type bound should fit in 32 bits.");
				ret[1] = (int)u;
			}
			return ret;
		}
		throw new IllegalArgumentException("Type " + t.toString() + " is not supported.");
	}
}
