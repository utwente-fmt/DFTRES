package schemes;

import algorithms.Scheme;
import algorithms.SearchAlgorithm;
import models.StateSpace;
import models.StateSpace.Neighbours;
import models.StateSpace.State;
import java.util.HashMap;
import java.util.Random;
import nl.utwente.ewi.fmt.EXPRES.Property;

/** Path-ZVAv schema extended to include estimated probability of timout
 */
public class SchemeZVAt extends Scheme {
	private static class StateInfo {
		/* The Neighbours needs to be kept in here to prevent
		 * garbage collection of this data in the actual state,
		 * which could lead to re-ordering of the neighbours
		 * when it is recreated.
		 */
		public final Neighbours nbs;
		public final double[] weights;
		public final double[] exitRates;
		public StateInfo(Neighbours nbs, double[] weights, double exitRates[])
		{
			this.nbs = nbs;
			this.weights = weights;
			this.exitRates = exitRates;
		}
	}
	private final HashMap<State, StateInfo> info;
	private final double[] stateWeightArray;

	private SchemeZVAt(StateSpace model, HashMap<State, StateInfo> info,
	                   int arraySize)
	{
		super(model, "Path-ZVA-v");
		this.info = info;
		stateWeightArray = new double[arraySize];
	}

	public SchemeZVAt clone()
	{
		return new SchemeZVAt(model, info, stateWeightArray.length);
	}

	public boolean isBinomial() {
		return false;
	}

	public static SchemeZVAt instantiate(StateSpace model, Property prop)
	{
		HashMap<State, StateInfo> info = new HashMap<>();
		SearchAlgorithm search = new SearchAlgorithm(model, prop);
		HashMap<State, Double> v = search.runAlgorithm();
		HashMap<State, Integer> d = search.d;
		int mostNeighbours = 0;

		for (State s : v.keySet()) {
			s = model.find(s);
			if (v.get(s) == 1)
				continue; /* Out of lambda */
			Neighbours nb = s.getNeighbours();
			State neighbours[] = nb.neighbours;
			if (neighbours.length == 0)
				continue;
			double ws[] = new double[neighbours.length];
			double exitRates[] = new double[neighbours.length];
			double ps[] = nb.probs;
			for(int i = 0; i < neighbours.length; i++) {
				ws[i] = v.get(neighbours[i]) * ps[i];
				exitRates[i] = neighbours[i].getNeighbours().exitRate;
				if (d.get(neighbours[i]) == 0)
					exitRates[i] = Double.POSITIVE_INFINITY;
			}
			if (neighbours.length > mostNeighbours)
				mostNeighbours = neighbours.length;
			StateInfo si = new StateInfo(nb, ws, exitRates);
			if (info.containsKey(s))
				throw new AssertionError("Duplicate");
			info.put(s, si);
		}
		return new SchemeZVAt(model, info, mostNeighbours);
	}

	public StateSpace.Neighbours prepareState(State state, double timeBound) {
		StateSpace.Neighbours ret = super.prepareState(state, timeBound);
		StateInfo si = info.get(state);
		if (si != null) {
			double[] weights = stateWeightsIS = stateWeightArray;
			if (ret != si.nbs) {
				System.err.println(state.getClass());
				throw new AssertionError("Different neighbours");
			}
			double sum = 0;
			if (weights.length < neighbours.length)
				throw new AssertionError("Length mismatch");
			double ws[] = si.weights;
			double eRates[] = si.exitRates;
			for (int i = 0; i < probs.length; i++) {
				weights[i] = ws[i];
				/* Approximate 1-exp(-eRates[i] * timeBound) */
				double approxNonTimeoutProb = eRates[i] * timeBound;
				if (timeBound < Double.POSITIVE_INFINITY && approxNonTimeoutProb < 0.1) {
					weights[i] *= eRates[i] * timeBound;
				}
				sum += weights[i];
			}
			totalStateWeightIS = sum;
		}
		return ret;
	}

	public int storedStates() {
		return info.size();
	}
}
