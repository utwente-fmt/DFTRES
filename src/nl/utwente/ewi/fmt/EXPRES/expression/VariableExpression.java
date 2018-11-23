package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import models.StateSpace;

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

	public int hashCode() {
		return variable.hashCode();
	}

	public boolean equals(Object other) {
		if (!(other instanceof Expression))
			return false;
		Expression expr = (Expression)other;
		expr = expr.simplify(Map.of());
		if (!(expr instanceof VariableExpression))
			return false;
		return ((VariableExpression)expr).variable.equals(variable);
	}

	public Number evaluate(StateSpace s, int state) {
		return s.getVarValue(variable, state);
	}

	public String toString() {
		return '"' + variable + '"';
	}
}
