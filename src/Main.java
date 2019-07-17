import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import schemes.SchemeUniform;
import schemes.SchemeZVAd;
import schemes.SchemeZVAv;
import algorithms.Scheme;
import algorithms.SimulationResult;
import algorithms.Simulator;

import algorithms.TraceGenerator;

import ec.util.MersenneTwisterFast;
import nl.ennoruijters.interval.XoroShiro128RandomSource;
import nl.utwente.ewi.fmt.EXPRES.Automaton;
import nl.utwente.ewi.fmt.EXPRES.MarkedAutomaton;
import nl.utwente.ewi.fmt.EXPRES.MarkovReducedLTS;
import nl.utwente.ewi.fmt.EXPRES.MarkovianComposition;
import nl.utwente.ewi.fmt.EXPRES.NondeterminismException;
import nl.utwente.ewi.fmt.EXPRES.Composition;
import nl.utwente.ewi.fmt.EXPRES.LTS;
import nl.utwente.ewi.fmt.EXPRES.MakeJani;
import nl.utwente.ewi.fmt.EXPRES.MakeTraLab;
import nl.utwente.ewi.fmt.EXPRES.Property;
import nl.utwente.ewi.fmt.EXPRES.Version;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.VariableExpression;

import models.ExpModel;

class Main {
	static Random rng;
	static int maxTime = 0;
	static long maxSims = 0;
	static double epsilon = 0.01;
	static double confidence = 0.95;
	static double relErr = Double.NaN;
	static Double forceBound = null;
	static boolean mc = false, zvad = false, zvav = false, unif = false;
	static boolean jsonOutput = false;
	static boolean unsafeComposition = false;
	static LTS model;
	static TreeSet<Property> properties = new TreeSet<>();

