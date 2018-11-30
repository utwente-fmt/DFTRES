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
	       NOT_EQUALS("≠", true),
	       LESS("<", true),
	       LESS_OR_EQUAL("≤", true),
	       GREATER("<", true),
	       GREATER_OR_EQUAL("≥", true),
	       AND("∧", true),
	       OR("∨", true),
	       XOR("xor", true),
	       ADD("+", false),
	       SUBTRACT("-", false),
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

	private static long checkedInteger(Number v)
	{
		if ((v instanceof Long) || (v instanceof Integer))
			return v.longValue();
		throw new IllegalArgumentException("Expected integer type, found : " + v);
	}

	public Number evaluate(Map<String, ? extends Number> valuation) {
		Number l = left.evaluate(valuation);
		Number r = right.evaluate(valuation);
		Number vL = null, vR = null;
		if (l != null && (l instanceof Long || l instanceof Integer))
			vL = l;
		if (r != null && (r instanceof Long || r instanceof Integer))
			vR = r;
		Boolean boolL = null, boolR = null;
		if (l != null)
			boolL = l.doubleValue() != 0;
		if (r != null)
			boolR = r.doubleValue() != 0;

		Boolean bRet = null;
		switch (op) {
			case EQUALS:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() == vR.longValue();
				break;
			case NOT_EQUALS:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() != vR.longValue();
				break;
			case LESS:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() < vR.longValue();
				break;
			case LESS_OR_EQUAL:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() <= vR.longValue();
				break;
			case GREATER:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() > vR.longValue();
				break;
			case GREATER_OR_EQUAL:
				if (vL == null || vR == null)
					return null;
				bRet = vL.longValue() >= vR.longValue();
				break;
			case ADD:
				if (vL == null || vR == null)
					return null;
				return vL.longValue() + vR.longValue();
			case SUBTRACT:
				if (vL == null || vR == null)
					return null;
				return vL.longValue() - vR.longValue();
			case AND:
				if (boolL == null && vR == null)
					return null;
				if (boolL == null)
					return boolR ? null : 0;
				if (boolR == null)
					return boolL ? null : 0;
				bRet = boolL && boolR;
				break;
			case OR:
				if (boolL == null && boolR == null)
					return null;
				if (boolL == null)
					return boolR ? 1 : null;
				if (boolR == null)
					return boolL ? 1 : null;
				bRet = boolL || boolR;
				break;
			case XOR:
				if (boolL == null || boolR == null)
					return null;
				bRet = boolR ^ boolR;
				break;
			default:
				throw new UnsupportedOperationException("Unknown operator: " + op.symbol);
		}
		return bRet ? 1 : 0;
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

	public Expression simplify(Map<String, ? extends Number> valuation) {
		Number maybeConstant = evaluate(valuation);
		if (maybeConstant != null)
			return new ConstantExpression(maybeConstant);
		Number constL = left.evaluate(valuation);
		Number constR = right.evaluate(valuation);
		Expression simplerL = left.simplify(valuation);
		Expression simplerR = right.simplify(valuation);
		if (constL != null || constR != null) {
			switch (op) {
			case AND:
				if (constL != null && constL.doubleValue() == 0)
					return new ConstantExpression(0);
				if (constR != null && constR.doubleValue() == 0)
					return new ConstantExpression(0);
				break;
			case OR:
				if (constL != null && constL.doubleValue() != 0)
					return new ConstantExpression(1);
				if (constR != null && constR.doubleValue() != 0)
					return new ConstantExpression(1);
				break;
			case NOT_EQUALS:
				if (constL != null && constL.doubleValue() == 0)
					return simplerR;
				if (constR != null && constR.doubleValue() == 0)
					return simplerL;
				break;
			}
		}
		if (simplerL != left || simplerR != right)
			return new BinaryExpression(op, simplerL, simplerR);
		return this;
	}
}
