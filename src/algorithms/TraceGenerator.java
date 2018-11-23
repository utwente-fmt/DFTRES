package algorithms;

import models.StateSpace;
import java.util.Arrays;
import java.util.Random;
import nl.utwente.ewi.fmt.EXPRES.Property;
import nl.ennoruijters.interval.XoroShiro128RandomSource;
import ec.util.MersenneTwisterFast;

/* General note: Only use one of extendPath, drawDelta, or drawMeanDelta
 * per transition.
 * Multiple calls may yield different paths through HPCs.
 */
public abstract class TraceGenerator
{
	public static double acceleration = 1;
	public final double forceBound;

	public final Scheme scheme;
	public final Property prop;
	public final int baseModelSize;
	private long startTime;

	private int chosen;
	private int prevState;
	private double delta;
	private double lastDeltaLikelihood;
	private double[] pReachSink = new double[1];

	private final Random rng;

	public TraceGenerator(Random rng, Scheme scheme, Property prop)
	{
		this.scheme = scheme;
		baseModelSize = scheme.model.size();
		this.prop = prop;
		this.rng = rng;
		this.forceBound = Double.POSITIVE_INFINITY;
		startTime = System.nanoTime();
	}

	public TraceGenerator(Random rng, Scheme scheme, Property prop, double forceBound)
	{
		this.scheme = scheme;
		baseModelSize = scheme.model.size();
		this.prop = prop;
		this.rng = rng;
		this.forceBound = forceBound;
		startTime = System.nanoTime();
	}

	protected Random subRNG()
	{
		Random ret;
		if (rng instanceof XoroShiro128RandomSource) {
			ret = ((XoroShiro128RandomSource)rng).long_jump();
		} else if (rng instanceof MersenneTwisterFast) {
			/* To be deterministic, we instantiate several new
			 * RNGs from the original. We use XoroShiro to select
			 * the new states, to make sure (with high
			 * probability) there is no weird relationship
			 * between the states.
			 */
			int state[] = new int[624];
			XoroShiro128RandomSource t;
			t = new XoroShiro128RandomSource(rng.nextLong());
			for (int j = 0; j < state.length; j++)
				state[j] = t.nextInt();
			ret = new MersenneTwisterFast(state);
		} else {
			System.err.println("WARNING: Unknown RNG for multicore simulation, defaulting to Xoroshiro128.");
			ret = new XoroShiro128RandomSource(rng.nextLong());
		}
		return ret;
	}

	public abstract TraceGenerator copy();

	public void reseedRNG(long seed) {
		rng.setSeed(seed);
	}

	/**
	 * Get the weights of the transitions for the current state.
	 */
	public int drawNextState(int state) {
		prevState = state;
		lastDeltaLikelihood = 1;
		scheme.prepareState(state);
		if (scheme.neighbours.length == 1) {
			chosen = 0;
			return scheme.neighbours[0];
		}
		if (scheme.neighbours.length == 0) {
			chosen = -1;
			return state;
		}
		double sumProb = 0;
		double u = rng.nextDouble() * scheme.totalStateWeightIS;
		for (int i = scheme.neighbours.length - 1; i >= 0; i--) {
			sumProb += scheme.stateWeightsIS[i];
			if(u < sumProb) {
				chosen = i;
				return scheme.neighbours[i];
			}
		}
		System.err.println("WARNING: State selection problem, probably due to floating-point roundoff error.");
		chosen = 0;
		return scheme.neighbours[chosen];
	}

	protected double likelihood()
	{
		return scheme.likelihood(chosen, delta) * lastDeltaLikelihood;
	}

	public boolean isDeadlocked() {
		return chosen == -1;
	}

