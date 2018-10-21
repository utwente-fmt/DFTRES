package schemes;

import algorithms.ModelGenerator;
import algorithms.Scheme;
import java.util.Random;

// path-IS, assuming the generator contains a list of generated states with correct values for w

public class SchemeZVAv extends Scheme {
	
	private boolean haveLeftLambda;
	
	public SchemeZVAv(Random rng, ModelGenerator gen) {
		super(rng, gen);
		this.name = "Path-ZVA-Delta";
	}
	
	public void reset() {
		haveLeftLambda = false;
	}

	public void computeNewProbs() {
		initGlobalVariables();
		totalStateWeightIS = 0;
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
		if (haveLeftLambda) {
			System.arraycopy(probs, 0, stateWeightsIS, 0, probs.length);
			totalStateWeightIS = 1;
			return;
		}
		for(int i=0;i<probs.length;i++) {
			double v = generator.X.v[neighbours[i]];
			stateWeightsIS[i] = probs[i] * v;
			totalStateWeightIS = Math.fma(probs[i], v, totalStateWeightIS);
		}
	}
}
