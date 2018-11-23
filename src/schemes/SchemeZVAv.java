package schemes;

import algorithms.Scheme;
import algorithms.SearchAlgorithm;
import models.StateSpace;
import java.util.Random;
import nl.utwente.ewi.fmt.EXPRES.Property;

// path-IS, assuming the generator contains a list of generated states with correct values for w

public class SchemeZVAv extends Scheme {
	private final double[][] cachedWeightsIS;
	private final double[] cachedWeightSums;

	private SchemeZVAv(StateSpace model, double weights[][], double sums[])
	{
		super(model, "Path-ZVA-Delta");
		cachedWeightsIS = weights;
		cachedWeightSums = sums;
	}

	public SchemeZVAv clone()
	{
		return new SchemeZVAv(model, cachedWeightsIS, cachedWeightSums);
	}

	public static SchemeZVAv instantiate(StateSpace model, Property prop)
	{
		double v[] = new SearchAlgorithm(model, prop).runAlgorithm();
		double weights[][] = new double[v.length][];
		double sums[] = new double[v.length];
		for (int state = 0; state < model.successors.size(); state++) {
			int neighbours[] = model.successors.get(state);
			boolean outOfLambda = false;
			if (neighbours == null)
				continue;
			if (v[state] == 1)
				outOfLambda = true;
			if (outOfLambda)
				continue;
			double probs[] = model.probs.get(state);
			double sum = 0;
			weights[state] = new double[probs.length];
			for(int i = 0; i < probs.length; i++) {
				double vi = v[neighbours[i]];
				weights[state][i] = probs[i] * vi;
				sum = Math.fma(probs[i], vi, sum);
			}
			if (sum == 0) {
				weights[state] = null;
				continue;
			}
			sums[state] = sum;
		}
		return new SchemeZVAv(model, weights, sums);
	}

	public boolean isBinomial() {
		return false;
	}

	public void prepareState(int state) {
		super.prepareState(state);
		if (state < cachedWeightsIS.length) {
			if (cachedWeightsIS[state] != null) {
				stateWeightsIS = cachedWeightsIS[state];
				totalStateWeightIS = cachedWeightSums[state];
			}
		}
	}
}
