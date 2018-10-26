package algorithms;
import models.StateSpace;
import java.util.Arrays;
import java.util.Map;

// general outlay of a general importance sampling scheme - extensions of this class are to be used
// by actual simulators.

public class Scheme
{
	public final String name;
	private final StateSpace initialModel;

	public StateSpace model;

	public double[] stateWeightsIS;
	public double totalStateWeightIS;
	public int[] neighbours;

	protected int[] orders;
	protected double[] probs;

	public Scheme(StateSpace model) {
		this(model, "standard Monte Carlo");
	}

	public Scheme(StateSpace model, String name) {
		this.initialModel = model;
		this.model = model.snapshot();
		this.name = name;
	}

	public Scheme clone()
	{
		return new Scheme(initialModel);
	}

	public boolean isBinomial() {
		return true;
	}

	public void prepareState(int state) {
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
		System.err.println("Resetting cache");
		this.model = initialModel.snapshot();
	}
	
	/**
	 * Returns the likelihood ratio of the chosen state transition
	 * and sojourn time.
	 *
	 * Assumes the sojourn time was samples exponentially based on
	 * the indicated exit rate of the last state.
	 * 
	 * @return the likelihood ratio between the old and new measure
	 * of the drawn transition.
	 */
	public double likelihood(int t, double delta) {
		/* We do not currently change the exit rate, so the
		 * sojourn time has no effect.
		 */
		return probs[t] * totalStateWeightIS / stateWeightsIS[t];
	}

	/**
	 * Returns the name of the scheme.
	 * @return scheme name
	 */
	public String getName() {
		return this.name;
	}
}
