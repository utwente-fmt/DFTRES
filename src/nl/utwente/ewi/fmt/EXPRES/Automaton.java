package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

public class Automaton implements LTS {
	/** The initial state of the automaton */
	public final int initState;
	/* targets[i][j] denotes the target state of the j'th transition
	 * from state i. labels[i][j] denotes the label of that
	 * transition. */
	private int targets[][];
	private String labels[][];
	private HashMap<String, Integer> assignments[][];
	private HashMap<String, Integer> transitions[];
	private final static boolean VERBOSE = false;

	/** Construct an automaton by reading it from a file.
	 * @param filename The name of the file to read.
	 * @param type The file type of the given file, currently
	 * supported types: aut
	 */
	public Automaton(String filename, String type) throws IOException
	{
		if (VERBOSE)
			System.err.println("New automaton: " + filename);
		switch(type)
		{
			case "aut":
				initState = readAut(filename);
				break;
			case "bcg":
				initState = readBcg(filename);
				break;
			default:
				throw new IllegalArgumentException("Unknown automaton type.");
		}
	}

	/* Private since 'Map' may in the future be suitable for
	 * multiple types.
	 */
	private Automaton(Map janiData)
	{
		if (janiData.containsKey("variables"))
			throw new IllegalArgumentException("Local variables not yet supported.");
		if (janiData.containsKey("restrict-initial"))
			throw new IllegalArgumentException("Local initial state restrictions not yet supported.");
		HashMap<String, Integer> locations = new HashMap<>();
		Object loco = janiData.get("locations");
		if (!(loco instanceof Object[]))
			throw new IllegalArgumentException("Locations should be array, not: " + loco);
		Object[] locs = (Object[]) loco;
		for (Object loc : locs) {
			if (!(loc instanceof Map))
				throw new IllegalArgumentException("Location should be JSON object, not: " + loc);
			Map ldata = (Map)loc;
			if (!ldata.containsKey("name"))
				throw new IllegalArgumentException("Unnamed location");
			String name = ldata.get("name").toString();
			if (ldata.containsKey("time-progress"))
				throw new IllegalArgumentException("Locations with time progress conditions are not supported.");
			if (ldata.containsKey("transient-values"))
				throw new IllegalArgumentException("Transient values not supported.");
			locations.put(name, locations.size());
		}
		labels = new String[locations.size()][0];
		targets = new int[locations.size()][0];
		assignments = (HashMap<String, Integer>[][])new HashMap[locations.size()][0];
		Object inito = janiData.get("initial-locations");
		if (!(inito instanceof Object[]))
			throw new IllegalArgumentException("Initial locations should be array of size 1, not: " + inito);
		Object[] inits = (Object[]) inito;
		if (inits.length != 1)
			throw new IllegalArgumentException("Automata must currently have exactly one initial location.");
		Integer initialLocation = locations.get(inits[0].toString());
		if (initialLocation == null)
			throw new IllegalArgumentException("Unknown initial location: " + inits[0]);
		initState = initialLocation;
		Object edgeo = janiData.get("edges");
		if (edgeo == null)
			throw new IllegalArgumentException("Automata without edges not supported.");
		if (!(edgeo instanceof Object[]))
			throw new IllegalArgumentException("Edges in automata should be an array, not " + edgeo);
		Object[] edges = (Object[]) edgeo;
		for (Object eo : edges) {
			if (!(eo instanceof Map))
				throw new IllegalArgumentException("Each edge should be a JSON object, not: " + eo);
			Map edge = (Map)eo;
			Object src = edge.get("location");
			if (src == null)
				throw new IllegalArgumentException("Edge without source specified.");
			Integer srci = locations.get(src.toString());
			if (srci == null)
				throw new IllegalArgumentException("Unknown location as source of edge: " + src);
			String action = null;
			Object ao = edge.get("action");
			if (ao != null)
				action = ao.toString();
			Object ro = edge.get("rate");
			if (ro == null && ao == null)
				action = "i";
			if (ro != null && ao != null)
				throw new IllegalArgumentException("An edge cannot have both an action and a rate.");
			if (ro != null) {
				if (!(ro instanceof Map))
					throw new IllegalArgumentException("Edge rates must be JSON objects, not: " + ro);
				Map rateMap = (Map)ro;
				Object rate = rateMap.get("exp");
				if (!(rate instanceof Number))
					throw new IllegalArgumentException("Unsupported rate expression: " + rate);
				action = "rate " + rate;
			}
			labels[srci] = Arrays.copyOf(labels[srci], labels[srci].length + 1);
			labels[srci][labels[srci].length - 1] = action;
			if (edge.containsKey("guard"))
				throw new IllegalArgumentException("Guards on transitions currently not supported.");
			Object destO = edge.get("destinations");
			if (!(destO instanceof Object[]))
				throw new IllegalArgumentException("Destinations of edges must be arrays (currently of size 1).");
			Object[] dests = (Object[])destO;
			if (dests.length != 1)
				throw new IllegalArgumentException("In-edge nondeterminism currently not supported, each edge must have one destination.");
			destO = dests[0];
			if (!(destO instanceof Map))
				throw new IllegalArgumentException("Each destination should be a JSON object, not: " + destO);
			Map dest = (Map)destO;
			destO = dest.get("location");
			Integer target = null;
			if (destO != null)
				target = locations.get(destO.toString());
			if (target == null)
				throw new IllegalArgumentException("Unknown target location: " + destO);
			/* Deliberatly ignoring probability, since we
			 * only support non-probabilistic transitions */
			targets[srci] = Arrays.copyOf(targets[srci], targets[srci].length + 1);
			targets[srci][targets[srci].length - 1] = target;
			Object assignO = dest.get("assignments");
			if (assignO == null)
				assignO = new Object[0];
			if (!(assignO instanceof Object[]))
				throw new IllegalArgumentException("Assignments shoud be array, not: " + assignO);
			Object[] assignments = (Object[])assignO;
			HashMap<String, Integer> assignMap = new HashMap<>();
			for (Object assO : assignments) {
				if (!(assO instanceof Map))
					throw new IllegalArgumentException("Assignment should be JSON object, not: " + assO);
				Map assignment = (Map)assO;
				Object refO = assignment.get("ref");
				if (!(refO instanceof String))
					throw new IllegalArgumentException("Assignment only supported to identifiers.");
				String ref = (String)refO;
				int val = 0;
				Object valO = assignment.get("value");
				if (valO instanceof Long) {
					val = ((Long)valO).intValue();
					if (!Long.valueOf(val).equals(valO))
						throw new IllegalArgumentException("Range error in assignment of value: " + valO);
				} else if (valO instanceof Boolean) {
					val = ((Boolean)valO) ? 1 : 0;
				} else
					throw new IllegalArgumentException("Assignments only supported to integers and booleans.");
				assignMap.put(ref, val);
			}
			if (!assignMap.isEmpty()) {
				this.assignments[srci] = Arrays.copyOf(this.assignments[srci], labels[srci].length);
				this.assignments[srci][labels[srci].length - 1] = assignMap;
			}
		}
		transitions = createTransitionArray();
		for (int i = 0; i < targets.length; i++) {
			transitions[i] = new HashMap<>();
			for (int j = 0; j < targets[i].length; j++) {
				transitions[i].put(labels[i][j], targets[i][j]);
			}
		}
	}

