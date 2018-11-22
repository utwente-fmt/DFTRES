package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.PrintStream;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

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
	public final String variable;

	public final Expression time_cumulative_reward, transient_reward;

	public Property(Type type, double bound, String var, String name,
	                Expression cumulative_reward,
			Expression transient_reward)
	{
		this.name = name;
		this.type = type;
		this.timeBound = bound;
		this.variable = var;
		this.time_cumulative_reward = cumulative_reward;
		this.transient_reward = transient_reward;
	}

	public Property(Type type, double bound, String var, String name)
	{
		this(type, bound, var, name, null, null);
	}

	public Property(Type type, String var, String name)
	{
		this(type, Double.POSITIVE_INFINITY, var, name);
	}

	public Property(Property other, String newName)
	{
		this.type = other.type;
		this.timeBound = other.timeBound;
		this.variable = other.variable;
		this.name = newName;
		this.time_cumulative_reward = other.time_cumulative_reward;
		this.transient_reward = other.transient_reward;
	}

	public int compareTo(Property other)
	{
		if (this.type != other.type)
			return this.type.compareTo(other.type);
		if (!this.variable.equals(other.variable))
			return this.variable.compareTo(other.variable);
		if (this.timeBound < other.timeBound)
			return -1;
		else if (this.timeBound > other.timeBound)
			return 1;
		else
			return 0;
	}

	public int hashCode()
	{
		int ret = getClass().hashCode();
		ret = (ret * 3) + type.hashCode();
		ret = (ret * 3) + Double.hashCode(timeBound);
		ret = (ret * 3) + variable.hashCode();
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
		return variable.equals(other.variable);
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
				out.println(tabs + "\t\t\"exp\":\""
						+ variable + "\"");
				break;
			case REACHABILITY:
				out.println("Pmax\",");
				out.print(tabs + "\t\t\"exp\":{\"op\":\"F\", \"exp\":\"" + variable + "\"");
				if (Double.isFinite(timeBound))
					out.print(", \"time-bounds\":{\"upper\":" + timeBound + "}");
				out.println("}");
				break;
			case EXPECTED_VALUE:
				out.println("Emax\",");
				if (time_cumulative_reward != null)
					out.println(tabs + "\t\t\"accumulate\": [\"time\"],");
				if (Double.isFinite(timeBound))
					out.println(tabs + "\t\t\"time-instant\": " + timeBound + ",");
				if (variable != null)
					out.println(tabs + "\t\t\"reach\": \"" + variable + "\",");
				out.print(tabs + "\t\t\"exp\": ");
				if (time_cumulative_reward != null)
					time_cumulative_reward.writeJani(out, indent + 3);
				else
					transient_reward.writeJani(out, indent + 3);
				out.println();
				break;
		}
		out.println(tabs + "\t\t}");
		out.println(tabs + "\t}");
		out.print(tabs + '}');
	}
}
