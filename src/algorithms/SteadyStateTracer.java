package algorithms;

import models.StateSpace;
import nl.utwente.ewi.fmt.EXPRES.Property;
import java.util.Random;

/**
 * Estimate the steady-state probability of being in a red state.
 */
public class SteadyStateTracer extends TraceGenerator
{
	private long N;
	private long M;
	private double sumRedTime;
	private double sumTime;
	private double sumRedTimesSquared;
	private double sumTimesSquared;
	private double estMeanTime;
	private double estMeanRedTime;
	private boolean hasDeadlocks;
	private final SteadyStateTracer mcTracer;

	public SteadyStateTracer(Random rng, Scheme s, Property p)
	{
		super(rng, s, p);
		if (s.getClass() == Scheme.class) {
			mcTracer = this;
		} else {
			mcTracer = new SteadyStateTracer(rng, new Scheme(s.model), p);
			mcTracer.scheme.model = scheme.model;
		}
	}

	public TraceGenerator copy()
	{
		SteadyStateTracer ret;
		ret = new SteadyStateTracer(subRNG(), scheme.clone(), prop);
		ret.hasDeadlocks = hasDeadlocks;
		ret.estMeanTime = estMeanTime;
		ret.estMeanRedTime = estMeanRedTime;
		return ret;
	}

	public void reset()
	{
		super.reset();
		hasDeadlocks = false;
		N = M = 0;
		sumRedTime = sumTime = sumRedTimesSquared = sumTimesSquared = 0;
		estMeanTime = estMeanRedTime = 0;
	}

	public void resetAndEstimateMeans()
	{
		double emt = 0, emrt = 0;
		if (N > 0) {
			emt = sumTime / N;
			emrt = sumRedTime / N;
		}
		super.resetAndEstimateMeans();
		estMeanTime = emt;
		estMeanRedTime = emrt;
		hasDeadlocks = false;
	}

	public void resetAndEstimateMeans(TraceGenerator[] ts)
	{
		double sum = 0;
		double sumRed = 0;
		long N = 0;
		super.resetAndEstimateMeans(ts);
		for (TraceGenerator t : ts) {
			if (t instanceof SteadyStateTracer) {
				sum += ((SteadyStateTracer)t).sumTime;
				sumRed += ((SteadyStateTracer)t).sumRedTime;
				N += ((SteadyStateTracer)t).N;
			}
		}
		estMeanTime = sum / N;
		estMeanRedTime = sumRed / N;
		hasDeadlocks = false;
	}

	public void resetModelCache()
	{
		super.resetModelCache();
		mcTracer.scheme.model = scheme.model;
	}

	public void sample()
	{
		boolean deadlocked = false;
                int state = 0;
                double likelihood = 1;
                double timeInRed = 0;
		StateSpace model = scheme.model;
		SteadyStateTracer tracer = this;

		/* Do a cycle with IS to measure red time */
                do {
			int prevState = state;
                        state = tracer.drawNextState(state);
			if (prop.isRed(model, prevState)) {
				timeInRed += tracer.drawMeanTransitionTime();
				tracer = mcTracer;
			}
			if (state != prevState) {
				likelihood *= likelihood();
			} else {
				if (tracer.drawMeanTransitionTime() == Double.POSITIVE_INFINITY) {
					deadlocked = true;
				} else {
					likelihood *= likelihood();
				}
			}
                } while(!prop.isBlue(model, state) && !deadlocked);
		if (deadlocked) {
			/* We basically resort to estimating
			 * P(eventually red), probably very badly due to
			 * the importance sampling.
			 */
			if (!hasDeadlocks) {
				/* We didn't know yet we could deadlock.
				 * */
				hasDeadlocks = true;
				M = 0;
				N = 0;
				sumRedTime = sumRedTimesSquared = 0;
			}
			N++;
			if (prop.isRed(model, state)) {
				M++;
				sumRedTime += likelihood;
				sumRedTimesSquared = Math.fma(likelihood, likelihood, sumRedTimesSquared);
			}
		}

		/* Now do a cycle without IS to measure cycle duration. */
		deadlocked = false;
		state = 0;
		double totalTime = 0;
		do {
			double delta;
			int prevState = state;
			state = mcTracer.drawNextState(state);
			totalTime += mcTracer.drawMeanTransitionTime();
			if (state == prevState)
				deadlocked = true;
		} while(!prop.isBlue(model, state) && !deadlocked);
		if (deadlocked) {
			if (!hasDeadlocks) {
				/* We didn't know yet we could deadlock.
				 * */
				hasDeadlocks = true;
				M = 0;
				N = 0;
				sumRedTime = sumRedTimesSquared = 0;
			}
			N++;
			if (prop.isRed(model, state)) {
				M++;
				sumRedTime += 1;
				sumRedTimesSquared += 1;
			}
		}
		if (!hasDeadlocks) {
			N++;
			if (timeInRed > 0) {
				M++;
				sumRedTime = Math.fma(timeInRed, likelihood, sumRedTime);
				double Z = Math.fma(timeInRed, likelihood, -estMeanRedTime);
				sumRedTimesSquared = Math.fma(Z, Z, sumRedTimesSquared);
			}
			sumTime += totalTime;
			totalTime -= estMeanTime;
			sumTimesSquared = Math.fma(totalTime, totalTime, sumTimesSquared);
		}
	}