	public static Automaton fromJani(Map janiData)
	{
		return new Automaton(janiData);
	}	

	@SuppressWarnings("unchecked")
	private HashMap<String, Integer>[] createTransitionArray()
	{
		return (HashMap<String, Integer>[]) new HashMap[labels.length];
	}

	/** Create a new automaton by renaming some transitions from an
	 * existing automaton.
	 * @param orig: The automaton from which to rename transitions
	 * @param renames: The set of transition to rename.
	 */
	public Automaton(Automaton orig, Map<String, String> renames)
	{
		boolean anyChanges = false;
		initState = orig.initState;
		targets = orig.targets;
		labels = new String[orig.labels.length][];
		for (int i = 0; i < labels.length; i++) {
			boolean changed = false;
			labels[i] = new String[orig.labels[i].length];
			for (int j = 0; j < labels[i].length; j++) {
				String label = renames.get(orig.labels[i][j]);
				if (label != null) {
					//System.out.println("Renaming to " + label);
					changed = true;
				} else
					label = orig.labels[i][j];
				labels[i][j] = label;
			}
			if (!changed)
				labels[i] = orig.labels[i];
			anyChanges |= changed;
		}
		if (!anyChanges) {
			labels = orig.labels;
			transitions = orig.transitions;
		} else {
			transitions = createTransitionArray();
			for (int i = labels.length - 1; i >= 0; i--) {
				transitions[i] = new HashMap<String, Integer>();
				for (int j = labels[i].length - 1; j >= 0; j--) {
					if (transitions[i].containsKey(labels[i][j])) {
						System.err.println("Warning: internal nondeterminism currently not supported.");
					}
					transitions[i].put(labels[i][j], targets[i][j]);
				}
			}
		}

	}

