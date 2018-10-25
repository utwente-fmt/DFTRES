package algorithms;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

// general outlay of a general importance sampling scheme - extensions of this class are to be used
// by actual simulators.

public class Scheme
{
	public double[] stateWeightsIS;
	public double totalStateWeightIS;

	public String name;

	public Random rng;
	public ModelGenerator generator;
	
	private int chosen;
	private int prevState;
	private double delta;
	private double lastDeltaLikelihood;
	
	public int[] neighbours;
	public int[] orders;
	public double[] probs;

	public static double gamma = 1;

	public Scheme(Random rng, ModelGenerator gen) {
		this.rng = rng;
		generator = gen;
	}
	
	/**
	 * Executes a set of processing steps that need to occur every time a new state is drawn.
	 */
	
	public void initGlobalVariables(int state) {
		prevState = state;
		neighbours = generator.X.successors.get(state);
		if(neighbours == null) {
			generator.findNeighbours(state);
			neighbours = generator.X.successors.get(state);
		}
		orders = generator.X.orders.get(state);
		probs = generator.X.probs.get(state);
		
		stateWeightsIS = probs;
		totalStateWeightIS = 1;
	}
	
	public void reset() {}

	/**
	 * Computes the weight of all the possible destination states and stores them in an array.
	 * This method needs to be called before drawTransition() is called, they are split into different
	 * methods for tracing purposes. Note that this method does not do anything for instances of Scheme - 
	 * this method should be overwritten by extensions of the general Scheme framework.
	 */
	
	public void computeNewProbs(int state) {
		initGlobalVariables(state);
	}
	
	/**
	 * Selects the next state. May write a warning message to the standard output.
	 * 
	 * @return an integer representing the index of the next state.
	 */
	
	public int drawNextState() {
		if (stateWeightsIS.length == 1) {
			chosen = 0;
			return generator.X.successors.get(prevState)[0];
		}
		chosen = -1;
		double sumProb = 0; 
		double u = rng.nextDouble() * totalStateWeightIS;
		for(int i=0;i<stateWeightsIS.length;i++) {
			sumProb += stateWeightsIS[i];
			if(u<sumProb) {
				chosen = i;
				return generator.X.successors.get(prevState)[i];
			}
		}
		// The program should not reach this part of the code - after all, the IS probabilities should sum to one.
		// However, we have previously found that this part of the code could be reached, possibly due to
		// floating point inaccuracies. Currently, a warning message is written to the standard output, and the last
		// of the possible successor states is chosen.
		System.out.println("Warning; dummy return statement reached in 'drawNextState' in Scheme.java.");
		System.out.println("Possible model error, or Gauss-Seidel accuracy not high enough.");
		System.out.println("State: "+prevState+" = "+Arrays.toString(generator.X.states.get(prevState)));
		System.out.println("Successors (state indices): "+Arrays.toString(generator.X.successors.get(prevState)));
		double totProb = 0;
		for(int i=0;i<generator.X.probs.get(prevState).length;i++) {
			totProb += generator.X.probs.get(prevState)[i];
		}
		System.out.println("Probs: "+Arrays.toString(generator.X.probs.get(prevState)));
		System.out.println("IS Weight: "+Arrays.toString(stateWeightsIS));
		System.out.println("IS total weight: "+totalStateWeightIS);
		System.out.println("Real total weight.: "+totProb);
		System.out.println("Chosen: " + u);
		chosen = stateWeightsIS.length-1; // possible error due to floating point precision (?)! 10/3/2015: seems so, although it seems to happen less often if tracing is on for some odd reason
		return generator.X.successors.get(prevState)[chosen];
	}

	/* Note: not combinable with drawDelta (may pick different segments) */
	public int[] extendPath(int[] path)
	{
		if(!generator.X.inHPC.get(prevState)) {
			if (path.length <= prevState)
				path = Arrays.copyOf(path, prevState + 1);
			path[prevState]++;
			return path;
		}
		int k = prevState;
		double[] pReachSink = new double[1];
		long count = 0;
		StateSpace X = generator.X;
		int[] initialSucc = X.successors.get(k);
		int sink = X.successors.get(prevState)[chosen];
		double sinkProb = X.getProb(k, sink);
		delta = 0;

		while(k != sink) {
			if (path.length <= k)
				path = Arrays.copyOf(path, k + 1);
			path[k]++;
			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			StateSpace.HPCState s = X.hpcs.get(k);
			int[] succ = s.successors;
			if (pReachSink.length < succ.length)
				pReachSink = new double[succ.length];
			double[] probs = s.probs;
			double sumP = 0;
			for (int i = 0; i < succ.length; i++) {
				double p;
				k = succ[i];
				if (k == sink)
					p = 1;
				else if (!X.inHPC.get(k))
					p = 0;
				else if (X.successors.get(k) == initialSucc)
					p = X.probs.get(k)[chosen];
				else
					p = X.getProb(k, sink);
				sumP = Math.fma(p, probs[i], sumP);
				pReachSink[i] = sumP;
			}
			double u = rng.nextDouble() * sumP;
			for (k = 0; u > pReachSink[k]; k++)
				;
			k = succ[k];
		}
		return path;
	}
	
	/**
	 * Returns the likelihood ratio of the chosen state transition. Does not allow for changes in the sojourn time density at the moment.
	 * 
	 * @return the likelihood ratio between the old and new measure of the drawn destination state
	 */
	
