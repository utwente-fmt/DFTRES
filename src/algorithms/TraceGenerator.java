package algorithms;

import nl.utwente.ewi.fmt.EXPRES.Property;

public abstract class TraceGenerator
{
	public final Scheme scheme;
	public final ModelGenerator generator;
	public final Property prop;
	public final int baseModelSize;
	private long startTime;

	public TraceGenerator(Scheme scheme, Property prop)
	{
		this.scheme = scheme;
		generator = scheme.generator;
		baseModelSize = generator.X.size();
		this.prop = prop;
		startTime = System.nanoTime();
	}

	public void reset()
	{
		startTime = System.nanoTime();
	}

	public void resetAndEstimateMeans()
	{
		reset();
	}

	/** Get time in nanoseconds since creation or last reset. */
	public long getElapsedTime()
	{
		return System.nanoTime() - startTime;
	}

	public abstract SimulationResult getResult(double alpha);
	public abstract void sample();
}
