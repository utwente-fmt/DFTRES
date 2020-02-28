package schemes;

import algorithms.Scheme;
import algorithms.SearchAlgorithm;
import models.StateSpace;
import models.StateSpace.Neighbours;
import models.StateSpace.State;
import java.util.HashMap;
import java.util.Random;
import nl.utwente.ewi.fmt.EXPRES.Property;

// path-IS, assuming the generator contains a list of generated states with correct values for w

public class SchemeZVAv extends Scheme {
	private static class StateInfo {
		/* The Neighbours needs to be kept in here to prevent
		 * garbage collection of this data in the actual state,
		 * which could lead to re-ordering of the neighbours
		 * when it is recreated.
		 */
		public final Neighbours nbs;
		public final double[] weightsIS;
		public final double weightSum;
		public StateInfo(Neighbours nbs, double[] weights, double sum)
		{
			this.nbs = nbs;
			weightsIS = weights;
			weightSum = sum;
		}
	}
	private final HashMap<State, StateInfo> cachedInfo;

	private SchemeZVAv(StateSpace model, HashMap<State, StateInfo> info)
	{
		super(model, "Path-ZVA-v");
		cachedInfo = info;
	}

	public SchemeZVAv clone()
	{
		return new SchemeZVAv(model, cachedInfo);
	}

	public static SchemeZVAv instantiate(StateSpace model, Property prop)
	{
		HashMap<State, StateInfo> info = new HashMap<>();
		HashMap<State, Double> v;
		v = new SearchAlgorithm(model, prop).runAlgorithm();
		for (State s : v.keySet()) {
			s = model.find(s);
			Neighbours nb = s.getNeighbours();
			State neighbours[] = nb.neighbours;
			if (v.get(s) == 1)
				continue; /* Out of lambda */
			double probs[] = nb.probs;
			double sum = 0;
			double weights[] = new double[probs.length];
			if (weights.length != neighbours.length)
				throw new AssertionError("Length mismatch");
			for(int i = 0; i < probs.length; i++) {
				double vi = v.get(neighbours[i]);
				weights[i] = probs[i] * vi;
				sum += weights[i];
			}
			if (sum != 0) {
				StateInfo si = new StateInfo(nb, weights, sum);
				if (info.containsKey(s))
					throw new AssertionError("Duplicate");
				info.put(s, si);
			}
		}
		return new SchemeZVAv(model, info);
	}

	public boolean isBinomial() {
		return false;
	}

	public StateSpace.Neighbours prepareState(State state, double timeBound) {
		StateSpace.Neighbours ret = super.prepareState(state, timeBound);
		StateInfo si = cachedInfo.get(state);
		if (si != null) {
			if (ret != si.nbs) {
				System.err.println(state.getClass());
				throw new AssertionError("Different neighbours");
			}
			stateWeightsIS = si.weightsIS;
			if (si.weightsIS.length != ret.neighbours.length)
				throw new AssertionError(ret.neighbours.length + " neighbours but " + stateWeightsIS.length + " weights");
			totalStateWeightIS = si.weightSum;
		}
		return ret;
	}

	public int storedStates() {
		return cachedInfo.size();
	}
}
