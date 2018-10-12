package algorithms;

import java.util.Arrays;
import java.util.HashMap;

public class Simulator {
	public String trace;
	private static final boolean doTrace = false;
	private static final double UNIF_BOUND = 1e-10;
	
	public static final int MAX_CACHE = 1000;
	
	public double[] run(Scheme scheme) {
		ModelGenerator generator = scheme.generator;
		generator.currentState = 0;
		scheme.reset();
		generator.time = 0;
		int z = 0;
		double delta; double likelihood = 1;
		
		double[] result = new double[2];
		int d = 0;
		
		if (doTrace)
			trace = "init:/n"+generator.currentStateString()+"/n";
		
		while(!generator.isRed(generator.currentState) && !generator.isBlue(generator.currentState)) {
			scheme.computeNewProbs();
			z = scheme.drawNextState();
			d += generator.X.getOrder(generator.currentState, z); 
			delta = scheme.drawDelta(Double.POSITIVE_INFINITY, false);
			likelihood *= scheme.likelihood();
			generator.time += delta;
			generator.currentState = z;
			if (doTrace)
				trace += generator.currentState+"/n";
		}
		result[1] = d;
		result[0] = 0.;
		if(generator.isRed(generator.currentState)) {
			result[0] = likelihood;
			if (doTrace)
				trace += "HIT/n";
		}
		else if (doTrace)
			trace += "MISS/n";
		if (doTrace)
			trace += "likelihood: "+likelihood+"/n";
		return result;
	}

