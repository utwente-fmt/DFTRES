import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
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
import nl.utwente.ewi.fmt.EXPRES.Automaton;
import nl.utwente.ewi.fmt.EXPRES.MarkedAutomaton;
import nl.utwente.ewi.fmt.EXPRES.Composition;
import nl.utwente.ewi.fmt.EXPRES.LTS;
import nl.utwente.ewi.fmt.EXPRES.MakeJani;
import nl.utwente.ewi.fmt.EXPRES.MakeTraLab;
import nl.utwente.ewi.fmt.EXPRES.Property;
import nl.utwente.ewi.fmt.EXPRES.Version;

import models.ExpModel;

class Main {
	static Random rng;
	static int maxTime = Integer.MAX_VALUE, maxSims = Integer.MAX_VALUE;
	static double epsilon = 0.01;
	static double confidence = 0.95;
	static double relErr = Double.NaN;
	static double forceBound = Double.POSITIVE_INFINITY;
	static boolean mc = false, zvad = false, zvav = false;
	static boolean jsonOutput = false;
	static LTS model;
	static HashSet<Property> properties = new HashSet<>();

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
					tmp = tmp.replace("CPU ", "");
					tmp = tmp.replace("(R)", "");
					tmp = tmp.replace("(TM)", "");
					tmp = tmp.replaceAll(" @.*", "");
					return tmp;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "CPU";
		}
		return "CPU";
	}

	private static SimulationResult runSim(Property prop, Scheme s)
	{
		Simulator simulator = new Simulator(prop, s, forceBound);
		SimulationResult res;
		if (!Double.isNaN(relErr)) {
			if (maxSims < Integer.MAX_VALUE)
				System.err.println("Warning: Simulating up to relative error, ignoring simulation bound.");
			if (maxTime < Integer.MAX_VALUE)
				System.err.println("Warning: Simulating up to relative error, ignoring time limit.");
			res = simulator.simRelErr(relErr, 1-confidence);
		} else {
			res = simulator.sim(maxTime, maxSims, 1-confidence);
		}
		return res;
	}

	private static ArrayList<SimulationResult> runSimulations(Property prop)
			throws IOException
	{
		boolean multiple = false;
		ArrayList<SimulationResult> ret = new ArrayList<>();
		ModelGenerator generator = new ExpModel(epsilon, model, prop);
		generator.initialise();
		if ((mc ? 1 : 0) + (zvav ? 1 : 0) + (zvad ? 1 : 0) > 1)
			multiple = true;

		if (mc) {
			SchemeMC mc = new SchemeMC(rng,generator);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-MC");
			SimulationResult res = runSim(nProp, mc);
			ret.add(res);
		}

		if (zvad || zvav) {
			MainAlgorithm algorithm = new MainAlgorithm(generator, false);

			algorithm.forwardPhase();
			algorithm.backwardPhase();
		}

		if (zvad) {
			SchemeZVAd sc = new SchemeZVAd(rng,generator);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-ZVAd");
			SimulationResult res = runSim(nProp, sc);
			ret.add(res);
		}

		if (zvav) {
			SchemeZVAv sc = new SchemeZVAv(rng,generator);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-ZVAv");
			SimulationResult res = runSim(nProp, sc);
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
			System.out.format("\t\t{ \"name\": \"%s\", \"time\": %s }%s\n", res.property.name, Double.toString(Math.round(res.simTimeNanos / 1000000) / 1000.0), (i == results.size() - 1) ? "" : "," );
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
			System.out.println("\t\t\t\"property\": \"" + res.property.name + "\",");
			System.out.println("\t\t\t\"value\": " + res.mean + ",");
			System.out.println("\t\t\t\"values\": [");
			System.out.println("\t\t\t\t{ \"name\": \"Time\", \"value\": " + Math.round(res.simTimeNanos / 1000000.0) / 1000.0 + ", \"unit\": \"s\" },");
			System.out.println("\t\t\t\t{ \"name\": \"Number of simulation traces\", \"value\": " + res.N + "},");
			System.out.println("\t\t\t\t{ \"name\": \"Number of traces that hit goal states\", \"value\": " + res.M + "},");
			System.out.println("\t\t\t\t{ \"name\": \"95% Confidence interval\", \"value\": \"[" + res.getCI() + "]\" }");
			System.out.println("\t\t\t]");
			if (i < results.size() - 1)
				System.out.println("\t\t},");
			else
				System.out.println("\t\t}");
		}
		System.out.println("\t]");
		System.out.println("}");
	}

	private static void showResults(long timeNanos, ArrayList<SimulationResult> results)
	{
		System.out.println("Total time: " + Double.toString(Math.round(timeNanos / 1000000.0) / 1000.0) + " s.");
		for (int i = 0; i < results.size(); i++) {
			SimulationResult res = results.get(i);
			System.out.println("Property " + res.property.name + ":");
			System.out.println(res.toString());
		}
	}

	private static LTS loadModel(String filename) throws IOException
	{
		LTS ret;
		if (filename.endsWith(".exp")) {
			Composition c;
			c = new Composition(filename, "exp", properties);
			c.markStatesAfter("FAIL", 1);
			c.markStatesAfter("REPAIR", 0);
			c.markStatesAfter("ONLINE", 0);
			ret = c;
		} else if (filename.endsWith(".aut")
		           || filename.endsWith(".bcg"))
		{
			String type = filename.substring(filename.length() - 3);
			Automaton a = new Automaton(filename, type);
			MarkedAutomaton m = new MarkedAutomaton(a);
			m.markStatesAfter("FAIL", 1);
			m.markStatesAfter("REPAIR", 0);
			m.markStatesAfter("ONLINE", 0);
			ret = m;
		} else if (filename.endsWith(".jani")) {
			ret = new Composition(filename, "jani", properties);
		} else if (filename.endsWith(".dft")) {
			String[] cmd = new String[]{"dftcalc", "-x", filename};
			Process dftc = Runtime.getRuntime().exec(cmd);
			int dret = 0;
			boolean done = false;
			while (!done) {
				try {
					dret = dftc.waitFor();
					done = true;
				} catch (InterruptedException e) {
				}
			}
			if (dret != 0)
				throw new IOException("Error executing DFTCalc.");
			String basename = filename;
			if (basename.lastIndexOf('/') != -1)
				basename = basename.substring(basename.lastIndexOf('/') + 1, basename.length());
			basename = basename.substring(0, basename.length() - 4);
			return loadModel("output/" + basename + ".exp");
		} else {
			throw new IllegalArgumentException("Type of file " + filename + " unknown");
		}
		return ret;
	}

	public static void main(String args[]) throws IOException
	{
		long startTime = System.nanoTime();
		long seed = 0;
		boolean haveSeed = false;
		ArrayList<SimulationResult> results = new ArrayList<>();
		String useRng = "XS128";
		String filename = args[args.length - 1];
		String janiOutputFile = null, traLabOutputFile = null;

		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals("-a")) {
				Property av = new Property(Property.Type.STEADY_STATE, null, "Unavailability");
				properties.add(av);
			} else if (args[i].equals("-r")) {
				double time = Double.parseDouble(args[++i]);
				Property rel = new Property(Property.Type.REACHABILITY, time, null, "Unreliability");
				properties.add(rel);
			} else if (args[i].equals("-s")) {
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
			else if (args[i].equals("-f"))
				forceBound = Double.parseDouble(args[++i]);
			else if (args[i].equals("--acc"))
				Scheme.gamma = Double.parseDouble(args[++i]);
			else if (args[i].equals("--relErr"))
				relErr = Double.parseDouble(args[++i]);
			else if (args[i].equals("--mc"))
				mc = true;
			else if (args[i].equals("--zvad"))
				zvad = true;
			else if (args[i].equals("--zvav"))
				zvav = true;
			else if (args[i].equals("--json"))
				jsonOutput = true;
			else if (args[i].equals("--export-jani"))
				janiOutputFile = args[++i];
			else if (args[i].equals("--export-tralab"))
				traLabOutputFile = args[++i];
			else
				System.err.format("Unknown option '%s', ignoring\n", args[i]);
		}
		if (!(mc || zvav || zvad))
			zvav = true;
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

		model = loadModel(filename);
		if (jsonOutput && !properties.isEmpty())
			benchmarkHeader(args, filename);
		if (janiOutputFile != null)
			MakeJani.makeJani(model, janiOutputFile, jsonOutput ? filename : null, args);
		if (traLabOutputFile != null) {
			MakeTraLab mtl = new MakeTraLab(model);
			mtl.convert(traLabOutputFile);
		}
		for (Property prop : properties) {
			try {
				results.addAll(runSimulations(prop));
			} catch (UnsupportedOperationException e2) {
				System.err.println(prop.name + ": " + e2.getMessage());
			}
		}
		long time = System.nanoTime() - startTime;
		if (jsonOutput && !properties.isEmpty())
			benchmarkPostSim(time, results);
		else
			showResults(time, results);
	}
}