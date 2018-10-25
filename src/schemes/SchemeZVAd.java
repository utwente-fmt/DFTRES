package schemes;
import algorithms.ModelGenerator;
import algorithms.Scheme;
import java.util.Random;

// path-ZVA, assuming the generator contains a list of generated states with correct values for d

public class SchemeZVAd extends Scheme {
	private double[] myStateWeights = new double[1];
	private boolean haveLeftLambda;
	
	public SchemeZVAd(Random rng, ModelGenerator gen) {
		super(rng, gen);
		this.name = "Path-ZVA-d";
	}
	
	public void reset() {
		haveLeftLambda = false;
	}
	
	public void computeNewProbs(int state) {
		super.computeNewProbs(state);
		if(generator.X.v[state] == 1) haveLeftLambda = true; // this seems to be an easy way to check whether gamma has been reached (or whether it doesn't matter anymore, because the probability of ending up in the goal state is 1 anyway).
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
				stateWeightsIS[i] = probs[i]*Math.pow(generator.epsilon,1.*generator.X.d[neighbours[i]]);
				totalStateWeightIS += stateWeightsIS[i];
			}
		}
	}
}
