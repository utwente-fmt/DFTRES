package schemes;

import algorithms.ModelGenerator;
import algorithms.Scheme;
import java.util.Random;

// path-IS, assuming the generator contains a list of generated states with correct values for w

public class SchemeZVAv extends Scheme {
	private double[][] cachedWeightsIS;
	private double[] cachedWeightSums;
	
	public SchemeZVAv(Random rng, ModelGenerator gen) {
		super(rng, gen);
		this.name = "Path-ZVA-Delta";
		cachedWeightsIS = new double[gen.X.v.length][];
		cachedWeightSums = new double[gen.X.v.length];
		for (int state = 0; state < gen.X.successors.size(); state++) {
			int neighbours[] = gen.X.successors.get(state);
			boolean outOfLambda = false;
			if (neighbours == null)
				continue;
			if (generator.X.v[state] == 1)
				outOfLambda = true;
			if (outOfLambda)
				continue;
			double probs[] = gen.X.probs.get(state);
			double sum = 0;
			cachedWeightsIS[state] = new double[probs.length];
			for(int i = 0; i < probs.length; i++) {
				double v = generator.X.v[neighbours[i]];
				cachedWeightsIS[state][i] = probs[i] * v;
				sum = Math.fma(probs[i], v, sum);
			}
			cachedWeightSums[state] = sum;
		}
	}

	public void computeNewProbs(int state) {
		super.computeNewProbs(state);
		if (state < cachedWeightsIS.length) {
			if (cachedWeightsIS[state] != null) {
				stateWeightsIS = cachedWeightsIS[state];
				totalStateWeightIS = cachedWeightSums[state];
			}
		}
	}
}