	public double likelihood() { 
		//return probs[chosen]/(stateWeightsIS[chosen] / totalStateWeightIS);
		return probs[chosen] * totalStateWeightIS / stateWeightsIS[chosen];
	}

	/**
	 * Draws a new state sojourn time (denoted by delta).
	 * 
	 * @return sojourn time, exponentially distributed with the exit rate of the current state
	 */

	public double deltaLikelihood ()
	{
		return lastDeltaLikelihood;
	}

	private double drawExponential()
	{
		/* Somewhat complicated method to draw more accurate
		 * values close to 0.
		 */
		long l = rng.nextLong();
		if (l < 0)
			return -Math.log1p(l * (-0.5 / Long.MIN_VALUE));
		else
			return -Math.log((~l) * (0.5 / Long.MIN_VALUE));
	}

	public double drawDelta(double timeLimit, double force) {
		int k = prevState;
		if(!generator.X.inHPC.get(k)) {
			double rate = generator.X.exitRates[k];
			if (force >= 0) {
				double compLikelihood = Math.exp(-rate * timeLimit);
				double likelihood = -Math.expm1(-rate * timeLimit);
				double rand;
				if (lastDeltaLikelihood > force) {
					lastDeltaLikelihood = likelihood;
					rand = Math.fma(rng.nextDouble(), likelihood, compLikelihood);
					delta = -Math.log(rand) / rate;
				} else {
					lastDeltaLikelihood = 1;
					rand = drawExponential() / rate;
				}
			} else {
				delta = drawExponential() / (rate * gamma);
				if (gamma == 1)
					lastDeltaLikelihood = 1;
				else
					lastDeltaLikelihood = Math.exp(rate * delta * (gamma - 1)) / gamma;
			}
			return delta;
		}
		double[] pReachSink = new double[1];
		long count = 0;
		StateSpace X = generator.X;
		int[] initialSucc = X.successors.get(k);
		int sink = X.successors.get(prevState)[chosen];
		double sinkProb = X.getProb(k, sink);
		delta = 0;
		lastDeltaLikelihood = 1;

		while(k != sink) {
			double currentDelta;
			double rate = X.exitRates[k];
			if (force >= 0) {
				double compLikelihood = Math.exp(-rate * timeLimit);
				double likelihood = -Math.expm1(-rate * timeLimit);
				double rand;
				if (lastDeltaLikelihood > force) {
					lastDeltaLikelihood *= likelihood;
					rand = Math.fma(rng.nextDouble(), likelihood, compLikelihood);
					currentDelta = -Math.log(rand) / rate;
				} else {
					currentDelta = drawExponential() / rate;
				}
			} else {
				currentDelta = drawExponential() / rate;
			}
			delta += currentDelta;
			timeLimit -= currentDelta;
			if (timeLimit < 0)
				return Double.POSITIVE_INFINITY;

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			StateSpace.HPCState s = X.hpcs.get(k);
			int[] succ = s.successors;
			if (pReachSink.length < succ.length)
				pReachSink = new double[succ.length];
			double[] probs = s.probs;
			double sumP = 0;
			for (int i = 0; i < succ.length; i++) {
				double p;
				k = succ[i];
				if (k == sink)
					p = 1;
				else if (!X.inHPC.get(k))
					p = 0;
				else if (X.successors.get(k) == initialSucc)
					p = X.probs.get(k)[chosen];
				else
					p = X.getProb(k, sink);
				sumP = Math.fma(p, probs[i], sumP);
				pReachSink[i] = sumP;
			}
			double u = rng.nextDouble() * sumP;
			for (k = 0; u > pReachSink[k]; k++)
				;
			k = succ[k];
		}
		//System.out.println("jump from "+generator.currentState+" to "+generator.X.successors.get(generator.currentState)[chosen]);
		//System.out.println("--- delta: "+delta);
		return delta;
	}

	public double drawMeanTransitionTime() {
		int k = prevState;
		if(!generator.X.inHPC.get(k))
			return 1 / generator.X.exitRates[k];

		double[] pReachSink = new double[1];
		long count = 0;
		StateSpace X = generator.X;
		int[] initialSucc = X.successors.get(k);
		int sink = X.successors.get(prevState)[chosen];
		double sinkProb = X.getProb(k, sink);
		double ret = 0;

		while(k != sink) {
			ret += 1 / X.exitRates[k];

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			StateSpace.HPCState s = X.hpcs.get(k);
			int[] succ = s.successors;
			if (pReachSink.length < succ.length)
				pReachSink = new double[succ.length];
			double[] probs = s.probs;
			double sumP = 0;
			for (int i = 0; i < succ.length; i++) {
				double p;
				k = succ[i];
				if (k == sink)
					p = 1;
				else if (!X.inHPC.get(k))
					p = 0;
				else if (X.successors.get(k) == initialSucc)
					p = X.probs.get(k)[chosen];
				else
					p = X.getProb(k, sink);
				sumP = Math.fma(p, probs[i], sumP);
				pReachSink[i] = sumP;
			}
			double u = rng.nextDouble() * sumP;
			for (k = 0; u > pReachSink[k]; k++)
				;
			k = succ[k];
		}
		return ret;
	}
	/**
	 * Returns the name of the scheme.
	 * @return scheme name
	 */
	
	public String getName() {
		return this.name;
	}
}
