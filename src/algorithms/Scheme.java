package algorithms;
import models.StateSpace;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

// general outlay of a general importance sampling scheme - extensions of this class are to be used
// by actual simulators.

public class Scheme
{
	protected double[] stateWeightsIS;
	protected double totalStateWeightIS;

	public final String name;

	public Random rng;
	public StateSpace model;
	private final StateSpace initialModel;
	
	private int chosen;
	private int prevState;
	private double delta;
	private double lastDeltaLikelihood;
	
	protected int[] neighbours;
	protected int[] orders;
	protected double[] probs;

	public static double gamma = 1;
	private double[] pReachSink = new double[1];

	public Scheme(Random rng, StateSpace model) {
		this(rng, model, "standard Monte Carlo");
	}

	public Scheme(Random rng, StateSpace model, String name) {
		this.rng = rng;
		this.initialModel = model;
		this.model = model.snapshot();
		this.name = name;
	}

	public boolean isBinomial() {
		return true;
	}

	/**
	 * Executes a set of processing steps that need to occur every time a new state is drawn.
	 */
	
	public void initGlobalVariables(int state) {
		prevState = state;
		neighbours = model.successors.get(state);
		if(neighbours == null) {
			model.findNeighbours(state);
			neighbours = model.successors.get(state);
		}
		orders = model.orders.get(state);
		probs = model.probs.get(state);
		
		stateWeightsIS = probs;
		totalStateWeightIS = 1;
	}
	
	public void resetModelCache() {
		this.model = initialModel.snapshot();
	}

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
			return model.successors.get(prevState)[0];
		}
		chosen = -1;
		double sumProb = 0; 
		double u = rng.nextDouble() * totalStateWeightIS;
		for(int i=0;i<stateWeightsIS.length;i++) {
			sumProb += stateWeightsIS[i];
			if(u<sumProb) {
				chosen = i;
				return model.successors.get(prevState)[i];
			}
		}
		// The program should not reach this part of the code - after all, the IS probabilities should sum to one.
		// However, we have previously found that this part of the code could be reached, possibly due to
		// floating point inaccuracies. Currently, a warning message is written to the standard output, and the last
		// of the possible successor states is chosen.
		System.out.println("Warning; dummy return statement reached in 'drawNextState' in Scheme.java.");
		System.out.println("Possible model error, or Gauss-Seidel accuracy not high enough.");
		System.out.println("State: "+prevState+" = "+Arrays.toString(model.states.get(prevState)));
		System.out.println("Successors (state indices): "+Arrays.toString(model.successors.get(prevState)));
		double totProb = 0;
		for(int i=0;i<model.probs.get(prevState).length;i++) {
			totProb += model.probs.get(prevState)[i];
		}
		System.out.println("Probs: "+Arrays.toString(model.probs.get(prevState)));
		System.out.println("IS Weight: "+Arrays.toString(stateWeightsIS));
		System.out.println("IS total weight: "+totalStateWeightIS);
		System.out.println("Real total weight.: "+totProb);
		System.out.println("Chosen: " + u);
		chosen = stateWeightsIS.length-1; // possible error due to floating point precision (?)! 10/3/2015: seems so, although it seems to happen less often if tracing is on for some odd reason
		return model.successors.get(prevState)[chosen];
	}

	/** Draw successor state conditional on eventually leaving the
	 * HPC to the sink state.
	 */
	private int drawHPCSuccessor(int state, int sink)
	{
		int k;
		StateSpace.HPCState s = model.hpcs.get(state);
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
			else if (!model.inHPC.get(k))
				p = 0;
			else if (k == prevState)
				p = model.probs.get(k)[chosen];
			else
				p = model.getProb(k, sink);
			sumP = Math.fma(p, probs[i], sumP);
			pReachSink[i] = sumP;
		}
		double u = rng.nextDouble() * sumP;
		for (k = 0; u > pReachSink[k]; k++)
			;
		return succ[k];
	}

	/* Note: not combinable with drawDelta (may pick different segments) */
	public int[] extendPath(int[] path)
	{
		if(!model.inHPC.get(prevState)) {
			if (path.length <= prevState)
				path = Arrays.copyOf(path, prevState + 1);
			path[prevState]++;
			return path;
		}
		int k = prevState;
		long count = 0;
		int sink = model.successors.get(prevState)[chosen];

		while(k != sink) {
			if (path.length <= k)
				path = Arrays.copyOf(path, k + 1);
			path[k]++;
			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			k = drawHPCSuccessor(k, sink);
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
		if(!model.inHPC.get(k)) {
			double rate = model.exitRates[k];
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
		long count = 0;
		int sink = model.successors.get(prevState)[chosen];
		delta = 0;
		lastDeltaLikelihood = 1;

		while(k != sink) {
			double currentDelta;
			double rate = model.exitRates[k];
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
			if (delta > timeLimit)
				return Double.POSITIVE_INFINITY;

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);
			k = drawHPCSuccessor(k, sink);
		}
		//System.out.println("jump from "+generator.currentState+" to "+generator.X.successors.get(generator.currentState)[chosen]);
		//System.out.println("--- delta: "+delta);
		return delta;
	}

	public double drawMeanTransitionTime() {
		int k = prevState;
		if(!model.inHPC.get(k))
			return 1 / model.exitRates[k];

		double[] pReachSink = new double[1];
		long count = 0;
		int sink = model.successors.get(prevState)[chosen];
		double ret = 0;

		while(k != sink) {
			ret += 1 / model.exitRates[k];

			count++;
			if (count % 1048576 == 0)
				System.err.format("%d Tries.\n", count);

			k = drawHPCSuccessor(k, sink);
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
