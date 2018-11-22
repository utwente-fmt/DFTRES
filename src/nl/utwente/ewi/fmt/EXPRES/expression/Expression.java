package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;

public abstract class Expression
{
	/** Get the set of variables that must be included in the
	 * valuation for the expression to be fully evaluated.
	 */

	public Set<String> getReferencedVariables() {
		return Set.of();
	}

	public Expression simplify(Map<String, ? extends Number> valuation) {
		return this;
	}

	public abstract Number evaluate(Map<String, ? extends Number> valuation);

	public static Expression fromJani(Object o)
	{
		if (o instanceof Number)
			return new ConstantExpression((Number)o);
		if (o instanceof String)
			return new VariableExpression((String)o);
		throw new UnsupportedOperationException("Expression: " + o);
	}

	public abstract void writeJani(PrintStream out, int indent);
}
