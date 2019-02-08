package algorithms;
import models.StateSpace;
import java.util.Arrays;
import java.util.Map;
import models.StateSpace;

// general outlay of a general importance sampling scheme - extensions of this class are to be used
// by actual simulators.

public class Scheme
{
	public final String name;
	public StateSpace model;

	public double[] stateWeightsIS;
	public double totalStateWeightIS;
	public StateSpace.State[] neighbours;
	public double exitRate;

	protected short[] orders;
	protected double[] probs;

	public Scheme(StateSpace model) {
		this(model, "standard Monte Carlo");
	}

	public Scheme(StateSpace model, String name) {
		this.model = model;
		this.name = name;
	}

	public Scheme clone()
	{
		return new Scheme(model);
	}

	public boolean isBinomial() {
		return true;
	}

	public StateSpace.Neighbours prepareState(StateSpace.State state) {
		StateSpace.Neighbours n = state.getNeighbours();
		neighbours = n.neighbours;
		exitRate = n.exitRate;
		orders = n.orders;
		probs = n.probs;

		stateWeightsIS = probs;
		totalStateWeightIS = 1;
		return n;
	}
	
	public void resetModelCache(StateSpace initial) {
		this.model = initial;
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
		if (probs.length == 0)
			return 1;
		return probs[t] * totalStateWeightIS / stateWeightsIS[t];
	}

	/**
	 * Returns the name of the scheme.
	 * @return scheme name
	 */
	public String getName() {
		return this.name;
	}

	public int storedStates() {
		return 1; /* Only the initial state is stored by default */
	}
}
