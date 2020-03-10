import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import nl.utwente.ewi.fmt.EXPRES.*;
import schemes.SchemeUniform;
import schemes.SchemeZVAd;
import schemes.SchemeZVAv;
import schemes.SchemeZVAt;
import algorithms.Scheme;
import algorithms.SimulationResult;
import algorithms.Simulator;
import algorithms.TraceGenerator;

import ec.util.MersenneTwisterFast;
import nl.ennoruijters.interval.XoroShiro128RandomSource;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.VariableExpression;

import models.ExpModel;

class Main {
	static int maxTime = 0;
	static long maxSims = 0;
	static double epsilon = 0.01;
	static double confidence = 0.95;
	static double relErr = Double.NaN;
	static Double forceBound = null;
	static boolean mc = false, zvad = false, zvav = false, unif = false;
	static boolean zvat = false;
	static boolean jsonOutput = false;
	static boolean unsafeComposition = false;
	static boolean unsafeSimulation = false;
	private static Random rng;
	private static LTS model;
	private static TreeSet<Property> properties = new TreeSet<>();
	private static Simulator simulator;
	private static ArrayList<SimulationResult> lastResults;

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
					return Math.round(Double.valueOf(line.split(" +")[1]) / (1024 * 1024));
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

	/**
	 * Run simulations to estimate the value of a {@code Property}.
     * A simulator of the specified IS {@code Scheme} is used,
	 * deleting any pre-existent {@code simulator}.
	 * @return Simulation result, including point estimate and CI
	 */
	private static SimulationResult runSim(Property prop, Scheme s)
	{
		final double alpha = 1-confidence;
		double force;
		if (forceBound != null) {
			force = forceBound;
		} else {
			if (prop.type == Property.Type.STEADY_STATE)
				force = Double.POSITIVE_INFINITY;
			else
				force = 0;
		}
		simulator = unsafeSimulation
				? new Simulator(rng, prop, s, force, 0)
				: new Simulator(rng, prop, s, force);
		if (!Double.isNaN(relErr)) {
			if (maxSims > 0)
				System.err.println("Warning: Simulating up to relative error, ignoring simulation bound.");
			if (maxTime > 0)
				System.err.println("Warning: Simulating up to relative error, ignoring time limit.");
			return simulator.simRelErr(relErr, alpha, maxSims);
		} else {
			return simulator.sim(maxTime, maxSims, alpha);
		}
//		assert(simulator.pregnant());
//		return simulator.bear(alpha);  // removes results from inside simulator
	}

