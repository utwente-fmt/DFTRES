package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class VariableExpression extends Expression
{
	public final String variable;

	public VariableExpression(String var) {
		variable = var;
	}

	public Set<String> getReferencedVariables() {
		return Set.of(variable);
	}

	public Expression simplify(Map<String, ? extends Number> valuation) {
		if (valuation.containsKey(variable))
			return new ConstantExpression(valuation.get(variable));
		return this;
	}

	public Number evaluate(Map<String, ? extends Number> valuation) {
		return valuation.get(variable);
	}

	public void writeJani(PrintStream out, int indent) {
		out.print("\"" + variable + "\"");
	}
}
