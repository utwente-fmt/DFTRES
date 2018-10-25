package algorithms;

import models.StateSpace;
import java.util.Arrays;
import java.util.HashMap;
import nl.utwente.ewi.fmt.EXPRES.Property;

/* Optimal stopping criterion, see pg. 555 of
 * http://www.ism.ac.jp/editsec/aism/pdf/s10463-015-0504-2.pdf
 * (A general sequential fixed-accuracy confidence interval estimation
 * methodology for a positive parameter).
 */

public class Simulator {
	public final static boolean VERBOSE = false;
	public static boolean showProgress = false;
	private final TraceGenerator gen;

	public Simulator(Property prop, Scheme scheme, double forcingBound)
	{
		switch (prop.type) {
			case REACHABILITY:
				gen = new ReachabilityTracer(scheme, prop,
				                             forcingBound);
				break;
			case STEADY_STATE:
				gen = new SteadyStateTracer(scheme, prop);
				break;
			default:
				gen = null;
				assert(false);
		}
	}

	/**
	 * Compute a (1-alpha) confidence interval for the value of the
	 * property, not exceeding maxN simulations (for the actual
	 * estimation, more samples may be taken for time estimation),
	 * and taking at most approximately msec milliseconds.
	 */
	public SimulationResult sim(int msec, long maxN, double alpha)
	{
		int percentage = 0;
		long N = 0;
		long start = System.currentTimeMillis();
		int initSize = gen.scheme.model.size();
		if (VERBOSE)
			System.err.println("Start size: " + initSize);

		/* For time bounds, start by estimating the number of
		 * simulations we can run.
		 */
		if (msec < Integer.MAX_VALUE) {
			long estN = 0;

			/* Spend 1% of time to estimate number of runs. */
			long trialSimTime = start + msec / 100;
			while (System.currentTimeMillis() < trialSimTime) {
				// keep the cache from exploding
				if(gen.scheme.model.size() > 2 * initSize)
					gen.scheme.resetModelCache();
				gen.sample();
				N++;
			}

			/* Spend about another 1% estimating the time
			 * after caching and without constant time
			 * measurements. */
			long startExact = System.nanoTime();
			while (estN < N) {
				// keep the cache from exploding
				if(gen.scheme.model.size() > 2 * initSize)
					gen.scheme.resetModelCache();
				gen.sample();
				estN++;
			}
			long exactTime = System.nanoTime() - startExact;
			msec -= (System.currentTimeMillis() - start);
			N = (msec * (1000000 * N)) / exactTime;
			if (N < maxN)
				maxN = N;
			N = 0;
		}
		if (VERBOSE)
			System.err.format("Will run %d simulations.\n", maxN);
		gen.resetAndEstimateMeans();

		while(N < maxN) {
			// keep the cache from exploding
			if(gen.scheme.model.size() > 2 * initSize)
				gen.scheme.resetModelCache();
			gen.sample();
			if (showProgress && maxN < Integer.MAX_VALUE) {
				int newPerc = (int)(N * 50 / maxN);
				if (newPerc != percentage) {
					percentage = newPerc;
					if (newPerc % 5 == 0)
						System.err.print(newPerc * 2 + "%");
					else
						System.err.print(".");
					percentage = newPerc;
				}
			}
			N++;
		}
		if (VERBOSE)
			System.err.println("End size: "+gen.scheme.model.size());
		return gen.getResult(alpha);
	}

	public SimulationResult simRelErr(double err, double alpha)
	{
		long maxN = 1000;
		long totalSims[] = new long[2];
		double totalAlpha = alpha;
		double lbound = 0, ubound = 1, mean;
		double sum = 0;
		double curRelErr;
		int initSize = gen.scheme.model.size();
		SimulationResult result;
		alpha /= 2; /* Start with a window 1/2 as big, then 1/4,
		             * 1/8, ... */
		long startTime = System.nanoTime();

		/* First try to hit the target at all, to get a rough
		 * estimate (without spoiling the confidence level).
		 */
		do {
			result = sim(Integer.MAX_VALUE, maxN, 0.05);
			if (result.M == 0)
				maxN *= 10;
			mean = result.mean;
		} while (result.ubound == 0);

		do {
			/* Estimate number of samples still needed to
			 * reach desired error */
			double Z = SimulationResult.CIwidth(alpha);
			long newN = (long)(result.var * Z * Z / (err * err * mean * mean));
			if (VERBOSE)
				System.err.println("Estimating " + newN + " new simulations required");
			if (newN > maxN)
				maxN = newN;

			result = sim(Integer.MAX_VALUE, maxN, alpha);
			if (result.M > 0) {
				if (result.lbound > lbound)
					lbound = result.lbound;
				if (result.ubound < ubound)
					ubound = result.ubound;
				if (ubound < lbound) {
					lbound = result.lbound;
					ubound = result.ubound;
				}
			}
			sum = Math.fma(result.N, result.mean, sum);
			mean = (ubound + lbound) / 2;
			double halfWidth = (ubound - lbound) / 2;
			curRelErr = halfWidth / mean;
			totalSims[0] += result.N;
			totalSims[1] += result.M;
			if (VERBOSE)
				System.err.format("Relative error %g after %d simulations\n", curRelErr, totalSims[0]);
			alpha /= 2;

			if (lbound == 0) { /* Unlikely, but apparantly we
					      haven't hit anything at all */
				maxN *= 10;
				continue;
			}
		} while (curRelErr > err);
		long exactTime = System.nanoTime() - startTime;
		return new SimulationResult(gen.prop, sum / totalSims[0],
				totalAlpha, Double.NaN, lbound, ubound,
				totalSims, exactTime, initSize);
	}
}