	private static Long getMaximalMemory()
	{
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/self/status"))))
		{
			String line;
			while ((line = r.readLine()) != null) {
				if (line.matches("VmHWM:.*")) {
					String digits = line.replaceAll("[^0123456789]", "");
					return Long.valueOf(digits) * 1024;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
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
		double force;
		if (forceBound != null) {
			force = forceBound;
		} else {
			if (prop.type == Property.Type.STEADY_STATE)
				force = Double.POSITIVE_INFINITY;
			else
				force = 0;
		}
		Simulator simulator = new Simulator(rng, prop, s, force);
		SimulationResult res;
		if (!Double.isNaN(relErr)) {
			if (maxSims > 0)
				System.err.println("Warning: Simulating up to relative error, ignoring simulation bound.");
			if (maxTime > 0)
				System.err.println("Warning: Simulating up to relative error, ignoring time limit.");
			res = simulator.simRelErr(relErr, 1-confidence, maxSims);
		} else {
			res = simulator.sim(maxTime, maxSims, 1-confidence);
		}
		return res;
	}

	private static List<SimulationResult> runSimulations(Property prop)
			throws IOException
	{
		boolean multiple = false;
		ExpModel statespace = new ExpModel(epsilon, model);
		if (!(mc || zvav || zvad || unif)) {
			Scheme s;
			if (prop.type == Property.Type.EXPECTED_VALUE
			    && prop.timeBound == Double.POSITIVE_INFINITY)
			{
				s = new Scheme(statespace);
			} else {
				s = SchemeZVAv.instantiate(statespace, prop);
			}
			SimulationResult res = runSim(prop, s);
			return List.of(res);
		}
		if ((mc ? 1 : 0) + (zvav ? 1 : 0) + (zvad ? 1 : 0) + (unif ? 1 : 0) > 1)
			multiple = true;

		ArrayList<SimulationResult> ret = new ArrayList<>();
		if (mc) {
			Scheme mc = new Scheme(statespace);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-MC");
			SimulationResult res = runSim(nProp, mc);
			ret.add(res);
		}

		if (unif) {
			Scheme s = new SchemeUniform(statespace);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-Unif");
			SimulationResult res = runSim(nProp, s);
			ret.add(res);
		}

		if (zvad) {
			SchemeZVAd sc = SchemeZVAd.instantiate(statespace, prop);
			if (prop.type == Property.Type.EXPECTED_VALUE) {
				System.err.println("WARNING: Importance sampling and expected value queries often give misleading results.");
			}
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-ZVAd");
			SimulationResult res = runSim(nProp, sc);
			ret.add(res);
		}

		if (zvav) {
			if (prop.type == Property.Type.EXPECTED_VALUE) {
				System.err.println("WARNING: Importance sampling and expected value queries often give misleading results.");
			}
			SchemeZVAv sc = SchemeZVAv.instantiate(statespace, prop);
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
		String version = System.getenv("MODEL_VERSION");
		if (version == null)
			version = "\"VERSION\"";
		String submitter = System.getenv("SUBMITTER");
		if (submitter == null)
			submitter = "SUBMITTER";
		System.out.println("{");
		System.out.println("\t\"file\": \"../" + model + "\",");
		System.out.println("\t\"version\": " + version + ",");
		System.out.println("\t\"date\": \"" + form.format(now) + "\",");
		System.out.println("\t\"submitter\": \"" + submitter + "\",");
		System.out.println("\t\"file-parameter-values\": [");
		Map<String, Object> params = MakeJani.getFileVars();
		if (params.isEmpty()) {
			System.out.println("\t\t\"VALUES\"");
		} else {
			int i = 0;
			for (String name : params.keySet()) {
				System.out.print("\t\t{ \"name\": \"" + name + "\", \"value\": " + params.get(name) + " }");
				if (++i != params.size())
					System.out.print(",");
				System.out.println();
			}
		}
		System.out.println("\t],");
		System.out.println("\t\"tool\": {");
		System.out.println("\t\t\"name\": \"DFTRES\",");
		System.out.println("\t\t\"version\": \"" + Version.version + "\",");
		System.out.println("\t\t\"url\": \"https://github.com/utwente-fmt/DFTRES\"");
		System.out.println("\t},");
		System.out.println("\t\"system\": {");
		System.out.println("\t\t\"cpu\": \"" + getCPUName() + "\",");
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

	private static void benchmarkPostSim(long timeNanos, ArrayList<SimulationResult> results, long seed)
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
			System.out.println("\t\t\t\"group\": \"Simulator\",");
			System.out.println("\t\t\t\"values\": [");
			System.out.println("\t\t\t\t{ \"name\": \"RNG Seed\", \"value\": " + seed + "},");
			System.out.println("\t\t\t\t{ \"name\": \"CPU cores used\", \"value\": " + Simulator.coresToUse + "}");
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
			System.out.println("\t\t\t\t{ \"name\": \"Number of states stored by importance sampling\", \"value\": " + res.storedStates + " }");
			System.out.println("\t\t\t\t{ \"name\": \"Number of simulation traces\", \"value\": " + res.N + "},");
			System.out.println("\t\t\t\t{ \"name\": \"Number of traces that hit goal states\", \"value\": " + res.M + "},");
			System.out.println("\t\t\t\t{ \"name\": \"Relative error\", \"value\": " + res.getRelErr() + "},");
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

	private static LTS loadModel(String filename,
	                             Map<String, Number> constants,
				     boolean doDontCareElimination)
			throws IOException
	{
		LTS ret;
		if (filename.endsWith(".exp")) {
			Composition c;
			c = new Composition(filename, "exp", properties, null);
			c.markStatesAfter("FAIL", 1);
			c.markStatesAfter("REPAIR", 0);
			c.markStatesAfter("ONLINE", 0);
			if (doDontCareElimination)
				c.addDontCares();
			ret = null;
			while (ret != c) {
				ret = c;
				c = c.partialCompose(256);
			}
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
			ret = new Composition(filename, "jani", properties, constants);
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
			return loadModel("output/" + basename + ".exp", null, doDontCareElimination);
		} else {
			throw new IllegalArgumentException("Type of file " + filename + " unknown");
		}
		return ret;
	}

	public static void main(String args[]) throws Exception
	{
		long startTime = System.nanoTime();
		long seed = 0;
		boolean haveSeed = false;
		boolean doDontCareElimination = true;
		TreeMap<String, Number> constants = new TreeMap<>();
		ArrayList<SimulationResult> results = new ArrayList<>();
		TreeSet<String> onlyProperties = new TreeSet<>();
		String useRng = "XS128";
		if (args.length == 1 && args[0].equals("--version")) {
			System.out.println("Version: " + Version.version);
			System.exit(0);
		}
		if (args.length == 0) {
			System.err.println("No filename provided.");
			System.exit(-1);
		}
		String filename = args[args.length - 1];
		String janiOutputFile = null, traLabOutputFile = null;

		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals("-a")) {
				Property av = new Property(Property.Type.STEADY_STATE, new VariableExpression("marked"), "Unavailability");
				properties.add(av);
				onlyProperties.add(av.name);
			} else if (args[i].equals("-u")) {
				Property rel = new Property(Property.Type.REACHABILITY, new VariableExpression("marked"), "Unreliability");
				properties.add(rel);
				onlyProperties.add(rel.name);
			} else if (args[i].equals("-r")) {
				double time = Double.parseDouble(args[++i]);
				Property rel = new Property(Property.Type.REACHABILITY, time, new VariableExpression("marked"), "Unreliability");
				properties.add(rel);
				onlyProperties.add(rel.name);
			} else if (args[i].equals("--mttf")) {
				Property mttf = new Property(Property.Type.EXPECTED_VALUE, Double.POSITIVE_INFINITY, new VariableExpression("marked"), "MTTF", new ConstantExpression(1), null);
				properties.add(mttf);
				onlyProperties.add(mttf.name);
			} else if (args[i].equals("-s")) {
				seed = Long.parseLong(args[++i]);
				haveSeed = true;
			} else if (args[i].equals("--rng")) {
				useRng = args[++i];
			} else if (args[i].equals("--no-dc")) {
				doDontCareElimination = false;
			} else if (args[i].equals("-t"))
				maxTime = Integer.parseInt(args[++i]) * 1000;
			else if (args[i].equals("-n"))
				maxSims = Long.parseLong(args[++i]);
			else if (args[i].equals("-e"))
				epsilon = Double.parseDouble(args[++i]);
			else if (args[i].equals("-f"))
				forceBound = Double.valueOf(args[++i]);
			else if (args[i].equals("-p"))
				Simulator.coresToUse = Integer.parseInt(args[++i]);
			else if (args[i].equals("--progress"))
				Simulator.showProgress = true;
			else if (args[i].equals("--acc"))
				TraceGenerator.acceleration = Double.parseDouble(args[++i]);
			else if (args[i].equals("--relErr"))
				relErr = Double.parseDouble(args[++i]);
			else if (args[i].equals("--mc"))
				mc = true;
			else if (args[i].equals("--zvad"))
				zvad = true;
			else if (args[i].equals("--unif"))
				unif = true;
			else if (args[i].equals("--zvav"))
				zvav = true;
			else if (args[i].equals("--def")) {
				Number v = null;
				String name = args[++i];
				++i;
				if (args[i].equalsIgnoreCase("false"))
					v = Long.valueOf(0);
				else if (args[i].equalsIgnoreCase("true"))
					v = Long.valueOf(1);
				try {
					v = Long.valueOf(args[i]);
				} catch (NumberFormatException e) {
					try {
						v = Double.valueOf(args[i]);
					} catch (NumberFormatException e2) {
					}
				}
				if (v == null) {
					System.err.println("Unable to parse value: " + args[i]);
					System.exit(-1);
				}
				constants.put(name, v);
			} else if (args[i].equals("--prop"))
				onlyProperties.add(args[++i]);
			else if (args[i].equals("--json"))
				jsonOutput = true;
			else if (args[i].equals("--export-jani"))
				janiOutputFile = args[++i];
			else if (args[i].equals("--export-tralab"))
				traLabOutputFile = args[++i];
			else if (args[i].equals("--unsafe-scheduling"))
				unsafeComposition = true;
			else if (args[i].equals("--no-forcing"))
				forceBound = Double.POSITIVE_INFINITY;
			else
				System.err.format("Unknown option '%s', ignoring\n", args[i]);
		}
		if (!haveSeed)
			seed = new SecureRandom().nextLong();
		if (useRng.equalsIgnoreCase("xs128")) {
			rng = new XoroShiro128RandomSource(seed);
		} else if (useRng.equalsIgnoreCase("mt199937")) {
			rng = new MersenneTwisterFast(seed);
		}

		model = loadModel(filename, constants, doDontCareElimination);
		if (janiOutputFile != null)
			MakeJani.makeJani(model, janiOutputFile, jsonOutput ? filename : null, args, properties);
		if (traLabOutputFile != null) {
			try {
				MakeTraLab mtl = new MakeTraLab(model, unsafeComposition);
				mtl.convert(traLabOutputFile);
			} catch (NondeterminismException e) {
				e.printStackTrace();
				LTS tmpModel = loadModel(filename, constants, false);
				MakeTraLab mtl = new MakeTraLab(tmpModel, unsafeComposition);
				mtl.convert(traLabOutputFile);
			}
		}
		if (model instanceof Composition && unsafeComposition) {
			model = new MarkovianComposition((Composition)model);
		} else {
			model = new MarkovReducedLTS(model);
		}
		if (Double.isNaN(relErr)
		    && maxTime == 0
		    && maxSims == 0)
		{
			System.err.println("No bounds on simulation specified, not performing simulations.");
			return;
		}
		if (jsonOutput && !properties.isEmpty())
			benchmarkHeader(args, filename);
		for (Property prop : properties) {
			if (!onlyProperties.isEmpty() && !onlyProperties.contains(prop.name))
				continue;
			try {
				results.addAll(runSimulations(prop));
			} catch (UnsupportedOperationException e2) {
				System.err.println(prop.name + ": " + e2.getMessage());
			}
		}
		long time = System.nanoTime() - startTime;
		if (jsonOutput && !properties.isEmpty())
			benchmarkPostSim(time, results, seed);
		else
			showResults(time, results);
	}
}
