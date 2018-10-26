package algorithms;

import models.StateSpace;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class Simulator {
	private final static int MIN_MAX_CACHE_SIZE = 100000;
	public final static boolean VERBOSE = false;
	public static int coresToUse;
	public static boolean showProgress = false;
	private final TraceGenerator gen;
	
	static {
		coresToUse = Runtime.getRuntime().availableProcessors();
		coresToUse = (coresToUse * 3) / 4;
	}

	private static class ProgressPrinter {
		private final long maxN;
		private final AtomicLong done;

		public ProgressPrinter(long N) {
			maxN = N;
			done = new AtomicLong(0);
		}
		public void doneOne() {
			if (showProgress && maxN < Long.MAX_VALUE) {
				long d = done.incrementAndGet();
				int prev = (int)((d - 1) * 50 / maxN);
				int newPerc = (int)(d * 50 / maxN);
				if (newPerc != prev) {
					if (newPerc % 5 == 0)
						System.err.print(newPerc * 2 + "%");
					else
						System.err.print(".");
				}
			}
		}
	}

	public Simulator(Random rng, Property prop, Scheme scheme, double forcingBound)
	{
		switch (prop.type) {
			case REACHABILITY:
				gen = new ReachabilityTracer(rng, scheme, prop,
				                             forcingBound);
				break;
			case STEADY_STATE:
				gen = new SteadyStateTracer(rng, scheme, prop);
				break;
			default:
				gen = null;
				assert(false);
		}
	}

	private TraceGenerator[] multiCoreSim(long maxN, int threads)
	{
		TraceGenerator[] ret = new TraceGenerator[threads];
		for (int i = 0; i < threads; i++)
			ret[i] = gen.copy();
		if (maxN == 0)
			return ret;
		int cacheSize = gen.scheme.model.size() * 2;
		if (cacheSize < MIN_MAX_CACHE_SIZE)
			cacheSize = MIN_MAX_CACHE_SIZE;
		final int maxCache = cacheSize;
		ProgressPrinter p;
		if (showProgress) {
			p = new ProgressPrinter(maxN);
		} else {
			p = new ProgressPrinter(Long.MAX_VALUE) {
				public void doneOne() { }
			};
		}
		final ProgressPrinter progress = p;

		class Tracer implements Runnable {
			public final TraceGenerator gen;
			public final long N;
			public Tracer(TraceGenerator g, long nSims) {
				gen = g;
				N = nSims;
			}
			public void run() {
				for (long i = 0; i < N; i++) {
					// keep the cache from exploding
					if(gen.scheme.model.size() > maxCache)
						gen.resetModelCache();
					gen.sample();
					p.doneOne();
				}
			}
		}

		if (threads > 1) {
			Thread[] ts = new Thread[threads];
			long nLeft = maxN;
			for (int i = 0; i < threads; i++) {
				long ourN = nLeft / (threads - i);
				nLeft -= ourN;
				ts[i] = new Thread(new Tracer(ret[i], ourN));
				ts[i].start();
			}
			for (int i = 0; i < threads; i++) {
				try {
					ts[i].join();
				} catch (InterruptedException e) {
					i--;
				}
			}
		} else {
			new Tracer(ret[0], maxN).run();
		}
		return ret;
	}

	/**
	 * Compute a (1-alpha) confidence interval for the value of the
	 * property, not exceeding maxN simulations (for the actual
	 * estimation, more samples may be taken for time estimation),
	 * and taking at most approximately msec milliseconds.
	 */
	public SimulationResult sim(int msec, long maxN, double alpha)
	{
		long start = System.currentTimeMillis();
		int maxCacheSize = gen.scheme.model.size() * 2;
		if (maxCacheSize < MIN_MAX_CACHE_SIZE)
			maxCacheSize = MIN_MAX_CACHE_SIZE;

		/* For time bounds, start by estimating the number of
		 * simulations we can run.
		 */
		if (msec < Integer.MAX_VALUE) {
			long N = 0;

			/* Do our stuff in a separate TG so that later
			 * calls explicitly specifying the same number
			 * of runs will produce the same result.
			 */
			TraceGenerator tg = gen.copy();
			tg.reseedRNG(System.nanoTime());
			/* Spend 1% of time to estimate number of runs. */
			long trialSimTime = start + msec / 100;
			while (System.currentTimeMillis() < trialSimTime) {
				// keep the cache from exploding
				if(tg.scheme.model.size() > maxCacheSize)
					tg.resetModelCache();
				tg.sample();
				N++;
			}

			if (showProgress)
				System.err.println("Estimating simulation rate.");
			/* Spend about another 1% estimating the time
			 * after caching and without constant time
			 * measurements. */
			long startExact = System.nanoTime();
			TraceGenerator[] gens = multiCoreSim(N, coresToUse);
			gens = Arrays.copyOf(gens, gens.length + 1);
			gens[gens.length - 1] = tg;
			long exactTime = System.nanoTime() - startExact;
			gen.resetAndEstimateMeans(gens);
			msec -= (System.currentTimeMillis() - start);
			N = (msec * (1000000 * N)) / exactTime;
			if (N < maxN)
				maxN = N;
		} else {
			/* Adjust the RNG to match the one that would
			 * have been produced by the timed version of
			 * the call.
			 */
			gen.copy();
			multiCoreSim(0, coresToUse);
			/* Note: This is not entirely deterministic, as
			 * we do not yet have an estimate of the mean to
			 * reduce the rounding error in the variance.
			 * Thus, our point estimate will be identical,
			 * but our confidence interval will not.
			 */
		}
		if (VERBOSE)
			System.err.format("Will run %d simulations.\n", maxN);

		TraceGenerator ts[] = multiCoreSim(maxN, coresToUse);
		if (VERBOSE)
			System.err.println("End size: "+gen.scheme.model.size());
		return gen.getResult(ts, alpha);
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
		int maxCacheSize = initSize * 2;
		if (maxCacheSize < MIN_MAX_CACHE_SIZE)
			maxCacheSize = MIN_MAX_CACHE_SIZE;
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
