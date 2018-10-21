package schemes;
import algorithms.ModelGenerator;
import algorithms.Scheme;
import java.util.Random;

// path-ZVA, assuming the generator contains a list of generated states with correct values for d

public class SchemeZVAd extends Scheme {
	
	private boolean haveLeftLambda;
	
	public SchemeZVAd(Random rng, ModelGenerator gen) {
		super(rng, gen);
		this.name = "Path-ZVA-d";
	}
	
	public void reset() {
		haveLeftLambda = false;
	}
	
	public void computeNewProbs() {
		initGlobalVariables();
		if(generator.X.v[generator.currentState] == 1) haveLeftLambda = true; // this seems to be an easy way to check whether gamma has been reached (or whether it doesn't matter anymore, because the probability of ending up in the goal state is 1 anyway).
		if (generator.currentState == 0)
			haveLeftLambda = false;
		totalStateWeightIS = 0;
		for(int i=0;i<probs.length;i++) {
			if(neighbours[i]> -1) {
				if(haveLeftLambda) {
					stateWeightsIS[i] = probs[i];
					totalStateWeightIS += stateWeightsIS[i];
				} else {
					stateWeightsIS[i] = probs[i]*Math.pow(generator.epsilon,1.*generator.X.d[neighbours[i]]);
					totalStateWeightIS += stateWeightsIS[i];
				}
			}
		}
	}
}
