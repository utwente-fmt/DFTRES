package algorithms;

import models.StateSpace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

import nl.utwente.ewi.fmt.EXPRES.NoResultsException;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class Simulator {
	// private final static long MIN_FREE_MEM = 512*1024L*1024L;  // TODO delete?
	public final static boolean VERBOSE = false;
	public final static int REL_ERR_RATE = 8;
	public static int coresToUse;
	public static boolean showProgress = false;
	private boolean hasFreshResults = false;
	private final int relErrRate;
	private final StateSpace initialModel;
	private final TraceGenerator gen;
	private final TraceGenerator[] tgArr;
	private static final Runtime r = Runtime.getRuntime();
	
	static {
		coresToUse = Runtime.getRuntime().availableProcessors();
	}

	public Simulator(Random rng, Property prop, Scheme scheme, double forcingBound, int relativeErrorRate)
	{
		relErrRate = relativeErrorRate;
		switch (prop.type) {
			case REACHABILITY:
				gen = new ReachabilityTracer(rng, scheme, prop, forcingBound);
				break;
			case STEADY_STATE:
				gen = new SteadyStateTracer(rng, scheme, prop);
				break;
			case EXPECTED_VALUE:
				gen = new ExpectedValueTracer(rng, scheme, prop, forcingBound);
				break;
			default:
				gen = null;
				assert(false);
		}
		initialModel = gen.scheme.model;  // TODO: delete?
		tgArr = new TraceGenerator[coresToUse];
		for (int i = 0; i < coresToUse; i++)
			tgArr[i] = gen.copy();
	}

	public Simulator(Random rng, Property prop, Scheme scheme, double forcingBound)
	{
		this(rng, prop, scheme, forcingBound, REL_ERR_RATE);
	}

	private static long getMemUsed()
	{
		return r.totalMemory() - r.freeMemory();
	}

	private static class ProgressPrinter extends Thread {
		private long maxMemUsed;
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
			int secsLeft = (int)((System.currentTimeMillis() - initialTime) * (maxN / (double)d - 1) / 1000.0);
			long mem = getMemUsed() / (1l<<20);  // in MB
			maxMemUsed = mem > maxMemUsed ? mem : maxMemUsed;
			if (d != maxN) {
				System.err.format(" (est. %d:%02d:%02d remaining, used %d MB)", secsLeft/3600l, secsLeft/60l, secsLeft%60l, mem);
				return false;
			} else {
				System.err.println("100%                     ");
				long sElapsed = (int)((System.currentTimeMillis() - initialTime) / 1000.0);
				System.err.format("Simulation time: %d:%02d:%02d\n", sElapsed/3600l, sElapsed/60l, sElapsed%60l);
				System.err.format("Peak memory usage: %d MB\n", maxMemUsed);
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

	/**
	 * Run {@code maxN} simulations using {@code threads} parallel threads.
	 * Results are stored in the internal array {@code tgArr}
	 * @param maxN     Number of simulations to run in total
	 * @param threads  Number of parallel threads to use
	 */
	private void multiCoreSim(long maxN, int threads)
	{
		if (maxN == 0)
			return;  // we're done; that was easy

		ProgressPrinter p;
		if (showProgress) {
			p = new ProgressPrinter(maxN);
		} else {
			p = new ProgressPrinter(Long.MAX_VALUE) {
				public void doneOne() { }
				public void run() { }
			};
		}

		class Tracer implements Runnable {
			public final TraceGenerator gen;
			public final long N;
			public Tracer(TraceGenerator g, long nSims) {
				gen = g;
				N = nSims;
			}
			public void run() {
				for (long i = 0; i < N; i++) {
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
				ts[i] = new Thread(new Tracer(tgArr[i], ourN));
				ts[i].start();
				hasFreshResults = true;  // FIXME: this may be too soon
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
			new Tracer(tgArr[0], maxN).run();
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
			if (showProgress)
				System.err.println("Estimating simulation rate.");
			long trialSimTime = start + msec / 100;
			while (System.currentTimeMillis() < trialSimTime) {
				tg.sample();
				N++;
			}
			/* Spend about another 1% estimating the time
			 * after caching and without constant time
			 * measurements. */
			N *= coresToUse;
			long startExact = System.nanoTime();
			multiCoreSim(N, coresToUse);  // updates tgArr
			TraceGenerator[] gens = Arrays.copyOf(tgArr, tgArr.length + 1);
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
			// gen.copy();  // FIXME: how was this call useful ???
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

		multiCoreSim(maxN, coresToUse);
		if (showProgress)
			System.err.println();
		if (VERBOSE)
			System.err.println("End size: " + initialModel.size());
		return gen.getResult(tgArr, alpha);
	}

	public SimulationResult simUnsafeRelErr(double err, double alpha)
	{
		long startTime = System.nanoTime();
		long maxN = 1000;
		long totalSims[] = new long[2];
		double curRelErr, mean;
		SimulationResult result;
		do {
			result = sim(0, maxN, alpha);
			totalSims[0] += result.N;
			totalSims[1] += result.M;
			mean = (result.ubound + result.lbound) / 2;
			double halfWidth = (result.ubound - result.lbound) / 2;
			curRelErr = halfWidth / mean;
			if (showProgress)
				System.err.format("Relative error %g after %d simulations\n", curRelErr, result.N);
			maxN = Math.round(maxN*1.7);
		} while (curRelErr > err);
		SimulationResult polishedResult = new SimulationResult(
				gen.prop, mean, alpha, result.var, result.lbound, result.ubound,
				totalSims, System.nanoTime()-startTime, initialModel.size());
		clearResults();
		return polishedResult;
	}

	public SimulationResult simRelErr(double err, double alpha, long limitN)
	{
		if (relErrRate <= 0)
			return simUnsafeRelErr(err, alpha);
		long maxN = 1000 * coresToUse;
		long totalSims[] = new long[2];
		double totalAlpha = alpha, consumedAlpha;
		double lbound = 0, ubound = 1, mean;
		double curRelErr;
		SimulationResult result;
		long startTime = System.nanoTime();

		alpha = totalAlpha / relErrRate;
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
		alpha = (alpha * (relErrRate - 1)) / relErrRate;
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
			alpha = (alpha * (relErrRate - 1)) / relErrRate;
			consumedAlpha += alpha - (alpha * consumedAlpha);

			if (lbound == 0) { /* we didn't hit anything at all! (unlikely) */
				maxN *= 10;
				continue;
			}
		} while (curRelErr > err);
		if (showProgress)
			System.err.format("Consumed alpha: %g\n", consumedAlpha);

		// Return result with (last) updated bounds and values
		SimulationResult polishedResults = new SimulationResult(
				gen.prop, mean, totalAlpha, result.var, lbound, ubound,
				totalSims, System.nanoTime() - startTime, initialModel.size());
		clearResults();
		return polishedResults;
	}

	/**
	 * Does the instance contain results from previous simulations?
	 * If so, these can be extracted via popResults()
	 */
	public boolean hasResults() { return hasFreshResults; }

	/** Alias for hasResults() */
	public boolean pregnant() { return hasResults(); }

	/***
	 * Obtain the result of the last simulations ran,
	 * and delete them from the internal containers of the instance
	 * @param alpha Alpha value of CI, i.e. 1 - confidence coefficient
	 * @return List of results from all the simulations ran
	 *         ever since last call to popResults()
     * TODO (Enno): make thread-safe plz
	 */
	public SimulationResult popResults(double alpha) throws NoResultsException
	{
		if (!hasResults())
			throw new NoResultsException();
		SimulationResult res = new SimulationResult(gen.prop, alpha);
		if (hasFreshResults)
			res = gen.getResult(tgArr, alpha);
		hasFreshResults = false;
		return res;
	}

	/**
	 * As in "a child": like popResults() but without the throw.
	 * If {@code !this.hasResults()} then bogus results are returned
	 */
	public SimulationResult bear(double alpha)
	{
	    SimulationResult res = new SimulationResult(gen.prop, alpha);
		try {
			if (hasResults())
				res = popResults(alpha);
		} catch (NoResultsException e) { }
		return res;
	}

	/** Delete any existent results */
	private void clearResults() { hasFreshResults = false; }
}