	private int readBcg(String filename) throws IOException
	{
		System.err.println(filename);
		String[] cmd = new String[] {"bcg_io", filename, "-aldebaran", "-"};
		Process p = Runtime.getRuntime().exec(cmd);
		InputStream i = p.getInputStream();
		int ret = readAutStream(i);
		boolean done = false;
		while (!done) {
			try {
				p.waitFor();
				done = true;
			} catch (InterruptedException e) {
			}
		}
		return ret;
	}

	private int readAut(String filename) throws IOException
	{
		return readAutStream(new FileInputStream(filename));
	}

	private int readAutStream(InputStream str) throws IOException
	{
		int ret, nStates, nTrans, i;
		BufferedReader input = new BufferedReader(new InputStreamReader(str));
		String line = input.readLine();
		String parts[];
		if (!line.startsWith("des"))
			System.err.println(line);
		line = line.substring(line.indexOf('(') + 1,
		                      line.lastIndexOf(')'));
		parts = line.split("\\s*,\\s*");
		ret = Integer.parseInt(parts[0]);
		nStates = Integer.parseInt(parts[2]);
		nTrans = Integer.parseInt(parts[1]);
		targets = new int[nStates][0];
		labels = new String[nStates][0];
		for (i = 0; i < nTrans; i++) {
			int first, last, from, to;
			line = input.readLine();
			line = line.substring(line.indexOf('(') + 1,
			                      line.lastIndexOf(')'));
			first = line.indexOf(',');
			last = line.lastIndexOf(',');
			parts[0] = line.substring(0, first).trim();
			parts[1] = line.substring(first + 1, last).trim();
			parts[2] = line.substring(last + 1).trim();
			if (parts[1].charAt(0) == '"') {
				parts[1] = parts[1].substring(1, parts[1].length() - 1);
			}

			from = Integer.parseInt(parts[0]);
			to = Integer.parseInt(parts[2]);
			targets[from] = Arrays.copyOf(targets[from],
			                              targets[from].length + 1);
			labels[from] = Arrays.copyOf(labels[from],
			                             labels[from].length + 1);
			targets[from][targets[from].length - 1] = to;
			labels[from][targets[from].length - 1] = parts[1].intern();
		}
		transitions = createTransitionArray();
		for (i = labels.length - 1; i >= 0; i--) {
			transitions[i] = new HashMap<String, Integer>();
			for (int j = labels[i].length - 1; j >= 0; j--) {
				if (labels[i][j].startsWith("rate "))
					continue;
				if (transitions[i].containsKey(labels[i][j])) {
					System.err.format("Warning: internal nondeterminism currently not supported (Transition %s in state %d).\n", labels[i][j], i);
				}
				transitions[i].put(labels[i][j], targets[i][j]);
			}
		}
		return ret;
	}

