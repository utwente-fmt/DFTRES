package algorithms;

import models.StateSpace;
import java.util.Random;
import nl.utwente.ewi.fmt.EXPRES.Property;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

/** Tracer to estimate the expected (cumulative) value of some
 * expression at the time of hitting either a red state or the time
 * bound.
 */
public class ExpectedValueTracer extends TraceGenerator
{
	private long N;
	private long M;
	private double sum;
	private double sumSquares;
	private double estMean;
	private final double UNIF_BOUND = 1e-10;

	public ExpectedValueTracer(Random rng, Scheme s, Property prop,
	                           double forceBound)
	{
		super(rng, s, prop, forceBound);
	}

	public ExpectedValueTracer copy()
	{
		ExpectedValueTracer ret;
		ret = new ExpectedValueTracer(subRNG(), scheme.clone(),
		                              prop, forceBound);
		ret.estMean = estMean;
		return ret;
	}

	public void reset()
	{
		super.reset();
		N = M = 0;
		sum = sumSquares = estMean = 0;
	}

	public void resetAndEstimateMeans()
	{
		double newEstMean = 0;
		if (N > 0)
			newEstMean = sum / N;
		super.resetAndEstimateMeans();
		estMean = newEstMean;
	}

	public void resetAndEstimateMeans(TraceGenerator[] ts)
	{
		double sum = 0;
		long N = 0;
		super.resetAndEstimateMeans(ts);
		for (TraceGenerator t : ts) {
			if (t instanceof ExpectedValueTracer) {
				sum += ((ExpectedValueTracer)t).sum;
				N += ((ExpectedValueTracer)t).N;
			}
		}
		estMean = sum / N;
	}

	public void sample()
	{
		int state = 0;
		double time = 0, reward = 0;
		double likelihood = 1;
		StateSpace model = scheme.model;

		do {
			int prevState = state;
			state = drawNextState(state);
			if (prop.timeBound < Double.POSITIVE_INFINITY) {
				double delta = drawDelta(prop.timeBound - time);
				if (delta > prop.timeBound - time) {
					delta = prop.timeBound - time;
					time = prop.timeBound;
				} else {
					time += delta;
				}
				if (prop.timeCumulativeReward != null) {
					reward = Math.fma(evaluate(prop.timeCumulativeReward, prevState), delta, reward);
				}
			} else if (prop.timeCumulativeReward != null) {
				reward = Math.fma(evaluate(prop.timeCumulativeReward, prevState), drawMeanTransitionTime(), reward);
			}
			likelihood *= likelihood();
		} while(!prop.isRed(model, state)
			&& !isDeadlocked()
		        && time < prop.timeBound
		        && likelihood > 0);

		if (isDeadlocked() && !prop.isRed(model, state)
		    && prop.timeBound == Double.POSITIVE_INFINITY)
			throw new UnsupportedOperationException("Deadlock detected while evaluating state-bounded expected value.");

		if (prop.transientReward != null)
			reward += evaluate(prop.transientReward, state);

		N++;
		if (reward > 0)
			M++;
		sum = Math.fma(reward, likelihood, sum);
		double diffEst = Math.fma(reward, likelihood, -estMean);
		sumSquares = Math.fma(diffEst, diffEst, sumSquares);
	}

	public SimulationResult getResult(double alpha)
	{
		long time = getElapsedTime();
		if (M == 0) {
			return new SimulationResult(prop, 0, alpha, Double.NaN,
					0, 1, new long[]{N, M}, time,
					baseModelSize);
		}
		double mean = sum / N;
		double estSum = Math.fma(-N, estMean, sum);
		double var = Math.fma(-estSum, estSum / N, sumSquares);
		var /= N - 1;
		SimulationResult ret = null;

		if (ret == null)
			ret = new SimulationResult(prop, alpha, mean, var, new long[]{N, M}, time, baseModelSize);
		return ret;
	}

	public SimulationResult getResult(TraceGenerator[] ts, double alpha)
	{
		sum = 0;
		N = M = 0;
		for (TraceGenerator t : ts) {
			if (t instanceof ExpectedValueTracer) {
				ExpectedValueTracer rt = (ExpectedValueTracer)t;
				if (estMean != rt.estMean) {
					sum = 0;
					sumSquares = 0;
					N = M = 0;
				}
				estMean = rt.estMean;
				sum += rt.sum;
				sumSquares += rt.sumSquares;
				N += rt.N;
				M += rt.M;
			}
		}
		return getResult(alpha);
	}

	private double evaluate(Expression exp, int state)
	{
		return exp.evaluate(scheme.model, state).doubleValue();
	}
}
