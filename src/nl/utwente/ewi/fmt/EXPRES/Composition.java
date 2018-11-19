package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.LinkedHashMap;
import nl.ennoruijters.util.JSONParser;

public class Composition implements MarkableLTS
{
	public static int statesExplored;
	private boolean[] possibleForcedTransitions;
	private int[][] vectorAutomata;
	private String[][] vectorLabels;
	private String[] synchronizedLabels;
	private Automaton[] automata;
	private TreeSet<String> hideLabels;
	private TreeMap<String, Integer> markLabels;
	private TreeMap<String, int[]> globalVars; /* Argument: lower bit, upper bit (both inclusive), initial value, lower bound */
	/* From a partial state to the targets of the needed automata */
	private LinkedHashMap<PartialState, int[]> transitionCache;
	private int[] noTransitionPossible = new int[0];

	/* Rejection cache: a transition t (i.e.
	 * t<vectorAutomata.length) was last rejected by automaton
	 * rejectedFor[2*t] being in state rejectedFor[2*t+1].
	 */
	private ThreadLocal<int[]> rejectedFor;
	private int[] haveRateTransitions;

	private class PartialState {
		private final Composition parent;
		public int transition;
		public int[] states;

		public PartialState(Composition p)
		{
			parent = p;
		}

		public int hashCode()
		{
			int ret = transition;
			int i = parent.vectorAutomata[transition].length;
			while (i --> 0) {
				ret = (ret * 31) + states[i];
			}
			return ret;
		}

		public boolean equals(Object other)
		{
			if (!(other instanceof PartialState))
				return false;
			PartialState os = (PartialState) other;
			if (os.transition != transition)
				return false;
			int i = parent.vectorAutomata[transition].length - 1;
			for (; i >= 0; i--) {
				if (os.states[i] != states[i])
					return false;
			}
			return true;
		}
	}

	public static String stateString(int[] state)
	{
		char[] tmp = new char[state.length * 3];
		int usedChars = 0;
		int i;
		for (i = state.length - 1; i >= 0; i--) {
			if (state[i] < 65535) {
				tmp[usedChars++] = (char)state[i];
			} else {
				tmp[usedChars++] = 65535;
				tmp[usedChars++] = (char)(state[i] & 0xFFFF);
				tmp[usedChars++] = (char)(state[i] >>> 16);
			}
		}
		return new String(tmp, 0, usedChars);
	}

	public static int[] stringToState(String sString)
	{
		int len = 0;
		for (int i = 0; i < sString.length(); i++) {
			if (sString.charAt(i) == 65535)
				i += 2;
			len++;
		}
		int ret[] = new int[len];
		int j = ret.length - 1;
		for (int i = 0; i < sString.length(); i++) {
			if (sString.charAt(i) < 65535) {
				ret[j--] = sString.charAt(i);
			} else {
				ret[j] = sString.charAt(i + 2);
				ret[j] <<= 16;
				ret[j--] += sString.charAt(i + 1);
				i += 2;
			}
		}
		return ret;
	}

	/** Construct a composition by reading a file.
	 * @param filename The file to read.
	 * @param type The type of the given file, currently supported:
	 * exp
	 */
	public Composition(String filename, String type, Set<Property> propertiesOut) throws IOException
	{
		markLabels = new TreeMap<String, Integer>();
		Set<Property> props = new TreeSet<>();
		switch (type) {
			case "exp":
				readExpFile(filename);
				break;
			case "jani":
				props = readJaniFile(filename);
				if (propertiesOut != null)
					propertiesOut.addAll(props);
				break;
			default:
				throw new IllegalArgumentException("Unsupported composition type");
		}
		afterParsing();
		transitionCache = new LinkedHashMap<PartialState, int[]>() {
			protected boolean removeEldestEntry(Map.Entry<PartialState, int[]> eldest)
			{
				return size() > 10000000;
			}
		};
		initForcedTransitions();
	}

	public Composition(String filename, String type) throws IOException
	{
		this(filename, type, null);
	}

	Composition()
	{
		markLabels = new TreeMap<String, Integer>();
		transitionCache = new LinkedHashMap<PartialState, int[]>() {
			protected boolean removeEldestEntry(Map.Entry<PartialState, int[]> eldest)
			{
				return size() > 1000000;
			}
		};
	}

	private final void initForcedTransitions()
	{
		TreeMap<String, Integer> transitions = new TreeMap<>();

		boolean ret[] = new boolean[vectorAutomata.length];
		for (int i = 0; i < ret.length; i++)
			ret[i] = true;

		for (int i = 0; i < ret.length; i++) {
			int[] auts = vectorAutomata[i];
			for (int j = 0; j < auts.length; j++) {
				String t = auts[j] + "-" + vectorLabels[i][j];
				Integer prev = transitions.get(t);
				if (prev != null) {
					ret[prev] = false;
					ret[i] = false;
				} else {
					transitions.put(t, i);
				}
			}
		}
		possibleForcedTransitions = ret;
	}

