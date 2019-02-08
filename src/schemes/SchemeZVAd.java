package schemes;
import algorithms.Scheme;
import algorithms.SearchAlgorithm;
import models.StateSpace;
import models.StateSpace.State;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import nl.utwente.ewi.fmt.EXPRES.Property;

// path-ZVA, assuming the generator contains a list of generated states with correct values for d

public class SchemeZVAd extends Scheme {
	private static class StateInfo {
		public final double v;
		public final int d;
		public StateInfo(double v, int d) {
			this.v = v;
			this.d = d;
		}
	};
	private double[] myStateWeights = new double[1];
	private boolean haveLeftLambda;
	private final HashMap<State, StateInfo> info;
	
	private SchemeZVAd(StateSpace model, HashMap<State, StateInfo> info) {
		super(model, "Path-ZVA-d");
		this.info = info;
	}

	public SchemeZVAd clone()
	{
		return new SchemeZVAd(model, info);
	}

	public static SchemeZVAd instantiate(StateSpace model, Property prop) {
		SearchAlgorithm s = new SearchAlgorithm(model, prop);
		HashMap<State, Double> v = s.runAlgorithm();
		HashMap<State, StateInfo> sinfo = new HashMap<>();
		for (Map.Entry<State, Double> e : v.entrySet()) {
			StateInfo si = new StateInfo(e.getValue(), s.d.get(e.getKey()));
			sinfo.put(e.getKey(), si);
		}
		return new SchemeZVAd(model, sinfo);
	}

	public boolean isBinomial() {
		return false;
	}

	public void reset() {
		haveLeftLambda = false;
	}
	
	public StateSpace.Neighbours prepareState(State state) {
		StateSpace.Neighbours ret = super.prepareState(state);
		StateInfo sinfo = info.get(state);
		if (sinfo == null || sinfo.v == 1)
			haveLeftLambda = true;
		if (haveLeftLambda) {
			if (state.equals(model.getInitialState()))
				haveLeftLambda = false;
			else
				return ret;
		}
		if (myStateWeights.length < probs.length)
			myStateWeights = new double[probs.length];
		stateWeightsIS = myStateWeights;
		totalStateWeightIS = 0;
		for(int i=0;i<probs.length;i++) {
			stateWeightsIS[i] = probs[i]*Math.pow(model.epsilon, info.get(neighbours[i]).d);
			totalStateWeightIS += stateWeightsIS[i];
		}
		return ret;
	}

	public int storedStates() {
		return info.size();
	}
}
