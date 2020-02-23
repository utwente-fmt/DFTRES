package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import models.StateSpace;

import nl.utwente.ewi.fmt.EXPRES.expression.BinaryExpression.Operator;
import nl.utwente.ewi.fmt.EXPRES.LTS;

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
	public Number evaluate(StateSpace s, StateSpace.State state) {
		HashMap<String, Number> vals = new HashMap<>();
		for (String v : getReferencedVariables()) {
			Number val = s.getVarValue(v, state);
			if (val != null)
				vals.put(v, val);
		}
		return evaluate(vals);
	}

	public Number evaluate(LTS lts, int[] state) {
		HashMap<String, Number> vals = new HashMap<>();
		for (String v : getReferencedVariables()) {
			Number val = lts.getVarValue(v, state);
			if (val != null)
				vals.put(v, val);
		}
		return evaluate(vals);
	}

	public static Expression fromJani(Object o)
	{
		if (o instanceof Number)
			return new ConstantExpression((Number)o);
		if (o instanceof String)
			return new VariableExpression((String)o);
		if (o instanceof Boolean)
			return new ConstantExpression(((Boolean)o) ? 1 : 0);
		if (o instanceof Map) {
			Map<?, ?> e = (Map<?, ?>)o;
			Object op = e.get("op");
			if ("ite".equals(op)) {
				Object ifExpr = e.get("if");
				Object thenExpr = e.get("then");
				Object elseExpr = e.get("else");
				return new IfThenElseExpression(
						Expression.fromJani(ifExpr),
						Expression.fromJani(thenExpr),
						Expression.fromJani(elseExpr));
			}
			Object l = e.get("left");
			Object r = e.get("right");
			Object exp = e.get("exp");
			if (!(op instanceof String))
				throw new IllegalArgumentException("Operator: " + op + " in " + o + " should be string");
			for (Operator cand : Operator.values()) {
				if (cand.symbol.equals(op)) {
					return new BinaryExpression(
						cand,
						Expression.fromJani(l),
						Expression.fromJani(r));
				}
			}
			if ("¬".equals(op)) {
				return new BinaryExpression(
						Operator.XOR,
						new ConstantExpression(1),
						Expression.fromJani(exp));
			}
		}
		throw new UnsupportedOperationException("Expression: " + o);
	}

	public Expression booleanExpression()
	{
		return new BinaryExpression(
				BinaryExpression.Operator.NOT_EQUALS,
				this,
				new ConstantExpression(0));
	}

	public abstract int hashCode();
	public abstract boolean equals(Object other);
	public abstract String toString();
	public abstract void writeJani(PrintStream out, int indent);
	public abstract Expression renameVars(Map<String, String> renames);
}
