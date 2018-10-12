package schemes;

import algorithms.ModelGenerator;
import algorithms.Scheme;
import java.util.Random;

// path-IS, assuming the generator contains a list of generated states with correct values for w

public class SchemeZVAv extends Scheme {
	
	private boolean haveLeftLambda;
	private double lastTimeBound;
	
	public SchemeZVAv(Random rng, ModelGenerator gen) {
		super(rng, gen);
		this.name = "Path-ZVA-Delta";
	}
	
	public void reset() {
		haveLeftLambda = false;
	}

	public void computeNewProbs() {
		initGlobalVariables();
		if(generator.X.v[generator.currentState] == 1)
			haveLeftLambda = true; // this seems to be an easy way to check whether gamma has been reached (or whether it doesn't matter anymore, because the probability of ending up in the goal state is 1 anyway).
		else if (haveLeftLambda) {
			haveLeftLambda = false;
			for (int neighbour : neighbours) {
				if (generator.X.v[neighbour] == 0) {
					haveLeftLambda = true;
					break;
				}
			}
		}
		if (generator.currentState == 0)
			haveLeftLambda = false;
		for(int i=0;i<probs.length;i++) {
			stateWeights[i] = probs[i];
			stateProbs[i] = probs[i];
			if(haveLeftLambda) {
				stateWeightsIS[i] = probs[i];
				totalStateWeightIS += stateWeightsIS[i];
			} else {
				stateWeightsIS[i] = probs[i]*generator.X.v[neighbours[i]];
				totalStateWeightIS += stateWeightsIS[i];
			}
		}
		for(int i=0;i<stateWeights.length;i++) {
			stateProbsIS[i] = stateWeightsIS[i]/totalStateWeightIS;
		}
	}
}