	public SimulationResult getDeadlockResult(double alpha)
	{
		long time = getElapsedTime();
		double mean = sumRedTime / N;
		double var = Math.fma(-sumRedTime, mean, sumRedTimesSquared);
		var /= N - 1;

		return new SimulationResult(prop, alpha, mean, var, new long[]{N, M}, time, baseModelSize);

	}

	public SimulationResult getResult(double alpha)
	{
		if (hasDeadlocks)
			return getDeadlockResult(alpha);
		long time = getElapsedTime();
		double meanZ = sumRedTime / N;
		double meanT = sumTime / N;
		if (M <= 1) {
			return new SimulationResult(prop, sumRedTime / sumTime, alpha, Double.NaN, 0, Double.POSITIVE_INFINITY, new long[]{N, M}, time, baseModelSize);
		}
		if (Simulator.VERBOSE) {
			System.err.println("Estimator for Z (unavail. time during cycle): " + meanZ);
			System.err.println("Estimator for D (total    time during cycle): " + meanT);
		}
		double varZ = Math.fma(N, -estMeanRedTime, sumRedTime);
                varZ = Math.fma(-varZ, meanZ - estMeanRedTime, sumRedTimesSquared);
                double varT = Math.fma(N, -estMeanTime, sumTime);
                varT = Math.fma(-varT, meanT - estMeanTime, sumTimesSquared);

                double meanV = sumRedTime/sumTime;
                double varV = N*Math.fma(meanV, meanV*varT, varZ)*N;
                varV /= (N-1)*sumTime*sumTime;

		return new SimulationResult(prop, alpha, meanV, varV, new long[]{N, M}, time, baseModelSize);
	}

	public SimulationResult getResult(TraceGenerator[] ts, double alpha)
	{
		boolean deadlock = false;
		hasDeadlocks = false;
		N = M = 0;
		sumRedTime = sumTime = sumRedTimesSquared = sumTimesSquared = 0;

		for (TraceGenerator t : ts) {
			if (t instanceof SteadyStateTracer) {
				SteadyStateTracer st = (SteadyStateTracer)t;
				if (deadlock && !st.hasDeadlocks)
					continue;
				if (st.hasDeadlocks && !deadlock) {
					N = M = 0;
					deadlock = true;
					hasDeadlocks = true;
					sumRedTime = sumRedTimesSquared = 0;
				}
				if (!deadlock
				    && (estMeanTime != st.estMeanTime
				        || estMeanRedTime != st.estMeanRedTime))
				{
					N = M = 0;
					sumRedTime = sumTime = 0;
					sumRedTimesSquared = 0;
					sumTimesSquared = 0;
				}
				estMeanTime = st.estMeanTime;
				estMeanRedTime = st.estMeanRedTime;
				sumTime += st.sumTime;
				sumRedTime += st.sumRedTime;
				sumTimesSquared += st.sumTimesSquared;
				sumRedTimesSquared += st.sumRedTimesSquared;
				N += st.N;
				M += st.M;
			}
		}
		return getResult(alpha);
	}
}
