package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import models.StateSpace;

public class BinaryExpression extends Expression
{
	public static enum Operator {
	       EQUALS("=", true),
	       NOT_EQUALS("≠", true)
		       ;

	       public final String symbol;
	       public final boolean returnsBoolean;
	       Operator(String symb, boolean ret) {
		       symbol = symb;
		       returnsBoolean = ret;
	       }
	}

	public final Operator op;
	public final Expression left, right;
	private Set<String> variables;

	public BinaryExpression(Operator op, Expression left, Expression right)
	{
		this.op = op;
		this.left = left;
		this.right = right;
		TreeSet<String> vs = new TreeSet<>(left.getReferencedVariables());
		vs.addAll(right.getReferencedVariables());
		variables = Set.copyOf(vs);
	}

	public Set<String> getReferencedVariables() {
		return variables;
	}

	public Number evaluate(Map<String, ? extends Number> valuation) {
		Number l = left.evaluate(valuation);
		Number r = right.evaluate(valuation);
		switch (op) {
			case EQUALS:
				if (l.longValue() != r.longValue())
					return 0;
				if (l.doubleValue() != r.doubleValue())
					return 0;
				return 1;
			case NOT_EQUALS:
				if (l.longValue() != r.longValue())
					return 1;
				if (l.doubleValue() != r.doubleValue())
					return 1;
				return 0;
			default:
				throw new UnsupportedOperationException("Unknown operator: " + op.symbol);
		}
	}

	public void writeJani(PrintStream out, int indent) {
		out.print("{\"op\": \"");
		out.print(op.symbol);
		out.print("\", \"left\": ");
		left.writeJani(out, indent);
		out.print(", \"right\": ");
		right.writeJani(out, indent);
		out.print("}");
	}

	public int hashCode() {
		return op.symbol.hashCode()
		       + 31 * (left.hashCode()
		               + 31 * right.hashCode());
	}

	public boolean equals(Object other) {
		Expression us = simplify(Map.of());
		if (us != this)
			return us.equals(other);
		if (!(other instanceof Expression))
			return false;
		Expression them = ((Expression)other).simplify(Map.of());
		if (!(them instanceof BinaryExpression))
			return false;
		BinaryExpression o = (BinaryExpression)them;
		return (op == o.op)
		       && left.equals(o.left)
		       && right.equals(o.right);
	}

	public String toString() {
		return '(' + left.toString() + ')' + op.symbol + '(' + right.toString() + ')';
	}

	public Expression booleanExpression() {
		if (op.returnsBoolean)
			return this;
		return super.booleanExpression();
	}
}
