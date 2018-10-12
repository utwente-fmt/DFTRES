package schemes;

import algorithms.ModelGenerator;
import algorithms.Scheme;
import java.util.Random;

// naive Monte Carlo scheme

public class SchemeMC extends Scheme {
	
	public SchemeMC(Random rng, ModelGenerator gen) {
		super(rng, gen);
		name = "standard Monte Carlo";
	}
	
	public void computeNewProbs() {
		// transition probabilities depend solely on their respective rates
		initGlobalVariables();
		for(int i=0;i<probs.length;i++) {
			stateWeights[i] = probs[i];
			stateProbs[i] = probs[i];
			stateWeightsIS[i] = probs[i];
			stateProbsIS[i] = probs[i];
		}
	}
}
