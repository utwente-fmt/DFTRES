package algorithms;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

// general outlay of a general importance sampling scheme - extensions of this class are to be used
// by actual simulators.

public class Scheme{

	public double[] stateWeights = new double[1];
	public double totalStateWeight;
	public double[] stateProbs = new double[1];
	
	public double[] stateWeightsIS = new double[1];
	public double totalStateWeightIS;
	public double[] stateProbsIS = new double[1];

	public String name;

	public Random rng;
	public ModelGenerator generator;
	
	public int chosen;
	public double delta;
	public double lastDeltaLikelihood;
	
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
	
	public void initGlobalVariables() {
		totalStateWeight = 0;
		totalStateWeightIS = 0;
		neighbours = generator.X.successors.get(generator.currentState);
		if(neighbours == null) {
			generator.findNeighbours(generator.currentState);
			neighbours = generator.X.successors.get(generator.currentState);
		}
		orders = generator.X.orders.get(generator.currentState);
		probs = generator.X.probs.get(generator.currentState);
		
		if (stateWeights.length < neighbours.length) {
			stateWeights = new double[neighbours.length];
			stateProbs = new double[neighbours.length];
			stateWeightsIS = new double[neighbours.length];
			stateProbsIS = new double[neighbours.length];
		}
	}
	
	public void reset() {}

	/**
	 * Computes the weight of all the possible destination states and stores them in an array.
	 * This method needs to be called before drawTransition() is called, they are split into different
	 * methods for tracing purposes. Note that this method does not do anything for instances of Scheme - 
	 * this method should be overwritten by extensions of the general Scheme framework.
	 */
	
	public void computeNewProbs() {

	}
	
	/**
	 * Selects the next state. May write a warning message to the standard output.
	 * 
	 * @return an integer representing the index of the next state.
	 */
	
	public int drawNextState() {
		if (stateProbsIS.length == 1) {
			chosen = 0;
			return generator.X.successors.get(generator.currentState)[0];
		}
		chosen = -1;
		double sumProb = 0; 
		double u = rng.nextDouble();
		for(int i=0;i<stateProbsIS.length;i++) {
			sumProb += stateProbsIS[i];
			if(u<sumProb) {
				chosen = i;
				return generator.X.successors.get(generator.currentState)[i];
			}
		}
		// The program should not reach this part of the code - after all, the IS probabilities should sum to one.
		// However, we have previously found that this part of the code could be reached, possibly due to
		// floating point inaccuracies. Currently, a warning message is written to the standard output, and the last
		// of the possible successor states is chosen.
		System.out.println("Warning; dummy return statement reached in 'drawNextState' in Scheme.java.");
		System.out.println("Possible model error, or Gauss-Seidel accuracy not high enough.");
		System.out.println("State: "+generator.currentState+" = "+Arrays.toString(generator.X.states.get(generator.currentState)));
		System.out.println("Successors (state indices): "+Arrays.toString(generator.X.successors.get(generator.currentState)));
		double totProb = 0;
		for(int i=0;i<generator.X.probs.get(generator.currentState).length;i++) {
			totProb += generator.X.probs.get(generator.currentState)[i];
		}
		System.out.println("Probs: "+Arrays.toString(generator.X.probs.get(generator.currentState)));
		System.out.println("IS Probs: "+Arrays.toString(stateProbsIS));
		System.out.println("Total prob.: "+totProb);
		System.out.println("Chosen: " + u);
		chosen = stateProbsIS.length-1; // possible error due to floating point precision (?)! 10/3/2015: seems so, although it seems to happen less often if tracing is on for some odd reason  
		return generator.X.successors.get(generator.currentState)[chosen];
	}

	/* Note: not combinable with drawDelta (may pick different segments) */
	public int[] extendPath(int[] path)
	{
		int k = generator.currentState;
		if(!generator.X.inHPC.get(k)) {
			if (path.length <= k)
				path = Arrays.copyOf(path, k + 1);
			path[k]++;
			return path;
		}
		double[] pReachSink = new double[1];
		long count = 0;
		StateSpace X = generator.X;
		int[] initialSucc = X.successors.get(k);
		int sink = X.successors.get(generator.currentState)[chosen];
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
		return stateProbs[chosen]/stateProbsIS[chosen];
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

	public double drawDelta(double timeLimit, double force) {
		int k = generator.currentState;
		if(!generator.X.inHPC.get(k)) {
			double rate = generator.X.exitRates[k];
			if (force >= 0) {
				double compLikelihood = Math.exp(-rate * timeLimit);
				double likelihood = 1 - compLikelihood;
				double rand;
				if (lastDeltaLikelihood > force) {
					lastDeltaLikelihood = likelihood;
					rand = rng.nextDouble() * likelihood + compLikelihood;
				} else {
					lastDeltaLikelihood = 1;
					rand = rng.nextDouble();
				}
				delta = -Math.log(rand) / rate;
			} else {
				delta = -Math.log(rng.nextDouble()) / (rate * gamma);
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
		int sink = X.successors.get(generator.currentState)[chosen];
		double sinkProb = X.getProb(k, sink);
		delta = 0;
		lastDeltaLikelihood = 1;

		while(k != sink) {
			double currentDelta;
			double rate = X.exitRates[k];
			if (force >= 0) {
				double compLikelihood = Math.exp(-rate * timeLimit);
				double likelihood = 1 - compLikelihood;
				double rand;
				if (lastDeltaLikelihood > force) {
					lastDeltaLikelihood *= likelihood;
					rand = rng.nextDouble() * likelihood + compLikelihood;
				} else {
					rand = rng.nextDouble();
				}
				/*
				if (likelihood < 1e-6)
					force = false;
				else
					lastDeltaLikelihood *= likelihood;
					*/
				currentDelta = -Math.log(rand) / rate;
			} else {
				currentDelta = -Math.log(rng.nextDouble())/rate;
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

	/**
	 * Returns the name of the scheme.
	 * @return scheme name
	 */
	
	public String getName() {
		return this.name;
	}
}
