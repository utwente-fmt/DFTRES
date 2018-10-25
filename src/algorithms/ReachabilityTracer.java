package algorithms;

import nl.utwente.ewi.fmt.EXPRES.Property;
/** Tracer to estimate the probability of hitting a red state before
 * hitting either a blue state of the time bound.
 */
public class ReachabilityTracer extends TraceGenerator
{
	private long N;
	private long M;
	private double sum;
	private double sumSquares;
	private double estMean;
	private final double forceBound;
	private final double UNIF_BOUND = 1e-10;

	public ReachabilityTracer(Scheme s, Property prop, double forceBound)
	{
		super(s, prop);
		this.forceBound = forceBound;
	}

	public void reset()
	{
		super.reset();
		N = M = 0;
		sum = sumSquares = estMean = 0;
	}

	public void resetAndEstimateMeans()
	{
		double newEstMean = 0;
		if (N > 0)
			newEstMean = sum / N;
		super.resetAndEstimateMeans();
		estMean = newEstMean;
	}

	private double computeProb(int[] path)
	{
		double[] exitRates = generator.X.exitRates;
		double unifRate = Double.NEGATIVE_INFINITY;
		int totalCount = 0;
		for (int i = 0; i < path.length; i++) {
			if (path[i] > 0) {
				Double rate = exitRates[i];
				if (rate > unifRate)
					unifRate = rate;
				totalCount += path[i];
			}
		}
		double rates[] = new double[totalCount + 1];
		double probs[] = new double[totalCount + 1];
		probs[0] = 1;
		double undecProb = 1;
		double reachProb = 0;
		double prob = Math.exp(-unifRate * prop.timeBound);
		int k = 0;
		for (int i = 0; i < path.length; i++) {
			if (path[i] > 0) {
				double rate = exitRates[i] / unifRate;
				while (path[i] --> 0)
					rates[k++] = rate;
			}
		}
		k = 1;
		do {
			double nextProb = 0;
			double nextRate = 0;
			int i;
			undecProb -= prob;
			prob *= (unifRate * prop.timeBound) / k++;
			for (i = 0; i < rates.length; i++) {
				double tmp;
				tmp = probs[i];
				probs[i] = -Math.fma(probs[i], rates[i], -probs[i]);
				probs[i] = Math.fma(nextProb, nextRate, probs[i]);
				nextProb = tmp;
				nextRate = rates[i];
			}
			reachProb = Math.fma(probs[i - 1], prob, reachProb);
		} while (undecProb > UNIF_BOUND);
		return reachProb;
	}

	public void sample()
	{
		scheme.reset();
		int state = 0;
		double time = 0;
		double likelihood = 1;
		int path[] = null;
		if (forceBound < 0 && Double.isFinite(prop.timeBound))
			path = new int[generator.X.d.length];

		do {
			scheme.computeNewProbs(state);
			state = scheme.drawNextState();
			if (path != null) {
				path = scheme.extendPath(path);
			} else if (prop.timeBound < Double.POSITIVE_INFINITY) {
				time += scheme.drawDelta(prop.timeBound - time,
						likelihood > forceBound
							? forceBound
							: -1);
				likelihood *= scheme.deltaLikelihood();
			}
			likelihood *= scheme.likelihood();
		} while(!generator.isRed(state) && !generator.isBlue(state)
		        && time < prop.timeBound
		        && likelihood > 0);

		N++;
		if(time < prop.timeBound && generator.isRed(state)) {
			M++;
			double prob = 1;
			if (path != null)
				prob = computeProb(path);
			sum = Math.fma(prob, likelihood, sum);
			prob = Math.fma(prob, likelihood, -estMean);
			sumSquares = Math.fma(prob, prob, sumSquares);
		}
	}

	public SimulationResult getResult(double alpha)
	{
		long time = getElapsedTime();
		double mean = sum / N;
		double estSum = Math.fma(-N, estMean, sum);
		double var = Math.fma(-estSum, mean - estMean, sumSquares);
		var /= N - 1;

		return new SimulationResult(prop, alpha, mean, var, new long[]{N, M}, time, baseModelSize);
	}
}
