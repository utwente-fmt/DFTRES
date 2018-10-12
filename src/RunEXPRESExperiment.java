import java.util.Arrays;

import java.io.IOException;
import java.util.Random;
import schemes.SchemeMC;
import schemes.SchemeZVAd;
import schemes.SchemeZVAv;
import algorithms.MainAlgorithm;
import algorithms.ModelGenerator;
import algorithms.SimulationResult;
import algorithms.Simulator;

import ec.util.MersenneTwisterFast;
import nl.ennoruijters.interval.XoroShiro128RandomSource;

import models.ExpModel;

class RunEXPRESExperiment {
	static Random rng;
	static int maxTime = Integer.MAX_VALUE, maxSims = Integer.MAX_VALUE;
	static double epsilon = 0.01;
	static boolean mc = false, zvad = false, zvav = false;
	static String model;

	/* Call with reliabilityTime == -1 for availability. */
	private static void runSimulations(double reliabilityTime) throws IOException
	{
		ModelGenerator generator = new ExpModel(epsilon, model, reliabilityTime > -1);
		generator.initialise();

		SchemeMC schemeMC2 = null;
		if (reliabilityTime == -1) {
			ModelGenerator gen2 = new ExpModel(epsilon, model, false);
			gen2.initialise();
			schemeMC2 = new SchemeMC(rng, gen2);
		}

		Simulator simulator = new Simulator();

		if (mc) {
			System.out.println("MC:");
			SchemeMC schemeMC = new SchemeMC(rng,generator);
			if (reliabilityTime == -1) {
				if (maxSims < Integer.MAX_VALUE)
					simulator.simUnavailabilityFixN(maxSims, schemeMC, schemeMC2);
				else
					simulator.simUnavailability(maxTime, schemeMC, schemeMC2);
			} else
				simulator.simReliability(maxTime, schemeMC, maxSims, reliabilityTime);
		}

		if (zvad || zvav) {
			MainAlgorithm algorithm = new MainAlgorithm(generator, false);

			algorithm.forwardPhase();
			algorithm.backwardPhase();
			System.out.println("Size of Lambda: "+algorithm.Lambda.size());
			System.out.println("Size of Gamma: "+algorithm.Gamma.size());
		}

		if (zvad) {
			System.out.println("ZVAd:");
			SchemeZVAd schemeZVAd = new SchemeZVAd(rng,generator);
			if (reliabilityTime == -1) {
				if (maxSims < Integer.MAX_VALUE)
					simulator.simUnavailabilityFixN(maxSims, schemeZVAd, schemeMC2);
				else
					simulator.simUnavailability(maxTime, schemeZVAd, schemeMC2);
			} else
				simulator.simReliability(maxTime, schemeZVAd, maxSims, reliabilityTime);
		}

		if (zvav) {
			System.out.println("ZVAv:");
			SchemeZVAv schemeZVAv = new SchemeZVAv(rng,generator);
			if (reliabilityTime == -1) {
				if (maxSims < Integer.MAX_VALUE)
					simulator.simUnavailabilityFixN(maxSims, schemeZVAv, schemeMC2);
				else
					simulator.simUnavailability(maxTime, schemeZVAv, schemeMC2);
			} else
				simulator.simReliability(maxTime, schemeZVAv, maxSims, reliabilityTime);
		}
	}

	public static void main(String args[]) throws java.io.IOException
	{
		double reliabilityTime = -1;
		boolean availability = false;
		long seed = 0;
		boolean haveSeed = false;
		String useRng = "XS128";
		model = args[args.length - 1];

		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals("-a"))
				availability = true;
			else if (args[i].equals("-s")) {
				seed = Long.parseLong(args[++i]);
				haveSeed = true;
			} else if (args[i].equals("--rng")) {
				useRng = args[++i];
			} else if (args[i].equals("-t"))
				maxTime = Integer.parseInt(args[++i]) * 1000;
			else if (args[i].equals("-n"))
				maxSims = Integer.parseInt(args[++i]);
			else if (args[i].equals("-e"))
				epsilon = Double.parseDouble(args[++i]);
			else if (args[i].equals("-r"))
				reliabilityTime = Double.parseDouble(args[++i]);
			else if (args[i].equals("--mc"))
				mc = true;
			else if (args[i].equals("--zvad"))
				zvad = true;
			else if (args[i].equals("--zvav"))
				zvav = true;
			else
				System.err.format("Unknown option '%s', ignoring\n", args[i]);
		}
		if (useRng.equalsIgnoreCase("xs128")) {
			if (haveSeed)
				rng = new XoroShiro128RandomSource(seed);
			else
				rng = new XoroShiro128RandomSource();
		} else if (useRng.equalsIgnoreCase("mt199937")) {
			if (haveSeed)
				rng = new MersenneTwisterFast(seed);
			else
				rng = new MersenneTwisterFast();
		}
		if (availability) {
			System.out.println("Long-run average unavailability:");
			runSimulations(-1);
		}
		if (reliabilityTime > -1) {
			if (availability)
				System.err.println();
			System.out.println("Unreliability before time " + reliabilityTime + ":");
			runSimulations(reliabilityTime);
		}
	}
}
