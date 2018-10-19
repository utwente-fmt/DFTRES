import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Random;
import schemes.SchemeMC;
import schemes.SchemeZVAd;
import schemes.SchemeZVAv;
import algorithms.MainAlgorithm;
import algorithms.ModelGenerator;
import algorithms.Scheme;
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

	private static Long getMaximalMemory()
	{
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/self/status"))))
		{
			String line;
			while ((line = r.readLine()) != null) {
				if (line.matches("VmHWM:.*"))
					return Long.valueOf(line.split(" +")[1]) * 1024;
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	private static Long getSystemMemory()
	{
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo"))))
		{
			String line;
			while ((line = r.readLine()) != null) {
				if (line.matches("MemTotal:.*"))
					return (long)Math.round(Double.valueOf(line.split(" +")[1]) / (1024 * 1024));
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	private static String getCPUName()
	{
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/cpuinfo"))))
		{
			String line;
			while ((line = r.readLine()) != null) {
				if (line.matches("model name.*")) {
					String tmp = line.split("\\s+", 4)[3];
					tmp = tmp.split(" CPU ")[0];
					tmp = tmp.replace("(R)", "");
					tmp = tmp.replace("(TM)", "");
					return tmp;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "CPU";
		}
		return "CPU";
	}

	/* Call with reliabilityTime == -1 for availability. */
	private static ArrayList<SimulationResult> runSimulations(double reliabilityTime) throws IOException
	{
		boolean multiple = false;
		ArrayList<SimulationResult> ret = new ArrayList<>();
		ModelGenerator generator = new ExpModel(epsilon, model, reliabilityTime > -1);
		generator.initialise();
		if ((mc ? 1 : 0) + (zvav ? 1 : 0) + (zvad ? 1 : 0) > 1)
			multiple = true;

		SchemeMC schemeMC2 = null;
		if (reliabilityTime == -1) {
			ModelGenerator gen2 = new ExpModel(epsilon, model, false);
			gen2.initialise();
			schemeMC2 = new SchemeMC(rng, gen2);
		}

		Simulator simulator = new Simulator();

		if (mc) {
			SimulationResult res;
			SchemeMC schemeMC = new SchemeMC(rng,generator);
			if (reliabilityTime == -1) {
				if (maxSims < Integer.MAX_VALUE)
					res = simulator.simUnavailabilityFixN(maxSims, schemeMC, schemeMC2);
				else
					res = simulator.simUnavailability(maxTime, schemeMC, schemeMC2);
			} else
				res = simulator.simReliability(maxTime, schemeMC, maxSims, reliabilityTime);
			if (multiple)
				res.property += "MC";
			ret.add(res);
		}

		if (zvad || zvav) {
			MainAlgorithm algorithm = new MainAlgorithm(generator, false);

			algorithm.forwardPhase();
			algorithm.backwardPhase();
		}

		if (zvad) {
			SimulationResult res;
			SchemeZVAd schemeZVAd = new SchemeZVAd(rng,generator);
			if (reliabilityTime == -1) {
				if (maxSims < Integer.MAX_VALUE)
					res = simulator.simUnavailabilityFixN(maxSims, schemeZVAd, schemeMC2);
				else
					res = simulator.simUnavailability(maxTime, schemeZVAd, schemeMC2);
			} else {
				res = simulator.simReliability(maxTime, schemeZVAd, maxSims, reliabilityTime);
			}
			if (multiple)
				res.property += "-ZVAd";
			ret.add(res);
		}

		if (zvav) {
			SimulationResult res;
			SchemeZVAv schemeZVAv = new SchemeZVAv(rng,generator);
			if (reliabilityTime == -1) {
				if (maxSims < Integer.MAX_VALUE)
					res = simulator.simUnavailabilityFixN(maxSims, schemeZVAv, schemeMC2);
				else
					res = simulator.simUnavailability(maxTime, schemeZVAv, schemeMC2);
			} else {
				res = simulator.simReliability(maxTime, schemeZVAv, maxSims, reliabilityTime);
			}
			if (multiple)
				res.property += "-ZVAv";
			ret.add(res);
		}
		return ret;
	}

	private static void benchmarkHeader(String[] args, String model)
	{
		DateTimeFormatter form = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		LocalDateTime now = LocalDateTime.now();
		System.out.println("{");
		System.out.println("\t\"file\": \"../" + model + "\",");
		System.out.println("\t\"version\": \"VERSION\",");
		System.out.println("\t\"date\": \"" + form.format(now) + "\",");
		System.out.println("\t\"submitter\": \"SUBMITTER\",");
		System.out.println("\t\"file-parameter-values\": [");
		System.out.println("\t\t\"VALUES\"");
		System.out.println("\t],");
		System.out.println("\t\"tool\": {");
		System.out.println("\t\t\"name\": \"DFTRES\",");
		System.out.println("\t\t\"version\": \"" + Version.version + "\",");
		System.out.println("\t\t\"url\": \"https://github.com/utwente-fmt/DFTRES\"");
		System.out.println("\t},");
		System.out.println("\t\"system\": {");
		System.out.println("\t\t\"cpu\": \"" + getCPUName() + "\"");
		System.out.print("\t\t\"ram\": ");
		Long ram = getSystemMemory();
		if (ram != null)
			System.out.println(ram + ",");
		else
			System.out.println("\"RAM\",");
		System.out.println("\t\t\"os\": \"" + System.getProperties().getProperty("os.name") + " " + System.getProperties().getProperty("os.version") + "\",");
		System.out.println("\t\t\"runtime\": \"" + System.getProperties().getProperty("java.vm.name") + " " + System.getProperties().getProperty("java.vm.version") + "\"");
		System.out.println("\t},");
		System.out.println("\t\"command\": \"java -jar DFTRES.jar " + String.join(" ", Arrays.asList(args)) + "\",");
	}

	private static void benchmarkPostSim(long timeNanos, ArrayList<SimulationResult> results)
	{
		System.out.println("\t\"time\": " + Double.toString(Math.round(timeNanos / 1000000.0) / 1000.0) + ",");
		System.out.println("\t\"memory\": " + getMaximalMemory() + ",");
		System.out.println("\t\"property-times\": [");
		for (int i = 0; i < results.size(); i++) {
			SimulationResult res = results.get(i);
			System.out.format("\t\t{ \"name\": \"%s\", \"time\": %s }%s\n", res.property, Double.toString(Math.round(res.simTimeNanos / 1000000) / 1000.0), (i == results.size() - 1) ? "" : "," );
		}
		System.out.println("\t],");
		System.out.println("\t\"data\": [");
		if (results.size() > 0) {
			System.out.println("\t\t{");
			System.out.println("\t\t\t\"group\": \"Importance sampling precomputation\",");
			System.out.println("\t\t\t\"value\": [");
			System.out.println("\t\t\t\t{ \"name\": \"Stored states\", \"value\": " + results.get(0).storedStates + " }");
			System.out.println("\t\t\t]");
			System.out.println("\t\t},");
		}
		for (int i = 0; i < results.size(); i++) {
			SimulationResult res = results.get(i);
			System.out.println("\t\t{");
			System.out.println("\t\t\t\"property\": \"" + res.property + "\",");
			System.out.println("\t\t\t\"value\": " + res.mean + ",");
			System.out.println("\t\t\t\"values\": [");
			System.out.println("\t\t\t\t{ \"name\": \"Time\", \"value\": " + Math.round(res.simTimeNanos / 1000000.0) / 1000.0 + ", \"unit\": \"s\" },");
			System.out.println("\t\t\t\t{ \"name\": \"Number of simulation traces\", \"value\": " + res.N + "},");
			System.out.println("\t\t\t\t{ \"name\": \"Number of traces that hit goal states\", \"value\": " + res.M + "},");
			System.out.println("\t\t\t\t{ \"name\": \"95% Confidence interval\", \"value\": \"" + res.getExplicitCI() + "\" }");
			System.out.println("\t\t\t]");
			if (i < results.size() - 1)
				System.out.println("\t\t},");
			else
				System.out.println("\t\t}");
		}
		System.out.println("\t]");
		System.out.println("}");
	}

	public static void main(String args[]) throws java.io.IOException
	{
		long startTime = System.nanoTime();
		double reliabilityTime = -1;
		boolean availability = false;
		long seed = 0;
		boolean haveSeed = false;
		ArrayList<SimulationResult> results = new ArrayList<>();
		String useRng = "XS128";
		model = args[args.length - 1];
		benchmarkHeader(args, model);

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
			results.addAll(runSimulations(-1));
		}
		if (reliabilityTime > -1) {
			results.addAll(runSimulations(reliabilityTime));
		}
		long time = System.nanoTime() - startTime;
		benchmarkPostSim(time, results);
	}
}
