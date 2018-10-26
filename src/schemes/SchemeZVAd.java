package schemes;
import algorithms.Scheme;
import algorithms.SearchAlgorithm;
import models.StateSpace;
import java.util.Random;

// path-ZVA, assuming the generator contains a list of generated states with correct values for d

public class SchemeZVAd extends Scheme {
	private double[] myStateWeights = new double[1];
	private boolean haveLeftLambda;
	private final double v[];
	private final int d[];
	
	private SchemeZVAd(StateSpace model, double v[], int d[]) {
		super(model, "Path-ZVA-d");
		this.v = v;
		this.d = d;
	}

	public SchemeZVAd clone()
	{
		return new SchemeZVAd(model.snapshot(), v, d);
	}

	public static SchemeZVAd instantiate(StateSpace model) {
		SearchAlgorithm s = new SearchAlgorithm(model);
		double v[] = s.runAlgorithm();
		int[] d = s.d;
		return new SchemeZVAd(model, v, d);
	}

	public boolean isBinomial() {
		return false;
	}

	public void reset() {
		haveLeftLambda = false;
	}
	
	public void prepareState(int state) {
		super.prepareState(state);
		if(state >= v.length || v[state] == 1) haveLeftLambda = true; // this seems to be an easy way to check whether gamma has been reached (or whether it doesn't matter anymore, because the probability of ending up in the goal state is 1 anyway).
		if (state == 0)
			haveLeftLambda = false;
		if (haveLeftLambda)
			return;
		if (myStateWeights.length < probs.length)
			myStateWeights = new double[probs.length];
		stateWeightsIS = myStateWeights;
		totalStateWeightIS = 0;
		for(int i=0;i<probs.length;i++) {
			if(neighbours[i]> -1) {
				stateWeightsIS[i] = probs[i]*Math.pow(model.epsilon, d[neighbours[i]]);
				totalStateWeightIS += stateWeightsIS[i];
			}
		}
	}
}