	private double computeProb(Scheme scheme, int[] path, double timeBound)
	{
		double[] exitRates = scheme.generator.X.exitRates;
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
		double prob = Math.exp(-unifRate * timeBound);
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
			double nextAdd = 0;
			int i;
			undecProb -= prob;
			prob *= (unifRate * timeBound) / k++;
			for (i = 0; i < rates.length; i++) {
				double tmp;
				tmp = probs[i] * rates[i];
				//probs[i] -= tmp;
				probs[i] = -Math.fma(probs[i], rates[i], -probs[i]);
				probs[i] += nextAdd;
				nextAdd = tmp;
			}
			reachProb = Math.fma(probs[i - 1], prob, reachProb);
		} while (undecProb > UNIF_BOUND);
		//} while ((undecProb / reachProb) > UNIF_BOUND);
		return reachProb;
	}

	public double[] runReliability(Scheme scheme, double timeBound) {
		ModelGenerator generator = scheme.generator;
		generator.currentState = 0;
		scheme.reset();
		generator.time = 0;
		int z = 0;
		//int path[] = new int[generator.X.d.length];
		double delta;
		double likelihood = 1;

		int d = 0;

		if (doTrace)
			trace = "initRel:/n"+generator.currentStateString()+"/n";

		while(!generator.isRed(generator.currentState) && generator.time < timeBound && likelihood > 0) {
			scheme.computeNewProbs();
			z = scheme.drawNextState();
			d += generator.X.getOrder(generator.currentState, z);
			delta = scheme.drawDelta(timeBound - generator.time, likelihood > 1e-50);
			//path = scheme.extendPath(path);
			likelihood *= scheme.likelihood() * scheme.deltaLikelihood();
			generator.time += delta;
			generator.currentState = z;
			if (doTrace)
				trace += generator.currentState+"/n";
		}
		double[] result = new double[2];
		result[1] = d;
		if(generator.time < timeBound) {
			result[0] = likelihood;
			//result[0] = computeProb(scheme, path, timeBound) * likelihood;
			if (doTrace)
				trace += "HIT/n";
		}
		else if (doTrace)
			trace += "MISS/n";

		if (doTrace)
			trace += "likelihood: "+likelihood+"/n";
		return result;
	}

	public double[] runRenewalCycle(Scheme scheme) {
		ModelGenerator generator = scheme.generator;
		generator.currentState = 0;
		scheme.reset();
		generator.time = 0;
		int z = 0;
		double delta; double likelihood = 1;
		double timeInRed = 0;
		
		double[] result = new double[2];
		int d = 0;

		if (doTrace)
			trace = "init:/n"+generator.currentStateString()+"/n";
		// leave the regenerative state
		
		while((!generator.isRed(generator.currentState) && !generator.isBlue(generator.currentState))) {
			scheme.computeNewProbs();
			z = scheme.drawNextState();
			d += generator.X.getOrder(generator.currentState, z); 
			delta = scheme.drawDelta(Double.POSITIVE_INFINITY, false);
			likelihood *= scheme.likelihood();
			generator.time += delta;
			generator.currentState = z;
			if (doTrace)
				trace += generator.currentState+"/n";
			//System.err.println("State " + generator.currentStateString());
		}
		if(generator.isRed(generator.currentState)) {
			if (doTrace)
				trace += "switch to standard MC/n";
			// turn on standard Monte Carlo
			Scheme schemeMC = new schemes.SchemeMC(scheme.rng, generator);
			// system failure has been reached - now complete the busy cycle 
			while(!generator.isBlue(generator.currentState)) {
				schemeMC.computeNewProbs();
				z = schemeMC.drawNextState();
				d += generator.X.getOrder(generator.currentState, z); 
				delta = schemeMC.drawDelta(Double.POSITIVE_INFINITY, false);
				if(generator.isRed(generator.currentState)) timeInRed += delta;
				generator.time += delta;
				generator.currentState = z;
				if (doTrace)
					trace += generator.currentState+"/n";
			}
		}
		
		result[1] = d;
		result[0] = timeInRed * likelihood;
		if (doTrace) {
			if(generator.isRed(generator.currentState))
				trace += "HIT/n";
			else
				trace += "MISS/n";
			trace += "Z: "+timeInRed+", likelihood: "+likelihood+"/n";
		}
		return result;
	}
	
	public double runRenewalCycleDuration(Scheme scheme) {
		ModelGenerator generator = scheme.generator;
		generator.currentState = 0;
		generator.time = 0;
		int z = 0;
		double delta;
		while(!generator.isBlue(generator.currentState)) {
			scheme.computeNewProbs();
			z = scheme.drawNextState();
			delta = scheme.drawDelta(Double.POSITIVE_INFINITY, false);
			generator.time += delta;
			generator.currentState = z;
		}
		return generator.time;
	}
	
	public SimulationResult sim(int msec, Scheme scheme) {
		long N=0;
		long M=0;
		long start = System.currentTimeMillis();
		double[] stats = new double[4]; double[] Y;
		ModelGenerator generator = scheme.generator;
		System.out.println("simulation running ...");
		System.out.println("start size: "+generator.X.size());
		StateSpace resX = generator.X.clone();
		int initSize = Math.max(generator.X.size(), MAX_CACHE);
		while(System.currentTimeMillis()-start < msec) {
			if(generator.X.size() > 2 * initSize) {generator.X = resX.clone();} // keeps the cache from exploding
			Y = this.run(scheme);
			if(((int) Y[1] > scheme.generator.X.d[0])) {
				M++; 
				stats[2] += Y[0]; 
				stats[3] += Math.fma(Y[0], Y[0], stats[3]);
			}
			stats[0] += Y[0];
			stats[1] = Math.fma(Y[0], Y[0], stats[1]);
			N++;
		}
		System.out.println("end size: "+generator.X.size());
		SimulationResult result;
		if(generator.XUnderQ == null) result = new SimulationResult(stats, new double[] {generator.X.v[0], 0}, N, M);
		else result = new SimulationResult(stats, new double[] {generator.X.v[0], generator.XUnderQ.v[0]}, N, M);
		
		System.out.println(result);
		System.out.println("run time: "+(System.currentTimeMillis() - start)+" milliseconds, N="+N+", M = "+M);
		System.out.println("--------------------------------");
		return result;
	}

	public SimulationResult simReliability(int msec, Scheme scheme, int maxN, double timeBound) {
		long N=0;
		long M=0;
		int percentage = 0;
		long start = System.currentTimeMillis();
		double[] stats = new double[4]; double[] Y;
		double estMean = 0;
		ModelGenerator generator = scheme.generator;
		System.out.println("simulation running ...");
		System.out.println("start size: "+generator.X.size());
		StateSpace resX = generator.X.clone();
		int initSize = Math.max(generator.X.size(), MAX_CACHE);
		if (msec < Integer.MAX_VALUE) {
			long newMaxN = 0;
			int estN = 0;
			int trialSimTime = msec / 100; /* Spend 1% of simulation time to estimate number of runs. */
			while (System.currentTimeMillis() - start < trialSimTime) {
				if(generator.X.size() > 2 * initSize)
					generator.X = resX.clone(); // keeps the cache from exploding
				Y = runReliability(scheme, timeBound);
				stats[0] += Y[0];
				newMaxN++;
			}
			/* Spend about another 1% estimating the time
			 * after caching and without constant time
			 * measurements. */
			long startExact = System.nanoTime();
			while (estN < newMaxN) {
				if(generator.X.size() > 2 * initSize)
					generator.X = resX.clone(); // keeps the cache from exploding
				Y = runReliability(scheme, timeBound);
				stats[0] += Y[0];
				estN++;
			}
			msec -= (System.currentTimeMillis() - start);
			newMaxN = 1000 * ((msec * 1000 * newMaxN) / (System.nanoTime() - startExact));
			estMean = stats[0] / (2*newMaxN);
			if (newMaxN < maxN)
				maxN = (int)newMaxN;
		}
		System.out.format("Will run %d simulations.\n", maxN);
		System.out.flush();
		stats[0] = 0;
		long startExact = System.nanoTime();
		while(N < maxN) {
			if(generator.X.size() > 2 * initSize) {generator.X = resX.clone();} // keeps the cache from exploding
			Y = this.runReliability(scheme, timeBound);
			if (((int) Y[1] > scheme.generator.X.d[0])) {
				M++;
				stats[2] += Y[0];
				stats[3] = Math.fma(Y[0], Y[0], stats[3]);
			}
			stats[0] += Y[0];
			Y[0] -= estMean;
			stats[1] = Math.fma(Y[0], Y[0], stats[1]);
			N++;
			if (maxN < Integer.MAX_VALUE) {
				int newPerc = (int)(N * 50 / maxN);
				if (newPerc != percentage) {
					percentage = newPerc;
					if (newPerc % 5 == 0)
						System.out.print(newPerc * 2 + "%");
					else
						System.out.print(".");
					System.out.flush();
					percentage = newPerc;
				}
			}
		}
		long endExact = System.nanoTime();
		System.out.println("\nend size: "+generator.X.size());
		SimulationResult result;
		/*
		if(generator.XUnderQ == null) result = new SimulationResult(stats, new double[] {generator.X.v[0], 0}, N, M);
		else result = new SimulationResult(stats, new double[] {generator.X.v[0], generator.XUnderQ.v[0]}, N, M);
		*/
		double mean = stats[0] / N;
		double var = Math.fma(-N, estMean, stats[0]);
		var = Math.fma(-var, mean - estMean, stats[1]);
		var /= N - 1;
		result = new SimulationResult(mean, var, N);
		result.M = M;

		System.out.println(result);
		System.out.println("run time: "+ (1e-9 * (endExact - startExact))+" seconds, N="+N+", M = "+M);
		System.out.println("Simulations per second: " + 1e9 * N / (endExact - startExact));
		System.out.println("--------------------------------");
		return result;
	}

	public SimulationResult simFixN(long Nm, Scheme scheme) {
		long start = System.currentTimeMillis();
		double[] stats = new double[4]; double[] Y;
		long N=0;
		long M=0;
		System.out.println("simulation running ...");
		ModelGenerator generator = scheme.generator;
		StateSpace resX = generator.X.clone();
		int initSize = Math.max(generator.X.size(), MAX_CACHE);
		while(N<Nm) {
			if(generator.X.size() > 2 * initSize) {generator.X = resX.clone();} // keeps the cache from exploding
			Y = this.run(scheme);
			if(((int) Y[1] > scheme.generator.X.d[0])) {
				M++; 
				stats[2] += Y[0]; 
				stats[3] = Math.fma(Y[0], Y[0], stats[3]);
			}
			stats[0] += Y[0];
			stats[1] = Math.fma(Y[0], Y[0], stats[1]);
			N++;
		}

		SimulationResult result;
		if(generator.XUnderQ == null) result = new SimulationResult(stats, new double[] {generator.X.v[0], 0}, N, M);
		else result = new SimulationResult(stats, new double[] {generator.X.v[0], generator.XUnderQ.v[0]}, N, M);
		
		System.out.println(result);
		System.out.println("run time: "+(System.currentTimeMillis() - start)+" milliseconds, N="+N+", M = "+M);
		System.out.println("--------------------------------");
		return result;
	}
	
	public SimulationResult simUnavailability(int msec, Scheme scheme1, Scheme scheme2) {
		long N=0;
		long start = System.currentTimeMillis();
		double[] Z; 
		double estMeans[] = new double[2];
		System.out.println("Estimating simulation rate...");
		int initSize = Math.max(scheme1.generator.X.size(), MAX_CACHE);
		initSize = Math.max(scheme2.generator.X.size(), initSize);
		StateSpace resX1 = scheme1.generator.X.clone();
		StateSpace resX2 = scheme2.generator.X.clone();

		long newMaxN = 0;
		int trialSimTime = msec / 100; /* Spend 1% of simulation time to estimate number of runs. */
		while (System.currentTimeMillis() - start < trialSimTime) {
			// keep the cache from exploding
			if(scheme1.generator.X.size() > 2 * initSize)
				scheme1.generator.X = resX1.clone();
			if(scheme2.generator.X.size() > 2 * initSize)
				scheme2.generator.X = resX2.clone();
			
			Z = this.runRenewalCycle(scheme1);
			estMeans[0] += Z[0];
			
			double D = runRenewalCycleDuration(scheme2);
			estMeans[1] += D;
			newMaxN++;
		}

		/* Spend about another 1% estimating the time
		 * after caching and without constant time
		 * measurements. */
		long startExact = System.nanoTime();
		for (N = 0; N < newMaxN; N++) {
			// keep the cache from exploding
			if(scheme1.generator.X.size() > 2 * initSize)
				scheme1.generator.X = resX1.clone();
			if(scheme2.generator.X.size() > 2 * initSize)
				scheme2.generator.X = resX2.clone();
			
			Z = this.runRenewalCycle(scheme1);
			estMeans[0] += Z[0];
			
			double D = runRenewalCycleDuration(scheme2);
			estMeans[1] += D;
		}
		estMeans[0] /= newMaxN * 2;
		estMeans[1] /= newMaxN * 2;

		msec -= System.currentTimeMillis() - start;
		newMaxN = 1000 * ((msec * 1000) * newMaxN / (System.nanoTime() - startExact));
		System.out.format("Will run %d simulations.\n", newMaxN);
		System.out.flush();
		return simUnavailabilityFixN((int)newMaxN, scheme1, scheme2, estMeans);
	}
	
	public SimulationResult simUnavailabilityFixN(int Nm, Scheme scheme1, Scheme scheme2) {
		return simUnavailabilityFixN(Nm, scheme1, scheme2, new double[2]);
	}

	public SimulationResult simUnavailabilityFixN(int Nm, Scheme scheme1, Scheme scheme2, double estMeans[]) {
		long N=0, M = 0;
		double[] zStats = new double[2]; 
		double[] dStats = new double[2];
		double[] Z; 
		System.out.println("simulation running ...");
		long start = System.nanoTime();
		int initSize = Math.max(scheme1.generator.X.size(), MAX_CACHE);
		initSize = Math.max(scheme2.generator.X.size(), initSize);
		StateSpace resX1 = scheme1.generator.X.clone();
		StateSpace resX2 = scheme2.generator.X.clone();
		while(N < Nm) {
			// keep the cache from exploding
			if(scheme1.generator.X.size() > 2 * initSize)
				scheme1.generator.X = resX1.clone();
			if(scheme2.generator.X.size() > 2 * initSize)
				scheme2.generator.X = resX2.clone();
			
			Z = this.runRenewalCycle(scheme1);
			zStats[0] += Z[0]; 
			if (Z[0] > 0)
				M++;
			Z[0] -= estMeans[0];
			zStats[1] = Math.fma(Z[0], Z[0], zStats[1]);

			double D = runRenewalCycleDuration(scheme2);
			dStats[0] += D;
			D -= estMeans[1];
			dStats[1] = Math.fma(D, D, dStats[1]);
			N++;
		}
		
		System.out.println("estimator for Z (unavail. time during renewal cycle): "+(zStats[0]/N));
		System.out.println("estimator for D (total time during renewal cycle): "+dStats[0]/N);
		
		double meanZ = zStats[0]/N; 
		double meanT = dStats[0]/N;
		double varZ = Math.fma(N, -estMeans[0], zStats[0]);
		varZ = Math.fma(-varZ, meanZ - estMeans[0], zStats[1]);
		double varT = Math.fma(N, -estMeans[1], dStats[0]);
		varT = Math.fma(-varT, meanT - estMeans[1], dStats[1]);

		double meanV = zStats[0]/dStats[0];
		double varV = N*Math.fma(meanV, meanV*varT, varZ)*N;
		varV /= (N-1)*dStats[0]*dStats[0];
		
		SimulationResult res = new SimulationResult(meanV, varV, N);
		System.out.println(res);
		System.out.println("run time: "+(System.nanoTime() - start)/1e9+" seconds, N="+N + ", M="+M);
//		System.out.println("run time: "+(System.currentTimeMillis() - start)+" milliseconds, N="+N);
//		System.out.println("--------------------------------");

		return res;
	}
}
