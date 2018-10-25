package algorithms;

import schemes.SchemeMC;
import nl.utwente.ewi.fmt.EXPRES.Property;

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
	private SchemeMC mcScheme;

	public SteadyStateTracer(Scheme s, Property p)
	{
		super(s, p);
		mcScheme = new SchemeMC(s.rng, generator);
	}

	public void reset()
	{
		super.reset();
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
	}

	public void sample()
	{
		scheme.reset();
                int state = 0;
                double likelihood = 1;
                double timeInRed = 0;

		/* Do a cycle with IS to measure red time */
                do {
                        scheme.computeNewProbs(state);
                        state = scheme.drawNextState();
                        likelihood *= scheme.likelihood();
                } while(!generator.isRed(state) && !generator.isBlue(state));
		while(!generator.isBlue(state)) {
                        double delta;
			mcScheme.computeNewProbs(state);
			int newState = mcScheme.drawNextState();
			if(generator.isRed(state))
				timeInRed += mcScheme.drawMeanTransitionTime();
			state = newState;
		}
		N++;
		if (timeInRed > 0) {
			M++;
			sumRedTime = Math.fma(timeInRed, likelihood, sumRedTime);
			double Z = Math.fma(timeInRed, likelihood, -estMeanRedTime);
			sumRedTimesSquared = Math.fma(Z, Z, sumRedTimesSquared);
		}

		/* Now do a cycle without IS to measure cycle duration. */
		mcScheme.reset();
		state = 0;
		double totalTime = 0;
		do {
			double delta;
			mcScheme.computeNewProbs(state);
			totalTime += mcScheme.drawMeanTransitionTime();
			state = mcScheme.drawNextState();
		} while(!generator.isBlue(state));
		sumTime += totalTime;
		totalTime -= estMeanTime;
		sumTimesSquared = Math.fma(totalTime, totalTime, sumTimesSquared);
	}

	public SimulationResult getResult(double alpha)
	{
		long time = getElapsedTime();
		double meanZ = sumRedTime / N;
		double meanT = sumTime / N;
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
}
