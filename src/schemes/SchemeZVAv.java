package schemes;

import algorithms.ModelGenerator;
import algorithms.Scheme;
import algorithms.SearchAlgorithm;
import java.util.Random;

// path-IS, assuming the generator contains a list of generated states with correct values for w

public class SchemeZVAv extends Scheme {
	private double[][] cachedWeightsIS;
	private double[] cachedWeightSums;
	
	public SchemeZVAv(Random rng, ModelGenerator gen) {
		super(rng, gen);
		this.name = "Path-ZVA-Delta";

		SearchAlgorithm s = new SearchAlgorithm(gen);
		double v[] = s.runAlgorithm();
		cachedWeightsIS = new double[v.length][];
		cachedWeightSums = new double[v.length];
		for (int state = 0; state < gen.X.successors.size(); state++) {
			int neighbours[] = gen.X.successors.get(state);
			boolean outOfLambda = false;
			if (neighbours == null)
				continue;
			if (v[state] == 1)
				outOfLambda = true;
			if (outOfLambda)
				continue;
			double probs[] = gen.X.probs.get(state);
			double sum = 0;
			cachedWeightsIS[state] = new double[probs.length];
			for(int i = 0; i < probs.length; i++) {
				double vi = v[neighbours[i]];
				cachedWeightsIS[state][i] = probs[i] * vi;
				sum = Math.fma(probs[i], vi, sum);
			}
			cachedWeightSums[state] = sum;
		}
	}

	public boolean isBinomial() {
		return false;
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