	/**
	 * @return The target of the n'th transition from state 'from',
	 * or -1 if 'from' has fewer than n transitions.
	 */
	public int getTransitionTarget(int from, int n)
	{
		if (targets[from].length <= n)
			return -1;
		return targets[from][n];
	}

	/**
	 * @return The target of the n'th transition from state 'from',
	 * or null if 'from' has fewer than n transitions.
	 */
	public String getTransitionLabel(int from, int n)
	{
		if (labels[from].length <= n)
			return null;
		return labels[from][n];
	}

	/**
	 * @return The target of the names transition, or -1 if no such
	 * transition exists.
	 */
	public int getTargetFor(int from, String transition)
	{
		Integer r = transitions[from].get(transition);
		if (r == null)
			return -1;
		return r;
	}

	public HashMap<String, Integer> getAssignments(int from, int n)
	{
		if (assignments == null)
			return null;
		if (assignments[from].length <= n)
			return null;
		return assignments[from][n];
	}

	public HashMap<String, Integer> getAssignmentsFor(int from, String act)
	{
		for (int i = 0; i < labels[from].length; i++) {
			if (labels[from][i].equals(act))
				return getAssignments(from, i);
		}
		return null;
	}

	public Map<String, Integer> getVarValues(int[] state)
	{
		return new HashMap<String, Integer>();
	}

	/** @return The number of states in this automaton */
	public int getNumStates()
	{
		return labels.length;
	}

	public int[] getInitialState()
	{
		return new int[]{0};
	}

	public TreeSet<LTS.Transition> getTransitions(int[] from)
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		int i = 0, target, src = from[0];
		while ((target = getTransitionTarget(src, i)) >= 0) {
			String label = getTransitionLabel(src, i);
			ret.add(new LTS.Transition(label, new int[]{target}));
			i++;
		}
		return ret;
	}

	public int stateSize()
	{
		return 1;
	}

	public String toString()
	{
		StringBuilder ret = new StringBuilder();
		ret.append(String.format("Number of states: %d\n", getNumStates()));
		ret.append(String.format("Initial state: %d\n", initState));
		for (int i = 0; i < getNumStates(); i++) {
			for (int j = 0; getTransitionTarget(i, j) > -1; j++) {
				ret.append(String.format("%5d ---> %5d (%s)\n",
						i,
						getTransitionTarget(i, j),
						getTransitionLabel(i, j)));
			}
		}
		return ret.toString();
	}

	public void printJaniAutomaton(String name)
	{
		boolean first = true;
		System.out.println("\t{\"name\":\""+name+"\",");
		System.out.print  ("\t \"locations\":[");
		for (int i = 0; i < targets.length; i++) {
			if (i > 0)
				System.out.print(",");
			System.out.print("{\"name\":\"l"+i+"\"}");
		}
		System.out.println("],"); /* End of locations */
		System.out.println("\t \"initial-locations\":[\"l"+initState+"\"],");
		System.out.println("\t \"edges\":[");
		for (int i = 0; i < targets.length; i++) {
			for (int j = 0; j < targets[i].length; j++) {
				if (!first)
					System.out.println("\t\t,");
				first = false;
				System.out.println("\t\t{\"location\":\"l"+i+"\",");
				System.out.println("\t\t \"destinations\":[{\"location\":\"l"+targets[i][j]+"\"}],");
				if (labels[i][j].startsWith("rate ")) {
					System.out.println("\t\t \"rate\":{\"exp\":"+labels[i][j].substring(5)+"}");
				} else {
					System.out.println("\t\t \"action\":\""+labels[i][j]+"\"");
				}
				System.out.println("\t\t}");
			}
		}
		System.out.println("\t]}");
	}
}
