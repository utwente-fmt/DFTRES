package algorithms;

import models.StateSpace;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.LongAdder;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class Simulator {
	private final static long MIN_FREE_MEM = 512*1024L*1024L;
	public final static boolean VERBOSE = false;
	public final static int REL_ERR_RATE = 8;
	public static int coresToUse;
	public static boolean showProgress = false;
	private final StateSpace initialModel;
	private final TraceGenerator gen;
	private static final Runtime r = Runtime.getRuntime();
	
	static {
		coresToUse = Runtime.getRuntime().availableProcessors();
	}

	private static long getMemUsed()
	{
		return r.totalMemory() - r.freeMemory();
	}

	private static long getMemFree()
	{
		return r.freeMemory();
	}

	private static class ProgressPrinter extends Thread {
		private final long maxN;
		private final LongAdder done;
		private final long initialTime;

		public ProgressPrinter(long N) {
			maxN = N;
			done = new LongAdder();
			initialTime = System.currentTimeMillis();
		}
		private boolean printLine() {
			long d = done.sum();
			if (d == 0)
				return false;
			int perc = (int)((d * 50) / maxN);
			System.err.print("\r");
			for (int i = 0; i < perc; i++) {
				if (i % 5 == 0)
					System.err.print(i * 2 + "%");
				else
					System.err.print(".");
			}
			long now = System.currentTimeMillis();
			long elapsed = now - initialTime;
			int secsLeft = (int)(elapsed * (maxN / (double)d - 1));
			secsLeft /= 1000;
			int minsLeft = secsLeft / 60;
			secsLeft -= minsLeft * 60;
			int hoursLeft = minsLeft / 60;
			minsLeft -= hoursLeft * 60;
			long mem = getMemUsed();
			if (d != maxN) {
				System.err.format (" (est. %d:%02d:%02d remaining, used %d MB)", hoursLeft, minsLeft, secsLeft, mem / 1048576);
				return false;
			} else {
				System.err.println("Done                     ");
				return true;
			}
		}
		public void doneOne() {
			done.increment();
		}
		public void run() {
			while (!printLine()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
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
			case EXPECTED_VALUE:
				gen = new ExpectedValueTracer(rng, scheme, prop,
				                              forcingBound);
				break;
			default:
				gen = null;
				assert(false);
		}
		initialModel = gen.scheme.model;
		gen.resetModelCache(initialModel.snapshot());
	}

	private TraceGenerator[] multiCoreSim(long maxN, int threads)
	{
		TraceGenerator[] ret = new TraceGenerator[threads];
		for (int i = 0; i < threads; i++)
			ret[i] = gen.copy();
		if (maxN == 0)
			return ret;
		ProgressPrinter p;
		if (showProgress) {
			p = new ProgressPrinter(maxN);
		} else {
			p = new ProgressPrinter(Long.MAX_VALUE) {
				public void doneOne() { }
				public void run() { }
			};
		}
		final ProgressPrinter progress = p;
		final StateSpace[][] toReset = new StateSpace[1][threads];

		class Tracer implements Runnable {
			public final TraceGenerator gen;
			public final long N;
			public Tracer(TraceGenerator g, long nSims) {
				gen = g;
				N = nSims;
			}
			private void resetAndWait() {
				synchronized (toReset) {
					int i, n = 0;
					StateSpace ours = null;
					for (i = 0; i < toReset[0].length; i++) {
						if (toReset[0][i] != null) {
							ours = toReset[0][i];
							toReset[0][i] = null;
							n = i;
							break;
						}
					}
					if (showProgress)
						System.err.format("Resetting %d: %d MB avail\n", n % toReset[0].length, getMemFree() / (1024 * 1024));
					if (ours == null) {
						ours = initialModel.snapshot();
						Simulator.this.gen.resetModelCache(ours);
						Arrays.fill(toReset[0], ours);
						toReset[0][0] = null;
					}
					gen.resetModelCache(ours);
					if (n == toReset[0].length - 1) {
						/* We were the last. */
						r.gc();
						toReset.notifyAll();
					} else {
						/* Wait for the last one. */
						try {
							toReset.wait();
						} catch (Exception e) {
						}
					}
				}
			}
			public void run() {
				for (long i = 0; i < N; i++) {
					// keep the cache from exploding
					if (getMemFree() < MIN_FREE_MEM)
						resetAndWait();
					gen.sample();
					p.doneOne();
				}
				synchronized(toReset) {
					boolean doNotify = false;
					if (toReset[0].length > 1
					    && toReset[0][toReset[0].length - 2] != null)
					{
						doNotify = true;
					}
					toReset[0] = Arrays.copyOf(toReset[0], toReset[0].length - 1);
					if (doNotify)
						toReset.notifyAll();
				}
				gen.resetModelCache(null);
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
			p.start();
			for (int i = 0; i < threads; i++) {
				try {
					ts[i].join();
				} catch (InterruptedException e) {
					i--;
				}
			}
			p.interrupt();
			try {
				p.join();
			} catch (InterruptedException e) {
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

		if (maxN == 0)
			maxN = Long.MAX_VALUE;
		/* For time bounds, start by estimating the number of
		 * simulations we can run.
		 */
		if (msec > 0) {
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
				if(getMemFree() < MIN_FREE_MEM)
					tg.resetModelCache(initialModel.snapshot());
				tg.sample();
				N++;
			}

			gen.resetModelCache(tg.scheme.model);
			if (showProgress)
				System.err.println("Estimating simulation rate.");
			/* Spend about another 1% estimating the time
			 * after caching and without constant time
			 * measurements. */
			N *= coresToUse;
			long startExact = System.nanoTime();
			TraceGenerator[] gens = multiCoreSim(N, coresToUse);
			gens = Arrays.copyOf(gens, gens.length + 1);
			gens[gens.length - 1] = tg;
			long exactTime = System.nanoTime() - startExact;
			gen.resetAndEstimateMeans(gens);
			msec -= (System.currentTimeMillis() - start);
			N = (long)((msec * (1000000.0 * N)) / exactTime);
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
		if (showProgress)
			System.err.format("\nWill run %d simulations.\n", maxN);

		TraceGenerator ts[] = multiCoreSim(maxN, coresToUse);
		if (showProgress)
			System.err.println();
		if (VERBOSE)
			System.err.println("End size: "+gen.scheme.model.size());
		return gen.getResult(ts, alpha);
	}

	public SimulationResult simUnsafeRelErr(double err, double alpha)
	{
		long maxN = 1000;
		double curRelErr;
		int initSize = gen.scheme.model.size();
		SimulationResult result;
		long startTime = System.nanoTime();

		do {
			result = sim(0, maxN, alpha);
			double mean = (result.ubound + result.lbound) / 2;
			double halfWidth = (result.ubound - result.lbound) / 2;
			curRelErr = halfWidth / mean;
			if (showProgress)
				System.err.format("Relative error %g after %d simulations\n", curRelErr, result.N);
			maxN *= 2;
		} while (curRelErr > err);
		long exactTime = System.nanoTime() - startTime;
		return result;
	}

	public SimulationResult simRelErr(double err, double alpha, long limitN)
	{
		if (REL_ERR_RATE <= 0)
			return simUnsafeRelErr(err, alpha);
		long maxN = 1000 * coresToUse;
		long totalSims[] = new long[2];
		double totalAlpha = alpha, consumedAlpha;
		double lbound = 0, ubound = 1, mean;
		double curRelErr;
		int initSize = gen.scheme.model.size();
		SimulationResult result;
		long startTime = System.nanoTime();

		alpha = totalAlpha / REL_ERR_RATE;
		consumedAlpha = alpha;
		/* First try to hit the target at all, to get a rough
		 * estimate (without spoiling the confidence level).
		 */
		do {
			maxN *= 10;
			if (alpha > 0)
				result = sim(0, maxN, alpha);
			else
				result = sim(0, maxN, 0.05);
			mean = result.mean;
			if (alpha > 0) {
				lbound = result.lbound;
				ubound = result.ubound;
				totalSims[0] = result.N;
				totalSims[1] = result.M;
			}
			alpha = 0;
		} while (result.M < 10);

		mean = (ubound + lbound) / 2;
		double halfWidth = (ubound - lbound) / 2;
		curRelErr = halfWidth / mean;
		if (showProgress)
			System.err.format("Consumed alpha so far: %g\n", consumedAlpha);
		alpha = (totalAlpha - consumedAlpha) / (1 - consumedAlpha);
		alpha = (alpha * (REL_ERR_RATE - 1)) / REL_ERR_RATE;
		consumedAlpha += alpha - (alpha * consumedAlpha);
		do {
			/* Estimate number of samples still needed to
			 * reach desired error */
			double Z = SimulationResult.CIwidth(alpha);
			long newN = (long)(2 * result.var * Z * Z / (err * err * mean * mean));
			if (showProgress) {
				System.err.format("Current alpha: %g\n", alpha);
				System.err.format("Consumed alpha after this step: %g\n", consumedAlpha);
				System.err.format("Relative error %e after %d simulations\n", curRelErr, totalSims[0]);
				System.err.format("Current estimate: [%g; %g]\n", lbound, ubound);
				System.err.println("Estimating " + newN + " new simulations required");
			}
			if (newN > maxN)
				maxN = newN;
			if (maxN > limitN && limitN > 0)
				maxN = limitN;

			result = sim(0, maxN, alpha);
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
			mean = (ubound + lbound) / 2;
			halfWidth = (ubound - lbound) / 2;
			curRelErr = halfWidth / mean;
			totalSims[0] += result.N;
			totalSims[1] += result.M;
			alpha = (totalAlpha - consumedAlpha) / (1 - consumedAlpha);
			alpha = (alpha * (REL_ERR_RATE - 1)) / REL_ERR_RATE;
			consumedAlpha += alpha - (alpha * consumedAlpha);

			if (lbound == 0) { /* Unlikely, but apparantly we
					      haven't hit anything at all */
				maxN *= 10;
				continue;
			}
		} while (curRelErr > err);
		if (showProgress)
			System.err.format("Consumed alpha: %g\n", consumedAlpha);
		long exactTime = System.nanoTime() - startTime;
		return new SimulationResult(gen.prop, mean,
				totalAlpha, Double.NaN, lbound, ubound,
				totalSims, exactTime, initSize);
	}
}