	/** Draw successor state conditional on eventually leaving the
	 * HPC to the chosen state.
	 */
	/*
	private int drawHPCSuccessor(int state, int sink)
	{
		double u = rng.nextDouble() * sumP;
		for (state = 0; u > pReachSink[state]; state++)
			;
		return succ[state];
	}
	*/
	private int drawHPCSuccessor(int state)
	{
		StateSpace model = scheme.model;
		int sink = scheme.neighbours[chosen];
		StateSpace.HPCState s = model.hpcs.get(state);
		int[] succ = s.successors;
		if (pReachSink.length < succ.length)
			pReachSink = new double[succ.length];
		double[] probs = s.probs;
		double sumP = 0;
		for (int i = 0; i < succ.length; i++) {
			double p;
			state = succ[i];
			if (state == sink)
				p = 1;
			else if (!model.inHPC.get(state))
				p = 0;
			else if (state == prevState)
				p = scheme.probs[chosen];
			else
				p = scheme.model.getProb(state, sink);
			sumP = Math.fma(p, probs[i], sumP);
			pReachSink[i] = sumP;
		}
		double u = rng.nextDouble() * sumP;
		for (state = 0; u > pReachSink[state]; state++)
			;
		return succ[state];
	}

	public int[] extendPath(int[] path)
	{
		if(!scheme.model.inHPC.get(prevState)) {
			if (path.length <= prevState)
				path = Arrays.copyOf(path, prevState + 1);
			path[prevState]++;
			return path;
		}
		int k = prevState;
		long count = 0;
		int sink = scheme.neighbours[chosen];

		while(k != sink) {
			if (path.length <= k)
				path = Arrays.copyOf(path, k + 1);
			path[k]++;
			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			k = drawHPCSuccessor(k);
		}
		return path;
	}

	private double drawExponential(double rate, double timeLimit)
	{
		/* Somewhat complicated method to draw more accurate
		 * values close to 0.
		 */
		double ret;
		if (Double.isFinite(timeLimit)
		    && lastDeltaLikelihood > forceBound)
		{
			double likelihood = -Math.expm1(-rate * timeLimit);
			double rand;
			lastDeltaLikelihood *= likelihood;
			rand = Math.fma(rng.nextDouble(), likelihood, -likelihood);
			ret = -Math.log1p(rand);
		} else {
			long l = rng.nextLong();
			if (l < 0)
				ret = -Math.log1p(l * (-0.5 / Long.MIN_VALUE));
			else
				ret = -Math.log((~l) * (0.5 / Long.MIN_VALUE));
			if (acceleration != 1) {
				rate *= acceleration;
				lastDeltaLikelihood *= Math.exp(ret * (acceleration - 1)) / acceleration;
			}
		}
		return ret / rate;
	}

	/**
	 * Draws a new state sojourn time (denoted by delta).
	 *
	 * @return sojourn time.
	 */
	public double drawDelta(double timeBound) {
		int k = prevState;
		if (chosen == -1) {
			lastDeltaLikelihood = 1;
			return Double.POSITIVE_INFINITY;
		}
		if(!scheme.model.inHPC.get(k)) {
			double rate = scheme.model.exitRates[k];
			lastDeltaLikelihood = 1;
			delta = drawExponential(rate, timeBound);
			return delta;
		}

		long count = 0;
		int sink = scheme.neighbours[chosen];
		delta = 0;
		while(k != sink) {
			double rate = scheme.model.exitRates[k];
			delta += drawExponential(rate, timeBound);
			if (delta > timeBound)
				return Double.POSITIVE_INFINITY;

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);
			k = drawHPCSuccessor(k);
		}
		return delta;
	}

	public double drawMeanTransitionTime() {
		int k = prevState;
		if (chosen == -1) {
			lastDeltaLikelihood = 1;
			return Double.POSITIVE_INFINITY;
		}
		if(!scheme.model.inHPC.get(k))
			return 1 / scheme.model.exitRates[k];

		double[] pReachSink = new double[1];
		long count = 0;
		int sink = scheme.neighbours[chosen];
		double ret = 0;

		while(k != sink) {
			ret += 1 / scheme.model.exitRates[k];

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			k = drawHPCSuccessor(k);
		}
		return ret;
	}

	public void resetModelCache()
	{
		scheme.resetModelCache();
	}

	public void reset()
	{
		startTime = System.nanoTime();
	}

	public void resetAndEstimateMeans()
	{
		reset();
	}

	public void resetAndEstimateMeans(TraceGenerator[] ts)
	{
		reset();
	}

	/** Get time in nanoseconds since creation or last reset. */
	public long getElapsedTime()
	{
		return System.nanoTime() - startTime;
	}

	public abstract SimulationResult getResult(double alpha);
	public abstract SimulationResult getResult(TraceGenerator[] ts, double alpha);
	public abstract void sample();
}