	public void markStatesAfter(String label, int val)
	{
		if (!globalVars.isEmpty())
			throw new UnsupportedOperationException("Combination of marking states and global variables currently not supported.");
		markLabels.put(label, val);
	}

	public void hideLabel(String label)
	{
		hideLabels.add(label);
	}

	/** Process a line describing a set of renames and a filename.
	 * @param line The line beginning with "total rename" and ending
	 * with "end rename"
	 * @param ret The map to be filled with the renames.
	 * @return The name of the file in which the renames should be
	 * performed.
	 */
	private String readRenames(String line, TreeMap<String, String> ret)
	{
		if (!line.matches("^total rename .* in .* end rename$"))
			throw new IllegalArgumentException("Invalid rename line");
		line = line.substring("total rename ".length(),
		                      line.length() - " end rename".length());
		String[] parts = line.split("(, | in )");
		line = parts[parts.length - 1];
		for (int i = parts.length - 2; i >= 0; i--) {
			String[] s = parts[i].split("\\s*->\\s*");
			if (s[0].charAt(0) == '"')
				s[0] = s[0].substring(1, s[0].length() - 1);
			s[0] = s[0].intern();
			if (s[1].charAt(0) == '"')
				s[1] = s[1].substring(1, s[1].length() - 1);
			s[1] = s[1].intern();
			ret.put(s[0], s[1]);
		}
		return line;
	}

	private void readHides(BufferedReader input) throws IOException
	{
		String line;
		hideLabels = new TreeSet<String>();
		do {
			line = input.readLine().trim();
			String label = line;
			if (label.charAt(label.length() - 1) == ',')
				label = label.substring(0, label.length() - 1);
			hideLabels.add(label);
		} while (line.charAt(line.length() - 1) == ',');
	}

	private void readExpFile(String filename) throws IOException
	{
		BufferedReader input = new BufferedReader(new FileReader(filename));
		int numAutomata = 0;
		ArrayList<int[]> vectorAutomata = new ArrayList<int[]>();
		ArrayList<String[]> vectorLabels = new ArrayList<String[]>();
		ArrayList<String> syncLabels = new ArrayList<String>();
		int i;
		globalVars = new TreeMap<>();
		String line = "";
		while (!line.equals("hide"))
			line = input.readLine().trim();
		readHides(input);
		while (!line.equals("label par using"))
			line = input.readLine().trim();
		do {
			int vectorPos = 0;
			line = input.readLine().trim();
			/* Remove any comments */
			line = line.replaceAll("\\s*\\(\\*.*\\*\\)\\s*", "");
			if (line.length() == 0)
				continue;
			if (line.charAt(line.length() - 1) == ',')
				line = line.substring(0, line.length() - 1);
			if (!line.equals("in")) {
				/* This should be a synchronization vector */
				String[] parts = line.split("\\s*(\\*|->)\\s*");
				numAutomata = parts.length - 1;
				int[] automs = new int[parts.length];
				String[] labels = new String[parts.length];
				for (i = 0; i < parts.length; i++) {
					if (parts[i].charAt(0) == '"') {
						parts[i] = parts[i].substring(
								1,
								parts[i].length() - 1);
					}
					if (!parts[i].equals("_")) {
						automs[vectorPos] = i;
						labels[vectorPos] = parts[i].intern();
						vectorPos++;
					}
				}
				syncLabels.add(labels[vectorPos - 1]);
				automs = Arrays.copyOf(automs, vectorPos - 1);
				labels = Arrays.copyOf(labels, vectorPos - 1);
				vectorAutomata.add(automs);
				vectorLabels.add(labels);
			}
		} while (!line.equals("in"));
		this.vectorAutomata = vectorAutomata.toArray(new int[vectorAutomata.size()][]);
		this.vectorLabels = vectorLabels.toArray(new String[vectorLabels.size()][]);
		this.synchronizedLabels = syncLabels.toArray(new String[syncLabels.size()]);
		/*
		for (i = 0; i < this.vectors.length; i++) {
			System.out.format("Vector %d:", i);
			for (int j = 0; j < this.vectors[i].length; j++) {
				System.out.format(" %s", this.vectors[i][j]);
			}
			System.out.format(" (%d)\n", this.vectors[i].length);
		}
		*/

		automata = new Automaton[numAutomata];
		i = 0;
		line = input.readLine().trim();
		while (!line.equals("end par")) {
			if (line.equals("||")) {
				line = input.readLine().trim();
				continue;
			}
			TreeMap<String, String> renames = null;
			if (line.startsWith("total rename")) {
				renames = new TreeMap<String, String>();
				line = readRenames(line, renames);
			}
			if (!line.matches("^\".*\"$")) {
				throw new IllegalArgumentException("No quoted filename on line " + line);
			}
			line = line.substring(1, line.length() - 1);
			String type = line.substring(line.lastIndexOf(".") + 1);
			Automaton a = new Automaton(line, type);
			if (renames != null)
				a = new Automaton(a, renames);
			automata[i++] = a;
			line = input.readLine().trim();
		}
		/*
		System.out.format("%d automata\n", automata.length);
		for (Automaton a : automata)
			System.out.println(a);
			*/
	}

