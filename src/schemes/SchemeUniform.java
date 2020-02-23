package schemes;
import algorithms.Scheme;
import algorithms.SearchAlgorithm;
import models.StateSpace;
import java.util.Random;

/** Importance sampling scheme that completely ignores actual transition
 * probabilities and instead uniformly selects between the transitions.
 */
public class SchemeUniform extends Scheme {
	private double[] myStateWeights = new double[1];
	
	public SchemeUniform(StateSpace model) {
		super(model, "uniform sampling");
	}

	public SchemeUniform clone()
	{
		return new SchemeUniform(model);
	}

	public boolean isBinomial() {
		return false;
	}

	public StateSpace.Neighbours prepareState(StateSpace.State state, double timeBound) {
		StateSpace.Neighbours ret = super.prepareState(state, timeBound);
		if (myStateWeights.length < probs.length)
			myStateWeights = new double[probs.length];
		stateWeightsIS = myStateWeights;
		totalStateWeightIS = 1;
		for(int i=0;i<probs.length;i++)
			stateWeightsIS[i] = 1.0 / probs.length;
		return ret;
	}
}
