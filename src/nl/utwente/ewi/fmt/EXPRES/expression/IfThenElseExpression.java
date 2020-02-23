package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import models.StateSpace;

public class IfThenElseExpression extends Expression
{
	public final Expression condition, thenExpr, elseExpr;
	private final int hash;
	private Set<String> variables;

	public IfThenElseExpression(Expression condition, Expression thenExpr, Expression elseExpr)
	{
		this.condition = condition;
		this.thenExpr = thenExpr;
		this.elseExpr = elseExpr;
		TreeSet<String> vs = new TreeSet<>(condition.getReferencedVariables());
		vs.addAll(thenExpr.getReferencedVariables());
		vs.addAll(elseExpr.getReferencedVariables());
		variables = Set.copyOf(vs);
		int hashVal = "ite".hashCode();
		hashVal = 31 * hashVal + condition.hashCode();
		hashVal = 31 * hashVal + thenExpr.hashCode();
		hashVal = 31 * hashVal + elseExpr.hashCode();
		hash = hashVal;
	}

	public Set<String> getReferencedVariables() {
		return variables;
	}

	private static long checkedInteger(Number v)
	{
		if ((v instanceof Long) || (v instanceof Integer))
			return v.longValue();
		throw new IllegalArgumentException("Expected integer type, found : " + v);
	}

	public Number evaluate(Map<String, ? extends Number> valuation) {
		Number condVal = condition.evaluate(valuation);
		if (condVal == null)
			return null;
		if (condVal.doubleValue() != 0)
			return thenExpr.evaluate(valuation);
		else
			return elseExpr.evaluate(valuation);
	}

	public void writeJani(PrintStream out, int indent) {
		out.print("{\"op\": \"ite\", \"if\": ");
		condition.writeJani(out, indent);
		out.print(", \"then\": ");
		thenExpr.writeJani(out, indent);
		out.print(", \"else\": ");
		elseExpr.writeJani(out, indent);
		out.print("}");
	}

	public int hashCode() {
		return hash;
	}

	public boolean equals(Object other) {
		Expression us = simplify(Map.of());
		if (us != this)
			return us.equals(other);
		if (!(other instanceof Expression))
			return false;
		Expression them = ((Expression)other).simplify(Map.of());
		if (!(them instanceof IfThenElseExpression))
			return false;
		IfThenElseExpression o = (IfThenElseExpression)them;
		return condition.equals(o.condition)
		       && thenExpr.equals(o.thenExpr)
		       && elseExpr.equals(o.elseExpr);
	}

	public String toString() {
		return '(' + condition.toString() + ") ? (" + thenExpr.toString() + ") : (" + elseExpr.toString() + ')';
	}

	public Expression booleanExpression() {
		Expression thenBool = thenExpr.booleanExpression();
		Expression elseBool = elseExpr.booleanExpression();
		return new IfThenElseExpression(condition, thenBool, elseBool);
	}

	public Expression simplify(Map<String, ? extends Number> valuation) {
		Number maybeConstant = evaluate(valuation);
		if (maybeConstant != null)
			return new ConstantExpression(maybeConstant);
		Number condConst = condition.evaluate(valuation);
		if (condConst != null) {
			if (condConst.doubleValue() != 0)
				return thenExpr.simplify(valuation);
			else
				return elseExpr.simplify(valuation);
		}
		Expression simplerCond = condition.simplify(valuation);
		Expression simplerThen = thenExpr.simplify(valuation);
		Expression simplerElse = elseExpr.simplify(valuation);
		if (simplerCond != condition || simplerThen != thenExpr || simplerElse != elseExpr)
			return new IfThenElseExpression(simplerCond, simplerThen, simplerElse);
		return this;
	}

	@Override
	public IfThenElseExpression renameVars(Map<String, String> renames) {
		return new IfThenElseExpression(condition.renameVars(renames),
		                                thenExpr.renameVars(renames),
					        elseExpr.renameVars(renames));
	}
}
