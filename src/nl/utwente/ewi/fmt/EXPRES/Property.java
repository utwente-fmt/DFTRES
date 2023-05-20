package nl.utwente.ewi.fmt.EXPRES;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.io.IOException;
import java.io.PrintStream;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;
import models.StateSpace;

public class Property implements Comparable<Property>
{
	public enum Type {
		STEADY_STATE,
		REACHABILITY,
		EXPECTED_VALUE
	};
	public final String name;
	public final Type type;
	public final double timeBound;
	public final Expression reachTarget, avoidTarget;
	public final Expression timeCumulativeReward, transientReward;

	public Property(Type type, double bound, Expression target,
			Expression avoid,
			String name,
	                Expression cumulativeReward,
			Expression transientReward)
	{
		if (avoid != null && type != Type.REACHABILITY)
			throw new IllegalArgumentException("Avoid states only supported for reachability queries");
		this.name = name;
		this.type = type;
		this.timeBound = bound;
		if (target != null)
			reachTarget = target.simplify(Map.of());
		else
			reachTarget = null;
		if (avoid != null)
			avoidTarget = avoid.simplify(Map.of());
		else
			avoidTarget = null;
		if (cumulativeReward != null)
			timeCumulativeReward = cumulativeReward.simplify(Map.of());
		else
			timeCumulativeReward = null;
		if (transientReward != null)
			this.transientReward = transientReward.simplify(Map.of());
		else
			this.transientReward = null;
	}

	public Property(Type type, double bound, Expression target, Expression avoid, String name)
	{
		this(type, bound, target, avoid, name, null, null);
	}

	public Property(Type type, Expression target, Expression avoid, String name)
	{
		this(type, Double.POSITIVE_INFINITY, target, avoid, name);
	}

	public Property(Property other, String newName)
	{
		this.type = other.type;
		this.timeBound = other.timeBound;
		this.reachTarget = other.reachTarget;
		this.avoidTarget = other.avoidTarget;
		this.name = newName;
		this.timeCumulativeReward = other.timeCumulativeReward;
		this.transientReward = other.transientReward;
	}

	private static int compareExprs(Expression e1, Expression e2)
	{
		if (e1 == null && e2 == null)
			return 0;
		if (e1 == null && e2 != null)
			return -1;
		if (e1 != null && e2 == null)
			return 1;
		return e1.toString().compareTo(e2.toString());
	}

	public int compareTo(Property other)
	{
		int ret;
		if (this.type != other.type)
			return this.type.compareTo(other.type);
		ret = compareExprs(this.reachTarget, other.reachTarget);
		if (ret != 0)
			return ret;
		ret = compareExprs(this.avoidTarget, other.avoidTarget);
		if (ret != 0)
			return ret;
		if (this.timeBound < other.timeBound)
			return -1;
		else if (this.timeBound > other.timeBound)
			return 1;
		ret = compareExprs(this.timeCumulativeReward, other.timeCumulativeReward);
		if (ret != 0)
			return ret;
		return compareExprs(this.transientReward, other.transientReward);
	}

	public Set<String> getReferencedVariables() {
		TreeSet<String> ret = new TreeSet<>();
		if (reachTarget != null)
			ret.addAll(reachTarget.getReferencedVariables());
		if (avoidTarget != null)
			ret.addAll(avoidTarget.getReferencedVariables());
		if (timeCumulativeReward != null)
			ret.addAll(timeCumulativeReward.getReferencedVariables());
		if (transientReward != null)
			ret.addAll(transientReward.getReferencedVariables());
		return ret;
	}

	public Property simplify(Map<String, ? extends Number> valuation) {
		Expression target = null, avoid = null, cumReward = null, transReward = null;
		if (reachTarget != null)
			target = reachTarget.simplify(valuation);
		if (avoidTarget != null)
			avoid = avoidTarget.simplify(valuation);
		if (timeCumulativeReward != null)
			cumReward = timeCumulativeReward.simplify(valuation);
		if (transientReward != null)
			transReward = transientReward.simplify(valuation);
		return new Property(this.type, timeBound, target, avoid, this.name,
	                            cumReward, transReward);
	}