	private void afterParsing()
	{
		rejectedFor = new ThreadLocal<int[]>();
		haveRateTransitions = new int[0];
		for (int i = 0; i < automata.length; i++) {
			for (int j = 0; j < automata[i].getNumStates(); j++) {
				for (int k = 0; true; k++) {
					String l = automata[i].getTransitionLabel(j, k);
					if (l == null)
						break;
					if (l.startsWith("rate ")) {
						haveRateTransitions = Arrays.copyOf(haveRateTransitions, haveRateTransitions.length + 1);
						haveRateTransitions[haveRateTransitions.length - 1] = i;
						j = Integer.MAX_VALUE - 1;
						break;
					}
				}
			}
		}
		TreeMap<String, Integer> mins = new TreeMap<>();
		TreeMap<String, Integer> maxs = new TreeMap<>();
		for (Map.Entry<String, int[]> e : globalVars.entrySet()) {
			mins.put(e.getKey(), e.getValue()[2]);
			maxs.put(e.getKey(), e.getValue()[2]);
		}
		for (Automaton a : automata) {
			for (int i = a.getNumStates() - 1; i >= 0; i--) {
				int n = 0;
				Map<String, Integer> assigns;
				while ( (assigns = a.getAssignments(i, n)) != null) {
					for (Map.Entry<String, Integer> e : assigns.entrySet()) {
						String v = e.getKey();
						Integer val = e.getValue();
						Integer cMin = mins.get(v);
						if (cMin == null || val < cMin)
							mins.put(v, val);
						Integer cMax = maxs.get(v);
						if (cMax == null || val > cMax)
							maxs.put(v, val);
					}
					n++;
				}
			}
		}
		int uBit = 0;
		for (String v : mins.keySet()) {
			Integer min = mins.get(v);
			Integer max = maxs.get(v);
			int[] data = globalVars.get(v);
			data[3] = min;
			long range = max;
			range -= min;
			int bits = 0;
			while (range > 0) {
				bits++;
				range >>>= 1;
			}
			data[0] = uBit;
			uBit += bits;
			if (uBit > 31)
				throw new IllegalArgumentException("Out of bits for global variables");
			data[1] = uBit;
		}
	}

	/** @return The initial state of this composed automaton */
	public int[] getInitialState() {
		int ret[] = new int[automata.length + 1];
		for (int i = automata.length - 1; i >= 0; i--)
			ret[i] = automata[i].initState;
		return ret;
	}

	public int stateSize()
	{
		return automata.length + 1;
	}

	public Map<String, Integer> getVarValues(int[] state)
	{
		int vals = state[state.length - 1];
		TreeMap<String, Integer> ret = new TreeMap<>();
		if (globalVars.isEmpty()) {
			ret.put("marked", vals);
			return ret;
		}
		for (Map.Entry<String, int[]> e : globalVars.entrySet()) {
			int vData[] = e.getValue();
			int val = vals >> vData[0];
			val &= ((-1) >>> (32 - vData[1]));
			val += vData[3];
			ret.put(e.getKey(), val);
		}
		return ret;
	}

	public int getVarValue(String var, int[] state)
	{
		int vals = state[state.length - 1];
		TreeMap<String, Integer> ret = new TreeMap<>();
		if (var.equals("marked") && globalVars.isEmpty()) {
			return vals;
		}
		int[] vData = globalVars.get(var);
		if (vData == null)
			throw new IllegalArgumentException("Attempt to read value of undeclared variable '" + var + "'");
		int val = vals >> vData[0];
		val &= ((-1) >>> (32 - vData[1]));
		val += vData[3];
		return val;
	}

	private void doAssigns(int[] state, Map<String, Integer> assigns)
	{
		for (Map.Entry<String, Integer> e : assigns.entrySet()) {
			int[] vData = globalVars.get(e.getKey());
			int val = e.getValue();
			val -= vData[2]; /* Remove lower bound */
			int origVals = state[state.length - 1];
			val <<= vData[0];
			int mask = -1;
			mask <<= vData[0];
			mask &= ((-1) >>> (32 - vData[1]));
			origVals &= ~mask;
			state[state.length - 1] = origVals | val;
		}
	}

	public Set<LTS.Transition> getTransitions(int[] from)
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		int t[] = new int[automata.length];
		//PartialState part = new PartialState(this);

