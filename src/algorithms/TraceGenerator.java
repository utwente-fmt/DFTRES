package algorithms;

import models.StateSpace;
import java.util.Arrays;
import java.util.Map;
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
	public static boolean enableHpcBoost = true;

	public final Scheme scheme;
	public final Property prop;
	private long startTime;

	private int chosen;
	private StateSpace.State prevState;
	private StateSpace.Neighbours nbs;
	private double delta;
	private double lastDeltaLikelihood;
	private double[] pReachSink = new double[1];

	private final Random rng;

	public TraceGenerator(Random rng, Scheme scheme, Property prop)
	{
		this(rng, scheme, prop, Double.POSITIVE_INFINITY);
	}

	public TraceGenerator(Random rng, Scheme scheme, Property prop, double forceBound)
	{
		this.scheme = scheme;
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

	public StateSpace.State drawNextState(StateSpace.State state) {
		return drawNextState(state, Double.POSITIVE_INFINITY);
	}

	/**
	 * Get the weights of the transitions for the current state.
	 */
	public StateSpace.State drawNextState(StateSpace.State state, double timeBound) {
		lastDeltaLikelihood = 1;
		prevState = state;
		nbs = scheme.prepareState(prevState, timeBound);
		if (scheme.neighbours.length == 1) {
			chosen = 0;
			return scheme.neighbours[0];
		}
		if (scheme.neighbours.length == 0) {
			chosen = -1;
			return prevState;
		}
		double sumProb = 0;
		double u = rng.nextDouble() * scheme.totalStateWeightIS;
		for (int i = scheme.neighbours.length - 1; i >= 0; i--) {
			sumProb += scheme.stateWeightsIS[i];
			if(u < sumProb) {
				chosen = i;
				//System.err.println("Going to: " + scheme.neighbours[i]);
				return scheme.neighbours[i];
			}
		}
		System.err.println("WARNING: State selection problem from state " + Arrays.toString(prevState.state) + ", probably due to floating-point roundoff error.");
		System.err.println("Probabilities: " + Arrays.toString(scheme.stateWeightsIS));
		System.err.println("Random: " + u + " out of " + scheme.totalStateWeightIS + " (sum " + sumProb + ")");
		System.err.println("Original: " + Arrays.toString(scheme.probs));
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
	private StateSpace.State drawHPCSuccessor(StateSpace.HPCState state, double sinkBoost)
	{
		StateSpace model = scheme.model;
		StateSpace.State sink = scheme.neighbours[chosen];
		StateSpace.State[] succ = state.origNeighbours.neighbours;
		if (pReachSink.length < succ.length)
			pReachSink = new double[succ.length];
		double[] probs = state.origNeighbours.probs;
		double sumP = 0;
		double boost = 0;
		double sinkProb = 0;
		for (int i = 0; i < succ.length; i++) {
			double p;
			StateSpace.State s = succ[i];
			if (s == sink) {
				p = sinkBoost;
				boost = (sinkBoost - 1) * probs[i];
				sinkProb = probs[i];
			}
			else if (!(s instanceof StateSpace.HPCState))
				p = 0;
			else if (s == prevState)
				p = scheme.probs[chosen];
			else
				p = ((StateSpace.HPCState)s).getProbTo(sink);
			sumP = Math.fma(p, probs[i], sumP);
			pReachSink[i] = sumP;
		}
		double u = rng.nextDouble() * sumP;
		int i;
		for (i = 0; u > pReachSink[i]; i++)
			;
		StateSpace.State ret = succ[i];
		if (ret == sink) {
			double num = sumP * sinkProb;
			double denom = (sumP - boost)*(sinkProb + boost);
			double ll = num / denom;
			//System.err.println("Boosted likelihood: " + ll);
			lastDeltaLikelihood *= ll;
		} else if (boost != 1) {
			double ll = sumP / (sumP - boost);
			//System.err.println("Dropped likelihood: " + ll + ", sum " + sumP + ", boost " + boost);
			lastDeltaLikelihood *= ll;
		}
		return ret;
	}

	/**
	 * Quick note on paths: The integer is actually the number of
	 * visits minus one (i.e., it is possible to encounter the
	 * Integer 0, meaning the state is visited once on the path).
	 */
	public void extendPath(Map<StateSpace.State, int[]> path)
	{
		if(!(prevState instanceof StateSpace.HPCState)) {
			int[] num = path.get(prevState);
			if (num == null) {
				num = new int[1];
				path.put(prevState, num);
			} else {
				num[0]++;
			}
			return;
		}
		StateSpace.State k = prevState;
		long count = 0;
		StateSpace.State sink = scheme.neighbours[chosen];

		while(k != sink) {
			int[] num = path.get(k);
			if (num == null) {
				num = new int[1];
				path.put(k, num);
			} else {
				num[0]++;
			}
			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			if (!(k instanceof StateSpace.HPCState))
				throw new AssertionError("Non-HPC state in HPC");
			k = drawHPCSuccessor((StateSpace.HPCState)k, 1);
		}
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

	/** Returns [time, likelihood] */
	protected double[] pathToTimed(Map<StateSpace.State, int[]> path, double timeBound)
	{
		double origTimeBound = timeBound;
		lastDeltaLikelihood = 1;
		for (Map.Entry<StateSpace.State, int[]> entry : path.entrySet()) {
			double rate = entry.getKey().getNeighbours().exitRate;
			int count = entry.getValue()[0];
			while (rate-- > 0)
				timeBound -= drawExponential(rate, timeBound);
		}
		return new double[] {origTimeBound - timeBound,
		                     lastDeltaLikelihood};
	}

	/**
	 * Draws a new state sojourn time (denoted by delta).
	 *
	 * @return sojourn time.
	 */
	public double drawDelta(double timeBound) {
		if (chosen == -1) {
			lastDeltaLikelihood = 1;
			return Double.POSITIVE_INFINITY;
		}
		if(!(prevState instanceof StateSpace.HPCState)) {
			lastDeltaLikelihood = 1;
			delta = drawExponential(prevState.getNeighbours().exitRate, timeBound);
			return delta;
		}

		StateSpace.State s = prevState;
		long count = 0;
		StateSpace.State sink = scheme.neighbours[chosen];
		delta = 0;
		while(s != sink) {
			StateSpace.Neighbours nbs = s.getNeighbours();
			delta += drawExponential(nbs.exitRate, timeBound);
			if (delta > timeBound)
				return Double.POSITIVE_INFINITY;

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);
			if (!(s instanceof StateSpace.HPCState))
				throw new AssertionError("Found non-HPC state in HPC");
			StateSpace.HPCState hs = (StateSpace.HPCState)s;
			double sinkBoost = 1;
			if (enableHpcBoost) {
				double tRate = nbs.exitRate * hs.origNeighbours.getProbTo(sink);
				if (tRate != 0)
					sinkBoost = 1 / (tRate * (timeBound - delta));
				/* With excessive boosting we can get numerical
				 * stability issues, as the likelihood ratie of
				 * the non-boosted states can approach infinity.
				 */
				if (sinkBoost > 10000)
					sinkBoost = 10000;
			}
			s = drawHPCSuccessor(hs, sinkBoost);
		}
		return delta;
	}

	public double drawMeanTransitionTime() {
		if (chosen == -1) {
			lastDeltaLikelihood = 1;
			return Double.POSITIVE_INFINITY;
		}
		if(!(prevState instanceof StateSpace.HPCState))
			return 1 / prevState.getNeighbours().exitRate;
		StateSpace.HPCState k = (StateSpace.HPCState)prevState;
		if (k.meanTimes != null)
			return k.meanTimes[chosen];

		long count = 0;
		StateSpace.State sink = scheme.neighbours[chosen];
		double ret = 0;

		while(k != sink) {
			StateSpace.State s = k;
			ret += 1 / s.getNeighbours().exitRate;

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			s = drawHPCSuccessor(k, 1);
			if (!(s instanceof StateSpace.HPCState)) {
				if (s == sink)
					return ret;
				throw new AssertionError("Exited HPC via wrong sink.");
			}
		}
		return ret;
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