	/**
	 * Run new simulations to estimate the value of a {@code Property}.
	 * Any {@code lastResults} are reset; this field will contain the results
	 * of the new simulations upon successful exit.
	 * @param prop
	 * @throws IOException
	 */
	private static void runSimulations(Property prop) throws IOException
	{
		lastResults = new ArrayList<>();
		ExpModel statespace = new ExpModel(epsilon, model);
		boolean multiple = ((mc ? 1 : 0) + (zvav ? 1 : 0)
		                + (zvad ? 1 : 0) + (unif ? 1 : 0) > 1);
		if (!(mc || zvav || zvad || zvat || unif)) {
			Scheme s;
			if (prop.type == Property.Type.EXPECTED_VALUE
			    && prop.timeBound == Double.POSITIVE_INFINITY)
			{
				s = new Scheme(statespace);
			} else {
				s = SchemeZVAv.instantiate(statespace, prop);
			}
			lastResults.add(runSim(prop,s));
		}

		if (mc) {
			Scheme mc = new Scheme(statespace);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-MC");
			lastResults.add(runSim(nProp, mc));
		}

		if (unif) {
			Scheme s = new SchemeUniform(statespace);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-Unif");
			lastResults.add(runSim(nProp, s));
		}

		if (zvad) {
			SchemeZVAd sc = SchemeZVAd.instantiate(statespace, prop);
			if (prop.type == Property.Type.EXPECTED_VALUE)
				System.err.println("WARNING: Importance Sampling and expected value queries often give misleading results.");
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-ZVAd");
			lastResults.add(runSim(nProp, sc));
		}

		if (zvav) {
			if (prop.type == Property.Type.EXPECTED_VALUE)
				System.err.println("WARNING: Importance Sampling and expected value queries often give misleading results.");
			SchemeZVAv sc = SchemeZVAv.instantiate(statespace, prop);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-ZVAv");
			lastResults.add(runSim(nProp, sc));
		}

		if (zvat) {
			if (prop.type == Property.Type.EXPECTED_VALUE)
				System.err.println("WARNING: Importance Sampling and expected value queries often give misleading results.");
			SchemeZVAt sc = SchemeZVAt.instantiate(statespace, prop);
			Property nProp = prop;
			if (multiple)
				nProp = new Property(prop, prop.name + "-ZVAt");
			lastResults.add(runSim(nProp, sc));
		}
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

	private static void benchmarkPostSim(long timeNanos, long RNGseed)
	{
		System.out.println("\t\"time\": " + Double.toString(Math.round(timeNanos / 1000000.0) / 1000.0) + ",");
		System.out.println("\t\"memory\": " + getMaximalMemory() + ",");
		System.out.println("\t\"property-times\": [");
		for (int i = 0; i < lastResults.size(); i++) {
			SimulationResult res = lastResults.get(i);
			System.out.format("\t\t{ \"name\": \"%s\", \"time\": %s }%s\n", res.property.name, Double.toString(Math.round(res.simTimeNanos / 1000000) / 1000.0), (i == lastResults.size() - 1) ? "" : "," );
		}
		System.out.println("\t],");
		System.out.println("\t\"data\": [");
		if (lastResults.size() > 0) {
			System.out.println("\t\t{");
			System.out.println("\t\t\t\"group\": \"Simulator\",");
			System.out.println("\t\t\t\"values\": [");
			System.out.println("\t\t\t\t{ \"name\": \"RNG Seed\", \"value\": " + RNGseed + "},");
			System.out.println("\t\t\t\t{ \"name\": \"CPU cores used\", \"value\": " + Simulator.coresToUse + "}");
			System.out.println("\t\t\t]");
			System.out.println("\t\t},");
		}
		for (int i = 0; i < lastResults.size(); i++) {
			SimulationResult res = lastResults.get(i);
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
			if (i < lastResults.size() - 1)
				System.out.println("\t\t},");
			else
				System.out.println("\t\t}");
		}
		System.out.println("\t]");
		System.out.println("}");
	}

	private static LTS loadModel(String filename,
								 Map<String, Number> constants,
								 boolean doDontCareElimination,
								 int compLimit)
			throws IOException
	{
		long maxMem = Runtime.getRuntime().maxMemory();
		maxMem = 3 * maxMem / 4;
		LTS ret;
		if (filename.endsWith(".exp")) {
			Composition c;
			c = new Composition(filename, "exp");
			c.markStatesAfter("FAIL", 1);
			c.markStatesAfter("REPAIR", 0);
			c.markStatesAfter("ONLINE", 0);
			if (doDontCareElimination)
				c.addDontCares();
			if (compLimit != 0) {
				ret = null;
				while (ret != c) {
					ret = c;
					c = c.partialCompose(compLimit, maxMem);
				}
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
			JaniModel model = new JaniModel(filename, constants);
			properties.addAll(model.getProperties());
			LTS l = model.getLTS();
			if ((l instanceof Composition) && compLimit != 0) {
				Composition c = (Composition)l;
				ret = null;
				while (ret != c) {
					ret = c;
					c = c.partialCompose(compLimit, maxMem);
				}
			}
			ret = l;
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
			return loadModel("output/" + basename + ".exp", null, doDontCareElimination, compLimit);
		} else {
			throw new IllegalArgumentException("Type of file " + filename + " unknown");
		}
		return ret;
	}

	private static void usage(PrintStream out) {
		String[][] options = new String[][] {
			{"General options:"},
			{"-h", "--help", "Show this message and exit."},
			{"-p N", "Use N threads in parallel."},
			{"--progress", "Show progress during simulations."},
			{"--version", "Show the program version and exit."},
			{"Available queries:"},
			{"-a", "Compute system unavailability."},
			{"-r T", "Compute unreliability up to time T."},
			{"--mttf", "Compute mean time to failure."},
			{"--prop P", "(For JANI models): compute the property named P."},
			{"-u", "Compute time-unbounded unreliability."},
			{"Simulation options:"},
			{"-e E", "Simulate until an absolute of E has been reached."},
			{"--relErr E", "Simulate until a relative of E has been reached."},
			{"--fixed-batches", "Use fixed-size simulation batches (may bias the result!)"},
			{"-n N", "Simulate exactly N runs."},
			{"--rng <rng>", "Set the RNG type, available choices are:"},
			{"",            "  \"XS128\":   Xoroshiro-128"},
			{"",            "  \"mt19937\": Mersenne Twister"},
			{"-s N", "Set initial seed for the RNG."},
			{"-t T", "Simulate for approximately T seconds."},
			{"Rare event simulation options:"},
			{"--acc F", "Accelerate transitions by the constant factor F."},
			{"--mc", "Using standard Monte Carlo to choose transition probabilities."},
			{"--unif", "Transform outgoing transitions to uniform probabilities."},
			{"--zvad", "Using ZVA-d to choose transition probabilities."},
			{"--zvav", "Using ZVA-v to choose transition probabilities."},
			{"--zvat", "Using ZVA-t to choose transition probabilities."},
			{"-f F", "Stop time-forcing when the importance factor drops below F."},
			{"--no-forcing", "Do not apply time-forcing."},
			{"--no-hpc-boost", "Do not boost HPC sink transitions (only affects time-bounded reachability"},
			{"Model (optimization) options:"},
			{"--compose full", "Compute the full parallel composition explicitly."},
			{"--compose none", "Do not explicitly compute any parallel composition."},
			{"--compose N", "Explicitly compute parallel compositions of automata as"},
			{"",            "long as the resulting automata as guaranteed smaller"},
			{"",            "than N states each (default: 256)."},
			{"--def P V", "(for JANI models): Define constant P to value V."},
			{"--no-dc", "Do not perform \"don't care\" optimizations."},
			/* Undocumented option: --unsafe-scheduling */
			{"Output options:"},
			{"--json", "Format the output in JSON format following the QComp"},
			{"",       "2018 schema."},
			{"--export-jani F", "Export the model in JANI format to a file named F."},
			{"--export-tralab F", "Export the model in .tra/.lab format to files named"},
			{"",                  "F.tra and F.lab"}
		};
		int len = 0;
		for (String[] option : options) {
			if (option.length < 2)
				continue;
			int thisLen = 1;
			for (int i = 0; i < option.length - 1; i++) {
				thisLen += option[i].length();
				if (i != 0)
					thisLen += 2; /* For the ", " */
			}
			if (thisLen > len)
				len = thisLen;
		}
		out.println("Usage: DFTRES [options] <model.file>");
		out.println("Available options:");
		for (String[] option : options) {
			int spaces = len, i = 0;
			if (option.length == 1) {
				out.println();
				out.println(option[0]);
				continue;
			}
			for (i = 0; i < option.length - 1; i++) {
				if (i != 0) {
					spaces -= 2;
					out.print(", ");
				}
				out.print(option[i]);
				spaces -= option[i].length();
			}
			while (spaces --> 0)
				out.print(' ');
			System.err.println(option[i]);
		}
		System.exit(out == System.err ? -1 : 0);
	}

	/**
	 * Bookkeeping of last simulations & results, followed by results dumping
	 * @param startTimeNanos  System.nanoTime() value on main() start
	 * @param RNGseed         Seed fed to the RNG for simulations
	 */
	private static void terminate(long startTimeNanos,
								  long RNGseed)
	{
		// Are there any unregistered simulation results?
		if (simulator.pregnant()) {
			if (null == lastResults)
				lastResults = new ArrayList<>();
			lastResults.add(simulator.bear(1 - confidence));
		}
		// Dump all results
		if (null != lastResults && !lastResults.isEmpty()) {
			final long time = System.nanoTime() - startTimeNanos;
			if (jsonOutput && !properties.isEmpty()) {
				// JSON output for benchmark
				benchmarkPostSim(time, RNGseed);
			} else {
				// Shell output
				System.out.println("Total time: " + Double.toString(Math.round(time/1000000.0) / 1000.0) + " s.");
				for (int i = 0; i < lastResults.size(); i++) {
					SimulationResult res = lastResults.get(i);
					System.out.println("Property " + res.property.name + ":");
					System.out.println(res.toString());
				}
			}
		}
	}

	public static void main(String args[]) throws Exception
	{
		long startTime = System.nanoTime();
		long seed = 0;
		int compositionStateLimit = 256;
		boolean haveSeed = false;
		boolean doDontCareElimination = true;
		TreeMap<String, Number> constants = new TreeMap<>();
		ArrayList<SimulationResult> results = new ArrayList<>();
		TreeSet<String> onlyProperties = new TreeSet<>();
		String useRng = "XS128";

		// Erroneous or help/version invocation
		if (args.length == 0)
			usage(System.err);
		else if (args.length == 1) {
			if (Arrays.asList("-h", "--help").contains(args[0]))
				usage(System.out);
			else if (args[0].equals("--version")) {
				System.out.println("Version: " + Version.version);
				System.exit(0);
			} else {
				System.err.println("Invalid single arguemnt \"" + args[0] + "\"");
				usage(System.err);
			}
		}

		String filename = args[args.length - 1];
		String janiOutputFile = null, traLabOutputFile = null;

		// TODO: factor this CLI parsing out of the main() method
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
			else if (args[i].equals("--zvat"))
				zvat = true;
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
			else if (args[i].equals("--compose")) {
				int limit = 0;
				i++;
				if (args[i].equalsIgnoreCase("full"))
					limit = -1;
				else if (args[i].equalsIgnoreCase("none"))
					limit = 0;
				else
					limit = Integer.parseInt(args[i]);
				compositionStateLimit = limit;
			}
			else if (args[i].equals("--fixed-batches"))
				unsafeSimulation = true;
			else if (args[i].equals("--unsafe-scheduling"))
				unsafeComposition = true;
			else if (args[i].equals("--no-forcing"))
				forceBound = Double.POSITIVE_INFINITY;
			else if (args[i].equals("--no-hpc-boost"))
				TraceGenerator.enableHpcBoost = false;
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

		model = loadModel(filename, constants, doDontCareElimination, compositionStateLimit);
		if (janiOutputFile != null)
			MakeJani.makeJani(model, janiOutputFile, jsonOutput ? filename : null, args, properties);
		if (traLabOutputFile != null) {
			try {
				MakeTraLab mtl = new MakeTraLab(model, unsafeComposition);
				mtl.convert(traLabOutputFile);
			} catch (NondeterminismException e) {
				e.printStackTrace();
				LTS tmpModel = loadModel(filename, constants, false, compositionStateLimit);
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

		// This runs on exit, including early termination e.g. by user SIGNAL
		final long seedChosen = seed;  // effectively final seed, you dense JVM
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			@Override public void run() { terminate(startTime, seedChosen); }
		});

		for (Property prop : properties) {
			if (!onlyProperties.isEmpty() && !onlyProperties.contains(prop.name))
				continue;
			try {
				runSimulations(prop);
			} catch (UnsupportedOperationException e2) {
				System.err.println(prop.name + ": " + e2.getMessage());
			}
		}
		assert(lastResults.isEmpty() || !simulator.hasResults());

		System.exit(0);  // results are dumped by shutdown hook
	}
}