		/* First, 'rate' transitions are always taken
		 * unsynchronized.
		 */
		for (int i : haveRateTransitions) {
			for (int j = 0; true; j++) {
				String l = automata[i].getTransitionLabel(from[i], j);
				if (l == null)
					break;
				if (l.startsWith("rate ")) {
					int[] target = Arrays.copyOf(from, from.length);
					target[i] = automata[i].getTransitionTarget(from[i], j);
					Map<String, Integer> assigns = automata[i].getAssignments(from[i], j);
					if (assigns != null)
						doAssigns(target, assigns);
					ret.add(new LTS.Transition(l, target));
				}
			}
		}

		for (int i = 0; i < vectorAutomata.length; i++) {
			int[] needed = vectorAutomata[i];
			String[] neededL = vectorLabels[i];
			int j;

			/* Check the rejection cache */
			int[] rejCache = rejectedFor.get();
			if (rejCache == null) {
				rejCache = new int[2*vectorAutomata.length];
				for (int k = 0; k < vectorAutomata.length; k++)
					rejCache[2*k+1] = -1;
				rejectedFor.set(rejCache);
			}
			if (from[rejCache[2*i]] == rejCache[2*i+1])
				continue;

			/* Try the partial-state cache first */
			/*
			part.transition = i;
			part.states = t;
			for (j = needed.length - 1; j >= 0; j--)
				t[j] = from[needed[j]];
			int[] target = transitionCache.get(part);
			if (target == noTransitionPossible)
				continue;
				*/
			int[] target;
			TreeMap<String, Integer> assigns = new TreeMap<>();

			for (j = needed.length - 1; j >= 0; j--)
			{
				Automaton a = automata[needed[j]];
				int origin = from[needed[j]];
				t[j] = a.getTargetFor(origin, neededL[j]);
				if (t[j] < 0) {
					rejCache[2*i] = needed[j];
					rejCache[2*i+1] = from[needed[j]];
					//System.err.println("Rejecting transition " + synchronizedLabels[i] + ": need " + neededL[j] + " at " + needed[j]);
					break;
				}
				Map<String, Integer> as = a.getAssignmentsFor(origin, neededL[j]);
				if (as != null)
					assigns.putAll(as);
			}
			if (j >= 0) {
				/*
				part.states = new int[needed.length];
				for (int k = needed.length - 1; k>=0; k--)
					part.states[k] = from[needed[k]];
				transitionCache.put(part, noTransitionPossible);
				part = new PartialState(this);
				*/
				continue;
			}

			/* Transition possible, we assume no internal
			 * nondeterminism.
			 */

			Integer m;
			target = Arrays.copyOf(from, from.length);
			m = markLabels.get(synchronizedLabels[i]);
			if (m != null)
				target[automata.length] = m;
			for (j = 0; j < needed.length; j++) {
				target[needed[j]] = t[j];
			}
			doAssigns(target, assigns);
			//System.err.println("Transition possible: " + synchronizedLabels[i]);
			ret.add(new Transition(synchronizedLabels[i], target));
		}

