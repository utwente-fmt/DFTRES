package nl.utwente.ewi.fmt.EXPRES;

public class Property
{
	public enum Type {
		STEADY_STATE,
		REACHABILITY
	};
	public final Type type;
	public final double timeBound;
	public final String variable;

	public Property(Type type, double bound, String var)
	{
		this.type = type;
		this.timeBound = bound;
		this.variable = var;
	}

	public Property(Type type, String var)
	{
		this(type, Double.POSITIVE_INFINITY, var);
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
}