	public int hashCode()
	{
		int ret = getClass().hashCode();
		ret = (ret * 3) + type.hashCode();
		ret = (ret * 3) + Double.hashCode(timeBound);
		ret *= 3;
		if (reachTarget != null)
			ret += reachTarget.hashCode();
		ret *= 3;
		if (avoidTarget != null)
			ret += avoidTarget.hashCode();
		ret *= 3;
		if (timeCumulativeReward != null)
			ret += timeCumulativeReward.hashCode();
		ret *= 3;
		if (transientReward != null)
			ret += transientReward.hashCode();
		return ret;
	}

	public boolean equals(Object otherO)
	{
		if (!(otherO instanceof Property))
			return false;
		Property other = (Property)otherO;
		if (type != other.type)
			return false;
		if (timeBound != other.timeBound)
			return false;
		if (compareExprs(reachTarget, other.reachTarget) != 0)
			return false;
		if (compareExprs(avoidTarget, other.avoidTarget) != 0)
			return false;
		if (compareExprs(timeCumulativeReward, other.timeCumulativeReward) != 0)
			return false;
		return compareExprs(transientReward, other.transientReward) == 0;
	}

	public boolean isRed(StateSpace ss, StateSpace.State state)
	{
		if (reachTarget == null)
			return false;
		Number n = reachTarget.evaluate(ss, state);
		if (n instanceof Integer || n instanceof Long)
			return n.longValue() != 0;
		return n.doubleValue() != 0;
	}
	
	public boolean isBlue(StateSpace ss, StateSpace.State state)
	{
		if (type != Type.STEADY_STATE) {
			if (avoidTarget != null) {
				Number n = avoidTarget.evaluate(ss, state);
				if (n instanceof Integer || n instanceof Long)
					return n.longValue() != 0;
				return n.doubleValue() != 0;
			}

			return false;
		}
		StateSpace.State init = ss.getInitialState();
		return state.equals(init);
	}

	public void printJani(PrintStream out, int indent)
	{
		String tabs = "";
		for (int i = 0; i < indent; i++)
			tabs += '\t';
		out.println(tabs + "{\"name\":\"" + name + "\",");
		out.println(tabs + " \"expression\":{");
		out.println(tabs + "\t\"fun\":\"max\",");
		out.println(tabs + "\t\"op\":\"filter\",");
		out.println(tabs + "\t\"states\":{\"op\":\"initial\"},");
		out.println(tabs + "\t\"values\":{");
		out.print(tabs + "\t\t\"op\":\"");
		switch (type) {
			case STEADY_STATE:
				out.println("Smax\",");
				out.print(tabs + "\t\t\"exp\":");
				reachTarget.writeJani(out, 3);
				out.println();
				break;
			case REACHABILITY:
				out.println("Pmax\",");
				out.print(tabs + "\t\t\"exp\":{\"op\":\"U\", \"left\":");
				if (avoidTarget != null)
					avoidTarget.booleanExpression().writeJani(out, 3);
				else
					out.print("true");
				out.print(", \"right\":");
				reachTarget.booleanExpression().writeJani(out, 3);
				if (Double.isFinite(timeBound))
					out.print(", \"time-bounds\":{\"upper\":" + timeBound + "}");
				out.println("}");
				break;
			case EXPECTED_VALUE:
				out.println("Emax\",");
				if (timeCumulativeReward != null)
					out.println(tabs + "\t\t\"accumulate\": [\"time\"],");
				if (Double.isFinite(timeBound))
					out.println(tabs + "\t\t\"time-instant\": " + timeBound + ",");
				if (reachTarget != null) {
					out.print(tabs + "\t\t\"reach\":");
					reachTarget.booleanExpression().writeJani(out, 3);
					out.println(",");
				}
				out.print(tabs + "\t\t\"exp\": ");
				if (timeCumulativeReward != null)
					timeCumulativeReward.writeJani(out, indent + 3);
				else
					transientReward.writeJani(out, indent + 3);
				out.println();
				break;
		}
		out.println(tabs + "\t\t}");
		out.println(tabs + "\t}");
		out.print(tabs + '}');
	}
}