		return ret;
	}

	/** Return the result of performing all currently enabled
	 * forced transitions. These are transitions with with following
	 * properties:
	 *
	 * - Non-Markovian
	 * - All automata involved have no other non-Markovian outgoing
	 *   transitions.
	 * - The automata cannot (in their current state) be involved in
	 *   any other synchronized transitions.
	 *
	 * These conditions imply that any Markovian state reachable
	 * from the current state, is also reachable from the returned
	 * state.
	 */
	public int[] performForcedTransitions(int[] from)
	{
		int ret[] = from.clone();
		int target[] = from.clone();

		//System.err.format("Checking for forced transitions from %s\n", Arrays.toString(from));
		for (int i = 0; i < vectorAutomata.length; i++) {
			if (!possibleForcedTransitions[i])
				continue;
			int[] needed = vectorAutomata[i];
			String[] neededL = vectorLabels[i];
			int j;

			for (j = needed.length - 1; j >= 0; j--) {
				Automaton a = automata[needed[j]];
				int origin = ret[needed[j]];
				/* We require that the transition is enabled in this
				 * automaton, and is the only enabled non-Markovian transition
				 * in the automaton.
				 */
				int n = 0;
				String label;
				target[j] = -1;
				while ((label = a.getTransitionLabel(origin, n)) != null) {
					if (!label.startsWith("rate ")) {
						if (label.equals(neededL[j]) && target[j] == -1) {
							target[j] = a.getTransitionTarget(origin, n);
						} else {
							//System.err.format("Rejecting transition %d for %s from %d (need %s), target was %d\n", i, label, needed[j], neededL[j], target[j]);
							target[j] = -1;
							break;
						}
					}
					n++;
				}
				if (target[j] == -1)
					break;
			}
			if (j >= 0) {
				/* Not a valid forced transition */
				continue;
			}

			/* We have found a forced transition. */
			for (int s : needed)
				ret[s] = target[s];

			Integer m;
			m = markLabels.get(synchronizedLabels[i]);
			if (m != null)
				ret[automata.length] = m;
		}
		if (Arrays.equals(ret, from)) {
			//System.err.println("No forced transitions");
			return from;
		}
		//System.err.format("Forced transition(s) to %s\n", Arrays.toString(ret));
		return ret;
	}

	/** Obtain one long non-Markovian transition that composes
	 * several independent transitions.
	 */
	public int[] getBigTransition(int[] from)
	{
		int state[] = Arrays.copyOf(from, from.length);
		int t[] = new int[automata.length];

		for (int i = 0; i < vectorAutomata.length; i++) {
			int[] needed = vectorAutomata[i];
			String[] neededL = vectorLabels[i];
			int j;

			for (j = needed.length - 1; j >= 0; j--)
			{
				Automaton a = automata[needed[j]];
				int origin = state[needed[j]];
				int k = -1;
				String label;
				t[j] = -1;
				do {
					k++;
					label = a.getTransitionLabel(origin, k);
					if (neededL[j].equals(label)) {
						if (t[j] >= 0) {
							System.err.format("Internal nondeterminism found is automaton %d label %s from state %d, spurious cycles may occur.\n", needed[j], label, origin);
						}
						t[j] = a.getTransitionTarget(origin, k);
					}
				} while (label != null);
				if (t[j] < 0)
					break;
			}
			if (j >= 0)
				continue;

			/* Transition possible, we assume no internal
			 * nondeterminism.
			 */

			Integer m;
			m = markLabels.get(synchronizedLabels[i]);
			if (m != null)
				state[automata.length] = m;
			for (j = 0; j < needed.length; j++) {
				state[needed[j]] = t[j];
			}
		}

		return state;
	}

	public void printAutomata()
	{
		for (Automaton a : automata)
			System.out.println(a);
	}

	private TreeSet<String> getAllTransitionLabels()
	{
		TreeSet<String> ret = new TreeSet<>();
		for (Automaton a : automata) {
			for (int i = a.getNumStates() - 1; i >= 0; i--) {
				int j = 0;
				String l;
				while ((l = a.getTransitionLabel(i, j++)) != null) {
					if (!l.startsWith("rate "))
						ret.add(l);
				}
			}
		}
		return ret;
	}

	public void printJani(String name, PrintStream out, Set<Property> props)
	{
		TreeSet<String> localHides = new TreeSet<>(hideLabels);
		/* Add markLabels to the hide set, as the marking
		 * (presumably) captures any visible effects of the
		 * transition. */
		localHides.addAll(markLabels.keySet());
		out.println("{\"jani-version\":1,");
		out.println("\"name\":\""+name+"\",");
		out.println("\"type\":\"ma\",");
		out.println("\"variables\":["+
				"{\"name\":\"marked\",\"type\":\"bool\","+
				"\"initial-value\":false}],");
		/* List of all actions */
		out.println("\"actions\":[");
		TreeSet<String> actions = new TreeSet<String>();
		for (String l : synchronizedLabels)
			actions.add(l);
		actions.removeAll(localHides);
		if (!actions.isEmpty()) {
			System.err.println("Warning: non-hidden labels remain after synchronization:");
			System.err.println(actions);
			System.err.println("Hiding these anyway.");
			localHides.addAll(actions);
			actions.clear();
		}
		actions.add("τ"); /* Needed for Storm compatibility */
		for (String[] ls : vectorLabels) {
			for (String l : ls)
				actions.add(l);
		}
		actions.addAll(getAllTransitionLabels());
		for (String l : actions)
			out.println("\t{\"name\":\""+l+"\"},");
		out.println("\t{\"name\":\"mark\"},");
		out.println("\t{\"name\":\"unmark\"}");
		out.println("\t],");
		out.println("\"automata\":[");
		HashMap<Automaton, Integer> outputAutomata = new HashMap<>();
		String autNames[] = new String[automata.length];
		for (int i = 0; i < automata.length; i++) {
			Integer num = outputAutomata.get(automata[i]);
			if (num == null) {
				num = outputAutomata.size();
				outputAutomata.put(automata[i], num);
				automata[i].printJaniAutomaton("aut"+num, out);
				out.println(",");
			}
			autNames[i] = "aut" + num;
		}
		/* Monitor automaton */
		out.println("\t{\"name\":\"monitor\",");
		out.println("\t \"locations\":[{\"name\":\"l\"}],");
		out.println("\t \"initial-locations\":[\"l\"],");
		out.println("\t \"edges\":[");
		out.println("\t\t{\"location\":\"l\",");
		out.println("\t\t \"action\":\"mark\",");
		out.println("\t\t \"destinations\":[{");
		out.println("\t\t\t\"location\":\"l\",");
		out.println("\t\t\t\"assignments\":[{\"ref\":\"marked\", \"value\":true}]");
		out.println("\t\t\t}]},");
		out.println("\t\t{\"location\":\"l\",");
		out.println("\t\t \"action\":\"unmark\",");
		out.println("\t\t \"destinations\":[{");
		out.println("\t\t\t\"location\":\"l\",");
		out.println("\t\t\t\"assignments\":[{\"ref\":\"marked\", \"value\":false}]");
		out.println("\t\t\t}]");
		out.println("\t\t}]");
		out.println("\t}");

		/* Continuing with rest of JANI */
		out.println("],");
		out.println("\"system\":{\"elements\":[");
		for (int i = 0; i < automata.length; i++) {
			out.println("\t{\"automaton\":\"" + autNames[i] + "\"},");
		}
		out.println("\t{\"automaton\":\"monitor\"}");
		out.println("\t],");
		out.println("\t\"syncs\":[");
		for (int i = 0; i < vectorLabels.length; i++) {
			String labels[] = new String[automata.length];
			for (int j = 0; j < vectorLabels[i].length; j++) {
				int aut = vectorAutomata[i][j];
				String label = vectorLabels[i][j];
				labels[aut] = label;
			}
			out.print("\t\t{\"synchronise\":[");
			for (int j = 0; j < automata.length; j++) {
				if (j > 0)
					out.print(",");
				if (labels[j] == null)
					out.print("null");
				else
					out.print("\""+labels[j]+"\"");
			}
			Integer markResult = markLabels.get(synchronizedLabels[i]);
			if (markResult != null && markResult == 0) {
				out.print(", \"unmark\"]");
			} else if (markResult != null) {
				out.print(", \"mark\"]");
			} else {
				out.print(", null]");
			}
			if (!localHides.contains(synchronizedLabels[i])) {
				out.println(",");
				out.print("\t\t \"result\":\"");
				out.print(synchronizedLabels[i]);
				out.print("\"");
			} else {
				/* Officially the empty string should
				 * work, but Storm does not accept
				 * this.
				System.out.println("");
				 */
				out.println(",");
				out.print("\t\t \"result\":\"τ\"");
			}
			if (i != vectorLabels.length - 1)
				out.println("},");
			else
				out.println("}");
		}
		out.println("\t]},");
		out.println("\"properties\": [");
		int i = 0;
		for (Property prop : props) {
			out.println("\t{\"name\":\"" + prop.name + "\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.print("\t\t\t\"op\":\"");
			switch (prop.type) {
				case STEADY_STATE:
					out.println("Smax\",");
					out.println("\t\t\t\"exp\":\""
					            + prop.variable + "\"");
					break;
				case REACHABILITY:
					out.println("Pmax\",");
					out.print("\t\t\t\"exp\":{\"op\":\"F\", \"exp\":\"" + prop.variable + "\"");
					if (Double.isFinite(prop.timeBound))
						out.print(", \"time-bounds\":{\"upper\":" + prop.timeBound + "}");
					out.println("}");
					break;
			}
			out.println("\t\t\t}");
			out.println("\t\t}");
			if (++i == props.size())
				out.println("\t}");
			else
				out.println("\t},");
		}
		/* End of properties */
		out.println("]");
		out.println("}");
	}

	private static int typeLowerBound(Object t)
	{
		if ("bool".equals(t))
			return 0;
		if ("int".equals(t))
			return 0;
		if (t instanceof Map) {
			Map tm = (Map)t;
			Object base = tm.get("base");
			if (!"int".equals(base))
				throw new IllegalArgumentException(base.toString() + " type variables are currently not supported.");
			if (!"kind".equals(tm.get("kind")))
				throw new IllegalArgumentException("Bounded type without 'bounded' kind not supported.");
			Object lb = tm.get("lower-bound");
			if (lb instanceof Long) {
				long l = (Long) lb;
				if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
					throw new IllegalArgumentException("Type bound should fit in 32 bits.");
				return (int)l;
			}
		}
		throw new IllegalArgumentException("Type " + t.toString() + " is not supported.");
	}

	private static Property parseJaniProperty(Map prop, Map<String, Number> constants)
	{
		Object nameO = prop.get("name");
		if (!(nameO instanceof String))
			throw new IllegalArgumentException("Property name should be string, not: " + nameO);
		String name = (String) nameO;
		Object expO = prop.get("expression");
		if (!(expO instanceof Map))
			throw new IllegalArgumentException("Property expression should be object, not: " + expO);
		Map expr = (Map)expO;
		if (!"filter".equals(expr.get("op")))
			throw new UnsupportedOperationException("I don't know what to do property operation '" + expr.get("op") + "'");
		Object fun = expr.get("fun");
		if (!("max".equals(fun) || "min".equals(fun) || "avg".equals("fun")))
			throw new UnsupportedOperationException("Unsupported property function: " + fun);
		if (!Collections.singletonMap("op", "initial").equals(expr.get("states")))
			throw new UnsupportedOperationException("Only properties over initial states currently supported.");
		Object valO = expr.get("values");
		if (!(valO instanceof Map))
			throw new IllegalArgumentException("Property values should be object, not: " + valO);
		Map values = (Map)valO;
		Object op = values.get("op");
		Property.Type propType = null;
		if ("Smax".equals(op) || "Smin".equals(op))
			propType = Property.Type.STEADY_STATE;
		else if ("Pmax".equals(op) || "Pmin".equals(op))
			propType = Property.Type.REACHABILITY;
		else
			throw new UnsupportedOperationException("Unsupported property operation: " + op);
		double timeBound = 0;
		String variable = null;
		expO = values.get("exp");
		if (expO instanceof String) {
			variable = (String)expO;
		} else if (expO instanceof Map) {
			expr = (Map)expO;
			if ("U".equals(expr.get("op"))) {
				if (!Boolean.TRUE.equals(expr.get("left")))
					throw new UnsupportedOperationException("Until formulae currently only supported with 'true' left operand.");
				expO = expr.get("right");
				if (!(expO instanceof String))
					throw new UnsupportedOperationException("Until formulae currently only supported with atomic (variable) right operand");
				variable = (String)expO;
			} else if ("F".equals(expr.get("op"))) {
				expO = expr.get("exp");
				if (!(expO instanceof String))
					throw new UnsupportedOperationException("Finally formulae currently only supported with atomic (variable) operand");
				variable = (String)expO;
			} else {
				throw new UnsupportedOperationException("The only currently supported formulae are variables and formulae 'F variable' or 'true U variable' (with time bound)");
			}
			Object boundO = expr.get("time-bounds");
			if (boundO == null) {
				timeBound = Double.POSITIVE_INFINITY;
			} else if (boundO instanceof Map) {
				Map bound = (Map)boundO;
				for (Object o : bound.keySet()) {
					if ("upper".equals(o)) {
						o = bound.get("upper");
						timeBound = JaniUtils.getConstantDouble(o, constants);
					} else if (!"upper-exclusive".equals(o)) {
						throw new UnsupportedOperationException("Only constant-valued upper bounds currently supported.");
					}
				}
			}
		} else {
			throw new IllegalArgumentException("Property expression should be identifier or expression.");
		}
		return new Property(propType, timeBound, variable, name);
	}

	private Set<Property> readJaniFile(String filename) throws IOException
	{
		globalVars = new TreeMap<>();
		Object jani = JSONParser.readJsonFromFile(filename);
		if (!(jani instanceof Map))
			throw new IllegalArgumentException("JANI file root is not an object.");
		Map root = (Map) jani;
		Object janiVers = root.get("jani-version");
		if (!Long.valueOf(1).equals(janiVers))
			System.err.println("Jani version != 1 may not be supported (file is version " + janiVers + ").");
		Object type = root.get("type");
		if (!"ma".equals(type))
			throw new IllegalArgumentException("Only Markov Automata are currently supported.");
		TreeMap<String, Number> constants = new TreeMap<>();
		Object constsO = root.get("constants");
		if (constsO != null) {
			if (!(constsO instanceof Object[]))
				throw new IllegalArgumentException("Constants should be an array.");
			Object[] consts = (Object[])constsO;
			for (Object cO : consts) {
				if (!(cO instanceof Map))
					throw new IllegalArgumentException("Constant found that is not an object.");
				Map c = (Map)cO;
				Object nO = c.get("name");
				if (nO == null)
					throw new IllegalArgumentException("Constant declaration with name.");
				if (!(nO instanceof String))
					throw new IllegalArgumentException("Constant declaration non-string name.");
				String name = (String) nO;
				Object vO = c.get("value");
				if (vO == null)
					throw new UnsupportedOperationException("Model parameters currently not supported.");
				if (!(vO instanceof Number || vO instanceof Boolean))
					vO = JaniUtils.getConstantDouble(vO, constants);
				if (vO instanceof Number)
					constants.put(name, (Number)vO);
				else if (vO.equals(Boolean.TRUE))
					constants.put(name, 1);
				else if (vO.equals(Boolean.FALSE))
					constants.put(name, 0);
				else
					throw new AssertionError("Number or Boolean constant value is neither Long, nor true or false.");
			}
		}
		Object variables = root.get("variables");
		if (variables != null) {
			if (!(variables instanceof Object[]))
				throw new IllegalArgumentException("Unexpected argument type of global variables list: Expected array, found " + variables.getClass());
			Object[] vars = (Object[]) variables;
			for (Object vo : vars) {
				if (vo == null)
					throw new IllegalArgumentException("Unexpected null in variable list.");
				if (!(vo instanceof Map)) {
					throw new IllegalArgumentException("Unexpected global variable entry: Expected object, found " + vo);
				}
				Map vm = (Map)vo;
				Object no = vm.get("name");
				if (!(no instanceof String))
					throw new IllegalArgumentException("Unexpected type of variable name: Expected string, found " + vo.toString());
				String name = (String)no;
				Object to = vm.get("type");
				int lowerBound = typeLowerBound(to);
				Object io = vm.get("initial-value");
				long initial = 0;
				initial = JaniUtils.getConstantLong(io, constants);
				if (initial < Integer.MIN_VALUE || initial > Integer.MAX_VALUE)
					throw new IllegalArgumentException("Initial value of variable '" + name + "' exceeds 32 bits.");
				int[] vals = new int[]{0,0,(int)initial, lowerBound};
				globalVars.put(name, vals);
			}
		}
		if (root.get("restrict-initial") != null)
			throw new IllegalArgumentException("Explicit initial states currently not supported.");
		Object autos = root.get("automata");
		if (!(autos instanceof Object[])) {
			throw new IllegalArgumentException("Unexpected JSON type of 'automata': Expected array, found " + autos);
		}
		Object[] auts = (Object[])autos;
		TreeMap<String, Automaton> declaredAuts = new TreeMap<>();
		for (Object aut : auts) {
			if (!(aut instanceof Map))
				throw new IllegalArgumentException("Automaton should be an object, not: " + aut);
			Map autm = (Map)aut;
			Object n = autm.get("name");
			if (n != null)
				declaredAuts.put(n.toString(), Automaton.fromJani(autm, constants));
		}
		Object syso = root.get("system");
		if (syso == null)
			throw new IllegalArgumentException("No system definition.");
		if (!(syso instanceof Map))
			throw new IllegalArgumentException("System declaration should be a composition, not: " + syso);
		Map sysComp = (Map)syso;
		Object elso = sysComp.get("elements");
		if (!(elso instanceof Object[]))
			throw new IllegalArgumentException("Elements of system composition should be an array.");
		Object[] elements = (Object[])elso;
		automata = new Automaton[elements.length];
		for (int i = 0; i < elements.length; i++) {
			Object element = elements[i];
			if (!(element instanceof Map))
				throw new IllegalArgumentException("Element of composition is not a JSON Object: " + element);
			Map emap = (Map)element;
			Object aut = emap.get("automaton");
			if (!(aut instanceof String))
				throw new IllegalArgumentException("Composition elements should be identifiers (of automata), not: " + aut);
			String autName = (String)aut;
			automata[i] = declaredAuts.get(autName);
			if (automata[i] == null)
				throw new IllegalArgumentException("Element declaration contains undefined automaton: " + autName);
		}
		Object synco = sysComp.get("syncs");
		if (synco == null) {
			vectorAutomata = new int[0][0];
			vectorLabels = new String[0][0];
			synchronizedLabels = new String[0];
		} else {
			if (!(synco instanceof Object[]))
				throw new IllegalArgumentException("Synchronization specification should be array, not: " + synco);
			Object[] syncs = (Object[]) synco;
			vectorAutomata = new int[syncs.length][];
			vectorLabels = new String[syncs.length][];
			synchronizedLabels = new String[syncs.length];
			for (int i = 0; i < syncs.length; i++) {
				Object syncItem = syncs[i];
				if (!(syncItem instanceof Map))
					throw new IllegalArgumentException("Synchronization item should be object, not: " + syncItem);
				Map syncMap = (Map)syncItem;
				Object syncLineO = syncMap.get("synchronise");
				if (syncLineO == null || !(syncLineO instanceof Object[]))
					throw new IllegalArgumentException("Synchronize line should be array of actions.");
				Object[] syncLine = (Object[]) syncLineO;
				int numAutomata = 0, j = 0;
				for (Object o : syncLine) {
					if (o != null)
						numAutomata++;
				}
				vectorAutomata[i] = new int[numAutomata];
				vectorLabels[i] = new String[numAutomata];
				numAutomata = 0;
				for (Object o : syncLine) {
					if (o != null) {
						vectorAutomata[i][numAutomata] = j;
						vectorLabels[i][numAutomata] = o.toString();
						numAutomata++;
					}
					j++;
				}
				Object resultAction = syncMap.get("result");
				if (resultAction != null)
					synchronizedLabels[i] = resultAction.toString();
			}
		}
		HashSet<Property> ret = new HashSet<>();
		Object propO = root.get("properties");
		if (propO == null)
			return ret;
		if (!(propO instanceof Object[]))
			throw new IllegalArgumentException("Properties should be array, not: " + propO);
		Object[] props = (Object[])propO;
		for (Object propO2 : props) {
			if (!(propO2 instanceof Map))
				throw new IllegalArgumentException("Property should be object, not: " + propO2);
			Map prop = (Map)propO2;
			try {
				Property p = parseJaniProperty(prop, constants);
				ret.add(p);
			} catch (UnsupportedOperationException e) {
				System.err.println(e.getMessage());
			}
		}
		return ret;
	}
}
