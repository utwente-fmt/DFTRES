package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import nl.ennoruijters.util.JSONParser;
import nl.utwente.ewi.fmt.EXPRES.JaniModel.JaniBaseType;
import nl.utwente.ewi.fmt.EXPRES.JaniModel.JaniType;
import nl.utwente.ewi.fmt.EXPRES.JaniModel.JaniVariable;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.BinaryExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;
import nl.utwente.ewi.fmt.EXPRES.expression.VariableExpression;

import algorithms.Simulator;

public class Composition implements MarkableLTS
{
	public static int statesExplored;
	/* Vector transitions:
	 * If vectorTransitions[v][a] is null, then vector 'v' does not
	 * depend on automaton [a]. Otherwise,
	 * vectorTransitions[v][a][s] = t indicates that vector 'v' can
	 * be taken from automaton 'a' in state 's' if and only if
	 * t >= 0, in which case t denotes the transition number.
	 */
	private int[][][] vectorTransitions;
	private int[][] vectorAutomata;
	private String[][] vectorLabels;
	private String[] synchronizedLabels;
	/** Priority vectors may be performed before any others, in any
	 * order.
	 *
	 * Priority vectors are those inserted by don't care
	 * propagation, which means we can guarantee that they can be
	 * performed before all others without loss of behaviour
	 * (assuming the don't care elimination works correctly).
	 */
	private boolean[] priorityVectors;
	private Automaton[] automata;
	private TreeSet<String> hideLabels;
	private TreeMap<String, Integer> markLabels;
	private Map<String, int[]> globalVars; /* Argument: lower bit, upper bit (both inclusive), initial value, lower bound */
	private String[] globalVarNames;
	private Map<String, Expression> transientGlobals; /* Maps the variable name to its initial value. */

	/* Rejection cache: a transition t (i.e.
	 * t<vectorAutomata.length) was last rejected by automaton
	 * rejectedFor[2*t] being in state rejectedFor[2*t+1].
	 */
	private ThreadLocal<int[]> rejectedFor;
	/* indepTransitions[haveIndepTransitions[i]][s] is a list of the
	 * transitions from state 's' of automata 'haveIndepTransitions[i]'
	 * that are known to always be taken independently (i.e.,
	 * Markovian or timed transitions).
	 */
	private int[] haveIndepTransitions;
	private int[][][] indepTransitions;
	private final static boolean VERBOSE = false;
	private final static boolean DEBUG = false;

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
		int i, pos = 0;
		for (i = state.length - 1; i >= 0; i--) {
			if (i > 0 && state[i] < 128 && state[i - 1] < 256) {
				pos++;
				i--;
			} else if (state[i] < 32767) {
				pos++;
			} else {
				pos += 3;
			}
		}
		char[] tmp = new char[pos];
		pos = 0;
		for (i = state.length - 1; i >= 0; i--) {
			if (i > 0 && state[i] < 128 && state[i - 1] < 256) {
				tmp[pos] = (char)((state[i] << 8) + state[i-1]);
				pos++;
				i--;
			} else if (state[i] < 32767) {
				tmp[pos++] = (char)(0x8000 | state[i]);
			} else {
				tmp[pos++] = 65535;
				tmp[pos++] = (char)(state[i] & 0xFFFF);
				tmp[pos++] = (char)(state[i] >>> 16);
			}
		}
		return new String(tmp);
	}

	/** Construct a composition by reading a file.
	 * @param filename The file to read.
	 * @param type The type of the given file, currently supported:
	 * exp
	 */
	public Composition(String filename, String type) throws IOException
	{
		markLabels = new TreeMap<String, Integer>();
		Set<Property> props = new TreeSet<>();
		switch (type) {
			case "exp":
				readExpFile(filename);
				break;
			default:
				throw new IllegalArgumentException("Unsupported composition type");
		}
		afterParsing();
	}

	public Composition(LTS[] automata,
	                    int[][] vectorAutomata,
	                    String[][] vectorLabels,
	                    String[] synchronizedLabels,
	                    Map<String, JaniVariable> globals,
	                    Map<String, Expression> transients)
	{
		globalVars = new TreeMap<>();
		transientGlobals = transients;
		for (Map.Entry<String, JaniVariable> variable : globals.entrySet())
		{
			String name = variable.getKey();
			JaniVariable var = variable.getValue();
			if (var.type.base != JaniBaseType.INTEGER
			    && var.type.base != JaniBaseType.BOOLEAN)
			{
				throw new UnsupportedOperationException("Unsupported variable type: " + var.type.base);
			}
			int init = JaniUtils.safeToInteger(var.initial);
			int min = JaniUtils.safeToInteger(var.type.minimum);
			int max = JaniUtils.safeToInteger(var.type.maximum);
			if (init < min)
				min = init;
			if (init > max)
				max = init;
			int[] vals = new int[]{0, max, init, min};
			globalVars.put(name, vals);
		}
		this.automata = new Automaton[automata.length];
		for (int i = 0; i < automata.length; i++) {
			LTS aut = automata[i];
			if (aut instanceof Automaton) {
				this.automata[i] = (Automaton)aut;
			} else {
				try {
					this.automata[i] = new Automaton(aut);
				} catch (ModelTooLargeException e) {
					throw new UnsupportedOperationException ("Automaton too large", e);
				}
			}
		}
		this.vectorAutomata = vectorAutomata;
		this.vectorLabels = vectorLabels;
		this.synchronizedLabels = synchronizedLabels;
		this.priorityVectors = new boolean[vectorAutomata.length];
		fixCombinedActions();
		afterParsing();
	}

	/* Create a subcomposition of only the given indices.
	 * 'syncLabs' will be set to the new label of each synchronized
	 * transitions (or null if the vector does not include any
	 * affected automaton).
	 */
	private Composition(Composition orig, int[] automata, String[] syncLabs)
	{
		automata = automata.clone();
		Arrays.sort(automata);
		this.automata = new Automaton[automata.length];
		for (int i = 0; i < automata.length; i++)
			this.automata[i] = orig.automata[automata[i]];
		List<Integer> keepVectors = new ArrayList<>();
		int i = -1;
		for (int[] auts : orig.vectorAutomata) {
			i++;
			for (int aut : auts) {
				int idx = Arrays.binarySearch(automata, aut);
				if (idx >= 0) {
					keepVectors.add(i);
					break;
				}
			}
		}
		vectorAutomata = new int[keepVectors.size()][];
		vectorLabels = new String[keepVectors.size()][];
		synchronizedLabels = new String[keepVectors.size()];
		priorityVectors = new boolean[keepVectors.size()];
		Set<String> usedLabels = new TreeSet<>();
		i = 0;
		HashMap<Set<List<Object>>, String> newLabels = new HashMap<>();
		HashMap<Set<List<Object>>, String> contexts = new HashMap<>();
		for (int v : keepVectors) {
			int[] oldAuts = orig.vectorAutomata[v];
			int size = 0;
			for (int aut : oldAuts) {
				int idx = Arrays.binarySearch(automata, aut);
				if (idx >= 0)
					size++;
			}
			int auts[] = new int[size];
			String labs[] = new String[size]; 
			int k = 0;
			Set<List<Object>> vec = new HashSet<>();
			Set<List<Object>> context = new HashSet<>();
			context.add(List.of(orig.synchronizedLabels[v]));

			for (int l = 0; l < oldAuts.length; l++) {
				int aut = oldAuts[l];
				int idx = Arrays.binarySearch(automata, aut);
				String label = orig.vectorLabels[v][l];
				if (idx >= 0) {
					auts[k] = idx;
					labs[k] = label;
					vec.add(List.of(idx, label));
					k++;
				} else {
					context.add(List.of(aut, label));
				}
			}
			String label = newLabels.get(vec);
			if (label != null) {
				syncLabs[v] = label;
				continue;
			}
			label = contexts.get(context);
			if (label == null) {
				label = orig.synchronizedLabels[v];
				label = makeUnique(usedLabels, label);
				contexts.put(context, label);
				usedLabels.add(label);
			}
			newLabels.put(vec, label);
			synchronizedLabels[i] = label;
			syncLabs[v] = label;
			vectorAutomata[i] = auts;
			vectorLabels[i] = labs;
			if (size == oldAuts.length)
				priorityVectors[i] = orig.priorityVectors[v];
			i++;
		}
		if (i != vectorAutomata.length) {
			vectorAutomata = Arrays.copyOf(vectorAutomata, i);
			vectorLabels = Arrays.copyOf(vectorLabels, i);
			synchronizedLabels = Arrays.copyOf(synchronizedLabels, i);
			priorityVectors = Arrays.copyOf(priorityVectors, i);
		}
		markLabels = new TreeMap<>();
		transientGlobals = orig.transientGlobals;
		if (!orig.globalVars.isEmpty())
			globalVars = new HashMap<>();
		else
			globalVars = Map.of();
		for (Map.Entry<String, int[]> e : orig.globalVars.entrySet())
			globalVars.put(e.getKey(), e.getValue().clone());
	}

	private Composition(Composition orig, int[] composed, Automaton aut,
	                    String[] labels)
	{
		composed = composed.clone();
		Arrays.sort(composed);
		vectorAutomata = new int[orig.vectorAutomata.length][];
		vectorLabels = new String[vectorAutomata.length][];
		int renumber[] = new int[orig.automata.length];
		int i = orig.automata.length - composed.length + 1;
		automata = new Automaton[i];
		i = 0;
		int compPos = 0;
		for (int oldPos = 0; oldPos < orig.automata.length; oldPos++) {
			if (compPos != -1 && composed[compPos] == oldPos) {
				compPos++;
				if (compPos >= composed.length)
					compPos = -1;
				renumber[oldPos] = -1;
			} else {
				renumber[oldPos] = i;
				automata[i++] = orig.automata[oldPos];
			}
		}
		automata[i] = aut;

		for (i = vectorLabels.length - 1; i >= 0; i--) {
			boolean refComp = false, mod = false;
			int size = 0;
			int[] origa = orig.vectorAutomata[i];
			for (int o : origa) {
				if (renumber[o] != o)
					mod = true;
				if (renumber[o] != -1) {
					size++;
				} else if (!refComp) {
					refComp = true;
					size++;
				}
			}
			if (!mod) {
				vectorAutomata[i] = orig.vectorAutomata[i];
				vectorLabels[i] = orig.vectorLabels[i];
				continue;
			}
			vectorAutomata[i] = new int[size];
			vectorLabels[i] = new String[size];
			int j, k = 0;
			for (j = 0; j < origa.length; j++) {
				int o = origa[j];
				if (renumber[o] != -1) {
					vectorAutomata[i][k] = renumber[o];
					vectorLabels[i][k] = orig.vectorLabels[i][j];
					vectorLabels[i][k].length();
					k++;
				}
			}
			if (refComp) {
				vectorAutomata[i][k] = automata.length - 1;
				vectorLabels[i][k] = labels[i];
				labels[i].length();
				k++;
			}
		}
		synchronizedLabels = orig.synchronizedLabels;
		priorityVectors = orig.priorityVectors;
		hideLabels = orig.hideLabels;
		markLabels = orig.markLabels;
		if (!orig.globalVars.isEmpty())
			globalVars = new HashMap<>();
		else
			globalVars = Map.of();
		for (Map.Entry<String, int[]> e : orig.globalVars.entrySet())
			globalVars.put(e.getKey(), e.getValue().clone());
		transientGlobals = orig.transientGlobals;
	}

	/* Returns the set of string in `labels' where `labels[i]' is
	 * included iff the i'th synchronization vector does not refer
	 * to automata outside `auts'.
	 * If 'visible' is set, returns only those transitions that have
	 * external effects (i.e., markLabels). Otherwise, return only
	 * those that do not (and can thus safely be removed).
	 */
	private Set<String> getInternalActions(int[] auts, String[] labels,
	                                       boolean visible)
	{
		Arrays.sort(auts);
		if (DEBUG)
			System.err.format("Getting internals for %s\n", Arrays.toString(auts));
		TreeSet<String> ret = new TreeSet<>();
		for (int i = labels.length - 1; i >= 0; i--) {
			String current = labels[i], sl = synchronizedLabels[i];
			if (current == null)
				continue;
			if ((markLabels != null && markLabels.containsKey(sl)) != visible)
				continue;
			for (int a : vectorAutomata[i]) {
				if (Arrays.binarySearch(auts, a) < 0) {
					if (DEBUG)
						System.err.format("%s not internal due to %d at %d\n", current, a, i);
					current = null;
					break;
				}
			}
			if (current != null) {
				ret.add(current);
				if (DEBUG)
					System.err.format("%s internal: %s \n", current, Arrays.toString(vectorAutomata[i]));
			}
		}
		return ret;
	}

	private Composition compose(int[] auts, long maxMem) throws ModelTooLargeException
	{
		for (Automaton a : automata)
			maxMem -= a.getMemUsed();
		String labels[] = new String[vectorLabels.length];
		if (VERBOSE) {
			System.err.println("Composing "+ Arrays.toString(auts));
			System.err.print("Sizes: [");
			boolean first = true;
			for (int a : auts) {
				if (!first)
					System.err.print(", ");
				first = false;
				System.err.print(automata[a].getNumStates());
			}
			System.err.println("]");
		}
		Composition sub = new Composition(this, auts, labels);
		sub.afterParsing();
		Set<String> internals = getInternalActions(auts, labels, false);
		Set<String> maxProg = getInternalActions(auts, labels, true);
		if (DEBUG) {
			System.err.println("Internal actions: " + internals);
			System.err.println("Max-Prog actions: " + maxProg);
			sub.printAutomata(System.err);
		}
		maxProg.addAll(internals);
		Automaton aut = new Automaton(sub, null, internals, maxProg, maxMem);
		sub = null;
		if (DEBUG)
			System.err.println("Labels: "+ Arrays.toString(labels));
		Composition ret = new Composition(this, auts, aut, labels);
		ret.removeImpossibleActions();
		if (DEBUG) {
			System.err.println("Recomposed:");
			ret.printAutomata(System.err);
		}
		return ret;
	}

	public Composition partialCompose(int stateLimit, long maxMem)
	{
		if (stateLimit <= 0)
			stateLimit = Integer.MAX_VALUE;
		if (Simulator.showProgress) {
			boolean first = true;
			System.err.print("Building composition, current sizes: [");
			for (Automaton a : automata) {
				if (!first)
					System.err.print(", ");
				first = false;
				System.err.format("%d", a.getNumStates());
			}
			System.err.println("]");
		}
		HashMap<Set<Integer>, Integer> counts = new HashMap<>();
		for (int i = vectorLabels.length - 1; i >= 0; i--) {
			Set<Integer> auts = new TreeSet<>();
			long states = 1;
			if (vectorAutomata[i].length < 2)
				continue;
			if (null == hideLabels || !hideLabels.contains(synchronizedLabels[i]))
				continue;
			for (int j : vectorAutomata[i]) {
				auts.add(j);
				states *= automata[j].getNumStates();
				if (states > stateLimit)
					break;
			}
			if (states > stateLimit)
				continue;
			Integer prev = counts.get(auts);
			if (prev == null)
				prev = 1;
			else
				prev = prev + 1;
			counts.put(auts, prev);
		}
		Map<Set<Integer>, Double> scores = new HashMap<>();
		for (Map.Entry<Set<Integer>, Integer> e : counts.entrySet()) {
			double score = e.getValue();
			score /= (1 << e.getKey().size());
			scores.put(e.getKey(), score);
		}
		counts = null;

		TreeSet<Set<Integer>> options = new TreeSet<>(
			new Comparator<Set<Integer>>() {
				public int compare(Set<Integer> s1, Set<Integer> s2) {
					Double score1 = scores.get(s1);
					Double score2 = scores.get(s2);
					return score2.compareTo(score1);
				}
			});
		options.addAll(scores.keySet());
		for (Set<Integer> best : options) {
			int auts[] = new int[best.size()];
			int i = 0;
			for (Integer s : best)
				auts[i++] = s;
			try {
				Composition ret = compose(auts, maxMem);
				ret.afterParsing();
				return ret;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return this;
	}

	public void markStatesAfter(String label, int val)
	{
		if (!globalVars.isEmpty())
			throw new UnsupportedOperationException("Combination of marking states and global variables currently not supported.");
		markLabels.put('i' + label, val);
	}

	public void hideLabel(String label)
	{
		hideLabels.add(label);
	}

	private static String mult(String r1, String r2)
	{
		if (r1.charAt(0) != 'r' || r1.charAt(0) != 'r')
			throw new IllegalArgumentException("Attempt to multiply non-rates: '" + r1 + "' and '" + r2 + "'");

		BigDecimal v1 = new BigDecimal(r1.substring(1));
		BigDecimal v2 = new BigDecimal(r2.substring(1));
		BigDecimal result = v1.multiply(v2);
		return 'r' + result.toString();
	}

	private String makeUnique(Set<String> others, String prefix) {
		if (!others.contains(prefix))
			return prefix;
		prefix += "%";
		if (!others.contains(prefix))
			return prefix;
		Random rng = new Random(prefix.hashCode() + others.hashCode());
		while (others.contains(prefix))
			prefix += (char)('A' + rng.nextInt(26));
		return prefix;
	}

	private void fixCombinedActions()
	{
		ArrayList<int[]> vAuts = new ArrayList<>();
		ArrayList<String[]> vLabels = new ArrayList<>();
		ArrayList<String> sLabels = new ArrayList<>();
		for (int i = 0; i < vectorAutomata.length; i++) {
			vAuts.add(vectorAutomata[i]);
			vLabels.add(vectorLabels[i]);
			sLabels.add(synchronizedLabels[i]);
		}
		for (int i = 0; i < automata.length; i++) {
			TreeSet<String> actions = new TreeSet<>();
			Automaton a = automata[i];
			for (int j = a.getNumStates() - 1; j >= 0; j--) {
				int k = 0;
				String l;
				while ((l = a.getTransitionLabel(j, k++)) != null) {
					if (l.charAt(0) != 'r')
						actions.add(l);
				}
			}
			for (int j = vLabels.size() - 1; j >= 0; j--) {
				int k;
				int[] auts = vAuts.get(j);
				for (k = auts.length - 1; k >= 0; k--)
					if (auts[k] == i)
						break;
				if (k < 0)
					continue;
				String[] labels = vLabels.get(j);
				String ilabel = labels[k];
				String label = ilabel.substring(1);
				boolean keepInteractive = false, match = false;
				for (String action : actions) {
					if (action.equals(ilabel)) {
						keepInteractive = true;
						continue;
					}
					if (action.charAt(0) != 'c')
						continue;
					String[] parts = action.split(";", 2);
					if (!parts[1].equals(label))
						continue;
					match = true;
					String rateStr = parts[0].substring(1);
					String sLabel = sLabels.get(j);
					if (sLabel == null) {
						sLabel = 'r' + rateStr;
					} else if (sLabel.charAt(0) == 'r') {
						sLabel = mult(sLabel, parts[0]);
					} else {
						System.err.format("Caution: discarding synchronization result label '%s' in Markovian synchronization.\n", sLabel);
						sLabel = 'r' + rateStr;
					}
					String[] newLabels = labels.clone();
					newLabels[k] = action;
					vAuts.add(auts);
					vLabels.add(newLabels);
					sLabels.add(sLabel);
				}
				if (match && !keepInteractive) {
					vAuts.remove(j);
					vLabels.remove(j);
					sLabels.remove(j);
				}
			}
		}
		vectorAutomata = vAuts.toArray(new int[0][]);
		vectorLabels = vLabels.toArray(new String[0][]);
		synchronizedLabels = sLabels.toArray(new String[0]);
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
			String oldName = s[0];
			if (oldName.startsWith("rate "))
				oldName = 'r' + oldName.substring(5);
			else if (oldName.startsWith("time "))
				oldName = 't' + oldName.substring(5);
			else
				oldName = 'i' + oldName;
			oldName = oldName.intern();
			if (s[1].charAt(0) == '"')
				s[1] = s[1].substring(1, s[1].length() - 1);
			String newName = s[1];
			if (newName.startsWith("rate "))
				newName = 'r' + newName.substring(5);
			else if (newName.startsWith("time "))
				newName = 't' + newName.substring(5);
			else
				newName = 'i' + newName;
			newName = newName.intern();
			ret.put(oldName, newName);
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
			if (label.startsWith("rate "))
				label = 'r' + label.substring(5);
			else if (label.startsWith("time "))
				label = 't' + label.substring(5);
			else
				label = 'i' + label;
			hideLabels.add(label);
		} while (line.charAt(line.length() - 1) == ',');
	}

	private void readExpFile(String filename) throws IOException
	{
		transientGlobals = Map.of();
		BufferedReader input = new BufferedReader(new FileReader(filename));
		int numAutomata = 0;
		ArrayList<int[]> vectorAutomata = new ArrayList<int[]>();
		ArrayList<String[]> vectorLabels = new ArrayList<String[]>();
		ArrayList<String> syncLabels = new ArrayList<String>();
		int i;
		globalVars = Map.of();
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
						String l = parts[i];
						if (l.startsWith("rate "))
							l = 'r'+l.substring(5);
						else if (l.startsWith("time "))
							l = 't'+l.substring(5);
						else
							l = 'i'+l;
						labels[vectorPos] = l.intern();
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
	}

	private void duplicateAutAction(int aut, String from, String to)
	{
		for (int i = vectorAutomata.length - 1; i >= 0; i--) {
			int j;
			for (j = 0; j < vectorAutomata[i].length; j++) {
				if (vectorAutomata[i][j] == aut
				    && vectorLabels[i][j].equals(from))
				{
					break;
				}
			}
			if (j == vectorAutomata[i].length)
				continue;
			int pos = vectorAutomata.length;
			vectorAutomata = Arrays.copyOf(vectorAutomata, pos + 1);
			vectorLabels = Arrays.copyOf(vectorLabels, pos + 1);
			synchronizedLabels = Arrays.copyOf(synchronizedLabels, pos + 1);
			priorityVectors = Arrays.copyOf(priorityVectors, pos + 1);
			vectorAutomata[pos] = vectorAutomata[i];
			synchronizedLabels[pos] = synchronizedLabels[i];
			priorityVectors[pos] = priorityVectors[i];
			vectorLabels[pos] = vectorLabels[i].clone();
			vectorLabels[pos][j] = to;
		}
	}

	private void removeInternalNondeterminism()
	{
		HashMap<String, String> intRenames = new HashMap<>();
		for (int i = 0; i < automata.length; i++) {
			intRenames.clear();
			Automaton newAut;
			newAut = automata[i].removeInternalNondet(intRenames);
			if (newAut == automata[i])
				continue;
			automata[i] = newAut;
			for (Map.Entry<String, String> e : intRenames.entrySet())
				duplicateAutAction(i, e.getValue(), e.getKey());
		}
	}

	private boolean removeImpossibleActions()
	{
		List<Set<String>> actions = new ArrayList<>(automata.length);
		List<Set<String>> syncs = new ArrayList<>(automata.length);
		for (Automaton a : automata) {
			actions.add(a.getAllActions(false));
			syncs.add(new TreeSet<>());
		}

		HashSet<Set<Object>> existing = new HashSet<>();
		for (int i = 0; i < vectorAutomata.length; i++) {
			int auts[] = vectorAutomata[i];
			String labels[] = vectorLabels[i];
			Set<Object> summary = new HashSet<>();
			summary.add(synchronizedLabels[i]);
			boolean ok = true;
			for (int j = 0; j < auts.length; j++) {
				Set<String> acts = actions.get(auts[j]);
				if (!acts.contains(labels[j])) {
					ok = false;
					break;
				}
				summary.add(List.of(auts[j], labels[j]));
			}
			if (!existing.add(summary))
				ok = false;
			if (!ok) {
				int j = vectorAutomata.length - 1;
				vectorAutomata[i] = vectorAutomata[j];
				vectorLabels[i] = vectorLabels[j];
				priorityVectors[i] = priorityVectors[j];
				synchronizedLabels[i] = synchronizedLabels[j];
				vectorAutomata = Arrays.copyOf(vectorAutomata, j);
				vectorLabels = Arrays.copyOf(vectorLabels, j);
				synchronizedLabels = Arrays.copyOf(synchronizedLabels, j);
				priorityVectors = Arrays.copyOf(priorityVectors, j);
				i--;
			} else {
				for (int j = 0; j < auts.length; j++)
					syncs.get(auts[j]).add(labels[j]);
			}
		}

		boolean anyChange = false;
		for (int i = 0; i < automata.length; i++) {
			Automaton orig = automata[i], reduced;
			reduced = orig.trim(syncs.get(i));
			if (reduced != orig) {
				automata[i] = reduced;
				anyChange = true;
			}
		}
		return anyChange;
	}

	/** We define a dependent automaton as one that has no
	 * effect on the globally visible automaton except through the
	 * provided automaton.
	 * In case of disconnected networks, this may include automata
	 * that have no communication with globals or with the specified
	 * automaton. This is a rather strange definition of dependent
	 * and we should probably just remove such automata, but it does
	 * no harm to include them.
	 *
	 * @param outwardActions Which interactive transitions of the
	 * the automaton communicate outside of the dependents.
	 */
	private Set<Integer> getDependents(int automaton, int global,
	                                   Set<String> outwardTransitions)
	{
		TreeSet<Integer> ret = new TreeSet<>();
		for (int i = 0; i < automata.length; i++) {
			if (i != automaton && i != global)
				ret.add(i);
		}
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = 0; i < vectorAutomata.length; i++) {
				/* Check if this synchronization links
				 * possible dependents and globals.
				 */
				boolean link = false;
				int[] auts = vectorAutomata[i];
				for (int j : auts) {
					if (!ret.contains(j) && j != automaton){
						link = true;
						break;
					}
				}
				if (link) {
					for (int aut : auts) {
						changed |= ret.remove(aut);
					}
				}
			}
		}
		if (automaton == global) {
			/* Special case: Actions that affect global
			 * variables also need to be preserved.
			 */
			Automaton aut = automata[automaton];
			for (int i = aut.getNumStates() - 1; i >= 0; i--) {
				int j = 0;
				while (aut.getTransitionTarget(i, j) != -1) {
					Map<String, Expression> assigns = aut.getAssignments(i, j);
					if (assigns != null
					    && !assigns.isEmpty())
					{
						outwardTransitions.add(aut.getTransitionLabel(i, j));
					}
					j++;
				}
			}
		}

		for (int i = 0; i < vectorAutomata.length; i++) {
			int[] auts = vectorAutomata[i];
			String[] labs = vectorLabels[i];
			int ourIdx = -1;
			boolean outsideDependents = false;
			for (int j = 0; j < auts.length; j++) {
				if (auts[j] == automaton) {
					ourIdx = j;
					continue;
				}
				if (!ret.contains(auts[j]))
					outsideDependents = true;
			}
			if (markLabels.containsKey(synchronizedLabels[i]))
				outsideDependents = true;
			if (ourIdx != -1 && outsideDependents)
				outwardTransitions.add(labs[ourIdx]);
		}
		return ret;
	}

	public void addDontCares()
	{
		/* Build dependency sets: For each automaton A we identify
		 * those automata B that communicate with A (directly or
		 * indirectly) but not with any globally visible
		 * automaton (i.e., those with assignments or
		 * participating in marked actions).
		 */
		Set<Integer> globalVisible = new TreeSet<>();
		for (int i = 0; i < automata.length; i++) {
			if (automata[i].hasAnyAssignments())
				globalVisible.add(i);
		}
		for (String l : markLabels.keySet()) {
			for (int i = 0; i < synchronizedLabels.length; i++) {
				if (!synchronizedLabels[i].equals(l))
					continue;
				for (int aut : vectorAutomata[i])
					globalVisible.add(aut);
			}
		}
		List<Set<Integer>> dependents = new ArrayList<>();
		List<Set<String>> preserveActions = new ArrayList<>();
		for (int i = 0; i < automata.length; i++) {
			Set<String> acts = new TreeSet<>();
			Set<Integer> deps = new TreeSet<>();
			for (Integer g : globalVisible) {
				deps.addAll(getDependents(i, g, acts));
			}
			dependents.add(deps);
			preserveActions.add(acts);
		}

		/* Transitive dependencies should not be kept, as they
		 * block the synchronization (or would require a large
		 * number of subset synchronizations).
		 */
		int m = 0;
		for (Set<Integer> ds : dependents) {
			Iterator<Integer> it = ds.iterator();
			while (it.hasNext()) {
				Integer s = it.next();
				for (Integer t : ds) {
					if (dependents.get(t).contains(s)) {
						it.remove();
						break;
					}
				}
			}
			m++;
		}

		/* Next, every automaton gets 'no longer cared about'
		 * signal. This signal indicates that the automaton no
		 * longer has a path to any globally visible automaton,
		 * and therefore should not exhibit any interesting
		 * behaviour. The only remaining behaviour is to
		 * continue responding to the 'no longer cared about'
		 * signal as larger irrelevant subsets may appear in the
		 * future.
		 */

		/* Pick a signal that don't exist yet */
		Set<String> existingLabels = getAllTransitionLabels();
		String dontCare = "iDONTCARE";
		int i = 0;
		while (existingLabels.contains(dontCare))
			dontCare = "iDONTCARE_" + (i++);
		String notCared = "iNOTCARED";
		i = 0;
		while (existingLabels.contains(notCared))
			notCared = "iNOTCARED_" + (i++);
		String dcLabel = makeUnique(Set.of(synchronizedLabels), "iDC");
		hideLabels.add(dcLabel);

		for (i = 0; i < automata.length; i++) {
			automata[i] = automata[i].addDontCares(dontCare,
					notCared,
					preserveActions.get(i));
		}
		/* Finally, add synchronizations such that once an automaton
		 * find all automata that depend on it have stopped
		 * caring, it goes to its own don't care state.
		 */
		for (i = 0; i < automata.length; i++) {
			/* Number of signals in synchronization vector:
			 * 1 per automaton dependent on us, plus 1 for
			 * ourselves.
			 */
			if (globalVisible.contains(i))
				continue;
			int n = 1;
			for (Set<Integer> depends : dependents) {
				if (depends.contains(i))
					n++;
			}
			int auts[] = new int[n];
			String labs[] = new String[n];
			auts[0] = i;
			labs[0] = notCared;
			n = 1;
			for (int j = 0; j < dependents.size(); j++) {
				Set<Integer> depends = dependents.get(j);
				if (depends.contains(i)) {
					auts[n] = j;
					labs[n] = dontCare;
					n++;
				}
			}
			n = vectorAutomata.length + 1;
			vectorAutomata = Arrays.copyOf(vectorAutomata, n);
			vectorLabels = Arrays.copyOf(vectorLabels, n);
			synchronizedLabels = Arrays.copyOf(synchronizedLabels, n);
			vectorAutomata[n - 1] = auts;
			vectorLabels[n - 1] = labs;
			synchronizedLabels[n - 1] = dcLabel;
			priorityVectors = Arrays.copyOf(priorityVectors, n);
			priorityVectors[n - 1] = true;
		}
		boolean changed = true;
		while (changed)
			changed = removeImpossibleActions();
		buildVectorTransitions();
	}

	private void afterParsing()
	{
		removeInternalNondeterminism();
		if (priorityVectors == null)
			priorityVectors = new boolean[vectorAutomata.length];
		boolean changed = true;
		while (changed) {
			changed = removeImpossibleActions();
		}
		rejectedFor = new ThreadLocal<int[]>();
		TreeMap<String, Integer> mins = new TreeMap<>();
		TreeMap<String, Integer> maxs = new TreeMap<>();
		TreeSet<String> unspecifieds = new TreeSet<>();
		globalVarNames = new String[globalVars.size()];
		int i = 0;
		for (Map.Entry<String, int[]> e : globalVars.entrySet()) {
			mins.put(e.getKey(), e.getValue()[2]);
			maxs.put(e.getKey(), e.getValue()[2]);
			globalVarNames[i++] = e.getKey();
		}
		for (Automaton a : automata) {
			for (i = a.getNumStates() - 1; i >= 0; i--) {
				int n = 0;
				Map<String, Expression> assigns;
				while ( (assigns = a.getAssignments(i, n)) != null) {
					for (Map.Entry<String, Expression> e : assigns.entrySet()) {
						String v = e.getKey();
						Expression exp = e.getValue();
						Number eval = exp.evaluate(Map.of());
						if (eval == null) {
							unspecifieds.add(v);
							continue;
						}
						int val = eval.intValue();
						Integer cMin = mins.get(v);
						if (globalVars.containsKey(v) &&
								(cMin == null || val < cMin)) {
							mins.put(v, val);
						}
						Integer cMax = maxs.get(v);
						if (globalVars.containsKey(v) &&
								(cMax == null || val > cMax)) {
							maxs.put(v, val);
						}
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
			if (data == null)
				throw new IllegalArgumentException("Variable "+v+" not found in global scope");
			if (unspecifieds.contains(v)) {
				min = data[3];
				max = data[1];
			}
			data[3] = min;
			long range = max;
			range -= min;
			int bits = 0;
			while (range > 0) {
				bits++;
				range >>>= 1;
			}
			if (bits > 32)
				throw new IllegalArgumentException("Out of bits for global variables");
			if (uBit / 32 != (uBit + bits) / 32)
				uBit = (uBit / 32 + 1) * 32;
			data[0] = uBit;
			uBit += bits;
			data[1] = uBit - 1;
			if (bits == 0)
				data[0] = data[1] = 0;
		}
		buildVectorTransitions();
	}

	private void buildVectorTransitions() {
		int vt[][][] = new int[vectorLabels.length][automata.length][];
		vectorTransitions = vt;
		for (int v = 0; v < vectorLabels.length; v++) {
			for (int i = 0; i < vectorAutomata[v].length; i++) {
				int a = vectorAutomata[v][i];
				Automaton aut = automata[a];
				String needed = vectorLabels[v][i];
				vt[v][a] = new int[aut.getNumStates()];
				Arrays.fill(vt[v][a], -1);
				for (int s = aut.getNumStates() - 1; s >= 0; s--) {
					int j = 0;
					String l = aut.getTransitionLabel(s, 0);
					while (l != null) {
						if (l.equals(needed)) {
							vt[v][a][s] = j;
							break;
						}
						l = aut.getTransitionLabel(s, ++j);
					}
				}
			}
		}

		/* Build rate transition list */
		haveIndepTransitions = new int[0];
		indepTransitions = new int[0][][];
		ArrayList<int[]> mStates = new ArrayList<>();
		for (int i = 0; i < automata.length; i++) {
			Automaton aut = automata[i];
			int[][] fromThisState = null;
			for (int j = 0; j < aut.getNumStates(); j++) {
				int[] mTransitions = null;
				for (int k = 0; true; k++) {
					String l = aut.getTransitionLabel(j, k);
					if (l == null)
						break;
					char type = l.charAt(0);
					if (type != 'r' && type != 't')
						continue;
					if (mTransitions == null) {
						mTransitions = new int[]{k};
					} else {
						mTransitions = Arrays.copyOf(mTransitions, mTransitions.length + 1);
						mTransitions[mTransitions.length - 1] = k;
					}
				}
				if (mTransitions != null) {
					if (fromThisState == null)
						fromThisState = new int[aut.getNumStates()][];
					fromThisState[j] = mTransitions;
				}
			}
			if (fromThisState != null) {
				int n = haveIndepTransitions.length;
				haveIndepTransitions = Arrays.copyOf(haveIndepTransitions, n + 1);
				haveIndepTransitions[n] = i;
				indepTransitions = Arrays.copyOf(indepTransitions, n + 1);
				indepTransitions[n] = fromThisState;
			}
		}
	}

	/** @return The initial state of this composed automaton */
	public int[] getInitialState() {
		int ret[] = new int[stateSize()];
		for (int i = automata.length - 1; i >= 0; i--)
			ret[i] = automata[i].initState;
		if (!globalVars.isEmpty()) {
			TreeMap<String, Expression> assigns = new TreeMap<>();
			for (String n : globalVars.keySet()) {
				int init = globalVars.get(n)[2];
				assigns.put(n, new ConstantExpression(init));
			}
			doAssigns(ret, assigns);
		}
		return ret;
	}

	public int stateSize()
	{
		int size = automata.length;
		if (globalVars.isEmpty()) {
			size += 1;
		} else {
			int maxBit = 0;
			for (int[] vals : globalVars.values()) {
				if (vals[1] > maxBit)
					maxBit = vals[1];
			}
			size += maxBit / 32 + 1;
		}
		return size;
	}

	public Map<String, Integer> getVarValues(int[] state)
	{
		if (globalVars.isEmpty())
			return Map.of("marked", state[state.length - 1]);
		if (globalVars.size() == 1) {
			String v = globalVarNames[0];
			int vData[] = globalVars.get(v);
			int word = vData[0] / 32 + automata.length;
			int vals = state[word];
			int lowBit = vData[0] % 32;
			int val = vals >> lowBit;
			val &= ((-1) >>> (31 - (vData[1] - vData[0])));
			val += vData[3];
			return Map.of(v, val);
		}
		Map<String, Integer> ret;
		if (globalVarNames.length > 8)
			ret = new HashMap<>();
		else
			ret = new TreeMap<>();
		for (String name : globalVarNames) {
			int vData[] = globalVars.get(name);
			int word = vData[0] / 32 + automata.length;
			int vals = state[word];
			int lowBit = vData[0] % 32;
			int val = vals >> lowBit;
			val &= ((-1) >>> (31 - (vData[1] - vData[0])));
			val += vData[3];
			ret.put(name, val);
		}
		for (Map.Entry<String, Expression> trn : transientGlobals.entrySet()) {
			Number val = trn.getValue().evaluate(this, state);
			if (val instanceof Integer)
				ret.put(trn.getKey(), (Integer) val);
			else
				throw new RuntimeException("Transient variable '" + trn.getKey() + "' has non-integer value: " + val);
		}
		return ret;
	}

	public int getVarValue(String var, int[] state)
	{
		if (var.equals("marked") && globalVars.isEmpty()) {
			return state[state.length - 1];
		}
		int[] vData = globalVars.get(var);
		if (vData == null) {
			Expression expr = transientGlobals.get(var);
			if (expr == null)
				throw new IllegalArgumentException("Attempt to read value of undeclared variable '" + var + "'");
			Number ret = expr.evaluate(this, state);
			if (ret instanceof Integer)
				return (Integer)ret;
			else
				throw new RuntimeException("Transient variable '" + var + "' has non-integer value: " + ret);
		}
		int word = vData[0] / 32 + automata.length;
		int vals = state[word];
		int lowBit = vData[0] % 32;
		int val = vals >> lowBit;
		val &= ((-1) >>> (31 - (vData[1] - vData[0])));
		val += vData[3];
		return val;
	}

	private void doAssigns(int[] state, Map<String, Expression> assigns)
	{
		for (String name : assigns.keySet()) {
			Expression exp = assigns.get(name);
			int val = exp.evaluate(this, state).intValue();
			int vData[] = globalVars.get(name);
			int word = vData[0] / 32 + automata.length;
			int vals = state[word];
			int lowBit = vData[0] % 32, highBit = vData[1] % 32;
			val -= vData[3]; /* Remove lower bound */
			if (val < 0)
				throw new IllegalArgumentException("Value " + exp.evaluate(getVarValues(state)).intValue() + " below lower bound (" + vData[2] + ") of variable " + name);
			int max = (1 << (vData[1] - vData[0] + 1)) - 1;
			if (val > max) {
				System.err.println("Current values: " + getVarValues(state));
				System.err.println("Bits: " + vData[0] + " - " + vData[1]);
				throw new IllegalArgumentException("Value " + exp.evaluate(getVarValues(state)).intValue() + " exceeds upper bound (" + max + ") of variable " + name + " in " + exp.toString());
			}
			int origVals = state[word];
			val <<= lowBit;
			int mask = -1;
			mask <<= lowBit;
			mask &= ((-1) >>> (31 - highBit));
			origVals &= ~mask;
			state[word] = origVals | val;
		}
	}

	private Set<LTS.Transition> getUnsynchronizedTransitions(int[] from)
	{
		Map<String, Integer> values = getVarValues(from);
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		for (int i = 0; i < automata.length; i++) {
			for (int j = 0; true; j++) {
				String l = automata[i].getTransitionLabel(from[i], j);
				if (l == null)
					break;
				Expression g = automata[i].getTransitionGuard(from[i], j);
				boolean guardOK = true;
				if (g != null) {
					Number v = g.evaluate(values);
					if (v == null) {
						System.err.println("Values: " + getVarValues(from));
						throw new UnsupportedOperationException("Unevaluatable expression: " + g.toString());
					}
					guardOK = v.doubleValue() != 0;
				}
				if (!guardOK)
					continue;
				int[] target = Arrays.copyOf(from, from.length);
				target[i] = automata[i].getTransitionTarget(from[i], j);
				Map<String, Expression> assigns = automata[i].getAssignments(from[i], j);
				doAssigns(target, transientGlobals);
				if (assigns != null)
					doAssigns(target, assigns);
				ret.add(new LTS.Transition(l, target, ConstantExpression.TRUE, Map.of()));
			}
		}
		return ret;
	}

	public Set<LTS.Transition> getTransitions(int[] from)
	{
		if (vectorAutomata == null)
			return getUnsynchronizedTransitions(from);
		ArrayList<LTS.Transition> ret = new ArrayList<LTS.Transition>();
		int t[] = new int[automata.length];

		/* First, 'rate' transitions are always taken
		 * unsynchronized.
		 */
		for (int i = 0; i < haveIndepTransitions.length; i++) {
			int a = haveIndepTransitions[i];
			Automaton aut = automata[a];
			int orig = from[a];
			if (indepTransitions[i][orig] == null)
				continue;
			for (int j : indepTransitions[i][orig]) {
				String l = aut.getTransitionLabel(orig, j);
				if (l == null)
					break;
				Expression g = aut.getTransitionGuard(orig, j);
				boolean guardOK = true;
				if (g != null) {
					Number v = g.evaluate(this, from);
					if (v == null) {
						System.err.println("Values: " + getVarValues(from));
						throw new UnsupportedOperationException("Unevaluatable expression: " + g.toString());
					}
					guardOK = v.doubleValue() != 0;
				}
				if (!guardOK)
					continue;
				int[] target = from.clone();
				target[a] = aut.getTransitionTarget(orig, j);
				Map<String, Expression> assigns = aut.getAssignments(orig, j);
				doAssigns(target, transientGlobals);
				if (assigns != null)
					doAssigns(target, assigns);
				ret.add(new LTS.Transition(l, target, ConstantExpression.TRUE, Map.of()));
			}
		}

		/* Initialize the rejection cache */
		int[] rejCache = rejectedFor.get();
		if (rejCache == null) {
			rejCache = new int[2*vectorAutomata.length];
			for (int k = 0; k < vectorAutomata.length; k++)
				rejCache[2*k+1] = -1;
			rejectedFor.set(rejCache);
		}

		for (int i = 0; i < vectorTransitions.length; i++) {
			/* Check the rejection cache */
			if (from[rejCache[2*i]] == rejCache[2*i+1])
				continue;

			int[] target;
			TreeMap<String, Expression> assigns;
			if (!transientGlobals.isEmpty())
				assigns = new TreeMap<>(transientGlobals);
			else
				assigns = null;

			int needed[][] = vectorTransitions[i];

			int j;
			for (j = needed.length - 1; j >= 0; j--) {
				if (needed[j] == null)
					continue;
				int origin = from[j];
				int k = needed[j][origin];
				if (k == -1) {
					rejCache[2*i] = j;
					rejCache[2*i+1] = origin;
					//System.err.println("Rejecting transition " + synchronizedLabels[i] + ": need " + neededL[j] + " at " + needed[j]);
					break;
				}
				Automaton a = automata[j];
				t[j] = a.getTransitionTarget(origin, k);
				Expression g = a.getTransitionGuard(origin, k);
				if (g != null && g != ConstantExpression.TRUE) {
					Number v = g.evaluate(getVarValues(from));
					if (v == null)
						throw new UnsupportedOperationException("Unable to evaluate guard: " + g);
					/* Don't cache since the cache
					 * doesn't include global
					 * variable values.
					 */
					if (v.doubleValue() == 0)
						break;
				}
				Map<String, Expression> as = a.getAssignments(origin, k);
				if (as != null) {
					if (assigns == null)
						assigns = new TreeMap<>(as);
					else
						assigns.putAll(as);
				}
			}
			if (j >= 0)
				continue;

			/* Transition possible, we assume no internal
			 * nondeterminism.
			 */
			Integer m;
			target = from.clone();
			if (null != markLabels) {
				m = markLabels.get(synchronizedLabels[i]);
				if (m != null)
					target[automata.length] = m;
			}
			for (j = 0; j < needed.length; j++) {
				if (needed[j] != null)
					target[j] = t[j];
			}
			if (assigns != null)
				doAssigns(target, assigns);
			//System.err.println("Transition possible: " + synchronizedLabels[i]);
			Transition nt = new Transition(synchronizedLabels[i], target, ConstantExpression.TRUE, Map.of());
			if (priorityVectors[i])
				return Collections.singleton(nt);
			ret.add(nt);
		}

		return new LTS.TransitionSet(ret, true);
	}

	public void printAutomata(PrintStream out)
	{
		for (int i = 0; i < synchronizedLabels.length; i++) {
			boolean first = true;
			for (int j = 0; j < vectorLabels[i].length; j++) {
				out.format("%s[%d: %s]", first ? "" : " * ", vectorAutomata[i][j], vectorLabels[i][j]);
				first = false;
			}
			out.format(" -> %s\n", synchronizedLabels[i]);
		}
		for (Automaton a : automata)
			out.println(a);
	}

	private TreeSet<String> getAllTransitionLabels()
	{
		TreeSet<String> ret = new TreeSet<>();
		for (Automaton a : automata) {
			for (int i = a.getNumStates() - 1; i >= 0; i--) {
				int j = 0;
				String l;
				while ((l = a.getTransitionLabel(i, j++)) != null) {
					char type = l.charAt(0);
					if (type != 'r' && type != 't')
						ret.add(l);
				}
			}
		}
		return ret;
	}

	public void printJani(String name, PrintStream out, Set<Property> props)
	{
		TreeSet<String> localHides;
		if (hideLabels != null)
			localHides = new TreeSet<>(hideLabels);
		else
			localHides = new TreeSet<>();
		/* Add markLabels to the hide set, as the marking
		 * (presumably) captures any visible effects of the
		 * transition. */
		localHides.addAll(markLabels.keySet());
		out.println("{\"jani-version\":1,");
		out.println("\"name\":\""+name+"\",");
		out.println("\"type\":\"ma\",");
		if (!markLabels.isEmpty()) {
			out.println("\"variables\":["+
					"{\"name\":\"marked\","+
					"\"type\":{\"kind\":\"bounded\",\"base\":\"int\",\"upper-bound\":1},"+
					"\"initial-value\":0}],");
			if (props.isEmpty()) {
				out.println("\"constants\":[");
				out.println("\t{\"name\":\"T\", \"type\":\"real\"},");
				out.println("\t{\"name\":\"L\", \"type\":\"real\"}],");
			}
		} else {
			out.println("\"variables\":[");
			boolean first = true;
			for (String var : globalVars.keySet()) {
				if (!first)
					out.println(",");
				first = false;
				int[] data = globalVars.get(var);
				int uBound = data[3] + (1 << (data[1] - data[0])) - 1;
				out.print("\t{\"name\":\"" + var + "\", "
					+ (transientGlobals.containsKey(var)
				               ? "\"transient\": true, "
					       : "")
					+ "\"type\":{\"base\":\"int\", "
					+ "\"kind\":\"bounded\", "
					+ "\"lower-bound\":" + data[3] + ", "
					+ "\"upper-bound\":" + uBound + "}, "
					+ "\"initial-value\":" + data[2] + "}");
			}
			out.println("\n],");
		}
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
		actions.add("iτ"); /* Needed for Storm compatibility */
		for (String[] ls : vectorLabels) {
			for (String l : ls)
				actions.add(l);
		}
		actions.addAll(getAllTransitionLabels());
		actions.add("imark");
		actions.add("iunmark");
		boolean first = true;
		for (String l : actions) {
			if (!first)
				out.println(",");
			first = false;
			l = l.substring(1);
			out.print("\t{\"name\":\""+l+"\"}");
		}
		out.println("\n],");
		out.println("\"automata\":[");
		HashMap<Automaton, Integer> outputAutomata = new HashMap<>();
		String autNames[] = new String[automata.length];
		for (int i = 0; i < automata.length; i++) {
			Integer num = outputAutomata.get(automata[i]);
			if (num == null) {
				if (i > 0)
					out.println(",");
				num = outputAutomata.size();
				outputAutomata.put(automata[i], num);
				automata[i].printJaniAutomaton("aut"+num, out);
			}
			autNames[i] = "aut" + num;
		}
		if (!markLabels.isEmpty()) {
			/* Monitor automaton */
			out.println(",");
			out.println("\t{\"name\":\"monitor\",");
			out.println("\t \"locations\":[{\"name\":\"l\"}],");
			out.println("\t \"initial-locations\":[\"l\"],");
			out.println("\t \"edges\":[");
			out.println("\t\t{\"location\":\"l\",");
			out.println("\t\t \"action\":\"mark\",");
			out.println("\t\t \"destinations\":[{");
			out.println("\t\t\t\"location\":\"l\",");
			out.println("\t\t\t\"assignments\":[{\"ref\":\"marked\", \"value\":1}]");
			out.println("\t\t\t}]},");
			out.println("\t\t{\"location\":\"l\",");
			out.println("\t\t \"action\":\"unmark\",");
			out.println("\t\t \"destinations\":[{");
			out.println("\t\t\t\"location\":\"l\",");
			out.println("\t\t\t\"assignments\":[{\"ref\":\"marked\", \"value\":0}]");
			out.println("\t\t\t}]");
			out.println("\t\t}]");
			out.println("\t}");
		} else {
			out.println();
		}
		/* Continuing with rest of JANI */
		out.println("],");
		out.println("\"system\":{\"elements\":[");
		for (int i = 0; i < automata.length; i++) {
			if (i != 0)
				out.println(",");
			out.print("\t{\"automaton\":\"" + autNames[i] + "\"}");
		}
		if (!markLabels.isEmpty())
			out.println(",\n\t{\"automaton\":\"monitor\"}");
		else
			out.println();
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
					out.print("\""+labels[j].substring(1)+"\"");
			}
			if (!markLabels.isEmpty()) {
				Integer markResult = markLabels.get(synchronizedLabels[i]);
				if (markResult != null && markResult == 0) {
					out.print(", \"unmark\"]");
				} else if (markResult != null) {
					out.print(", \"mark\"]");
				} else {
					out.print(", null]");
				}
			} else {
				out.print("]");
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
		if (props.isEmpty() && !markLabels.isEmpty()) {
			/* Special cases as we write min/max. */
			props = new HashSet<>();
			Property av = new Property(Property.Type.STEADY_STATE, new BinaryExpression(BinaryExpression.Operator.GREATER, new VariableExpression("marked"), ConstantExpression.FALSE), null, "Unavailability");
			props.add(av);
			Property mttf = new Property(Property.Type.EXPECTED_VALUE, Double.POSITIVE_INFINITY, new BinaryExpression(BinaryExpression.Operator.NOT_EQUALS, new VariableExpression("marked"), ConstantExpression.FALSE), null, "MTTF", new ConstantExpression(1), null);
			props.add(mttf);
			out.println("\t{\"name\":\"TBLmax_Unreliability\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Pmax\",");
			out.println("\t\t\t\"exp\":{\"op\":\"U\", \"left\":true, \"right\":{\"op\":\">\", \"left\":\"marked\", \"right\":0}, \"time-bounds\":{\"upper\": \"T\", \"lower\":\"L\"}}");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"TBmax_Unreliability\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Pmax\",");
			out.println("\t\t\t\"exp\":{\"op\":\"U\", \"left\":true, \"right\":{\"op\":\">\", \"left\":\"marked\", \"right\":0}, \"time-bounds\":{\"upper\": \"T\"}}");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"TBmin_Unreliability\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Pmin\",");
			out.println("\t\t\t\"exp\":{\"op\":\"U\", \"left\":true, \"right\":{\"op\":\">\", \"left\":\"marked\", \"right\":0}, \"time-bounds\":{\"upper\": \"T\", \"lower\":\"L\"}}");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"UBmax_Unreliability\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Pmax\",");
			out.println("\t\t\t\"exp\":{\"op\":\"U\", \"left\":true, \"right\":{\"op\":\">\", \"left\":\"marked\", \"right\":0}}");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"UBmin_Unreliability\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Pmin\",");
			out.println("\t\t\t\"exp\":{\"op\":\"U\", \"left\":true, \"right\":{\"op\":\">\", \"left\":\"marked\", \"right\":0}}");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"max_Unavailability\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Smax\",");
			out.println("\t\t\t\"exp\":{\"op\":\">\", \"left\":\"marked\", \"right\":0}");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"min_Unavailability\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Smin\",");
			out.println("\t\t\t\"exp\":{\"op\":\">\", \"left\":\"marked\", \"right\":0}");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"max_MTTF\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Emax\",");
			out.println("\t\t\t\"accumulate\": [\"time\"],");
			out.println("\t\t\t\"reach\":{\"op\":\">\", \"left\":\"marked\", \"right\":0},");
			out.println("\t\t\t\"exp\":1");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
			out.println("\t{\"name\":\"min_MTTF\",");
			out.println("\t \"expression\":{");
			out.println("\t\t\"fun\":\"max\",");
			out.println("\t\t\"op\":\"filter\",");
			out.println("\t\t\"states\":{\"op\":\"initial\"},");
			out.println("\t\t\"values\":{");
			out.println("\t\t\t\"op\":\"Emin\",");
			out.println("\t\t\t\"accumulate\": [\"time\"],");
			out.println("\t\t\t\"reach\":{\"op\":\">\", \"left\":\"marked\", \"right\":0},");
			out.println("\t\t\t\"exp\":1");
			out.println("\t\t\t}");
			out.println("\t\t}");
			out.println("\t},");
		}
		for (Property prop : props) {
			prop.printJani(out, 1);
			if (++i == props.size())
				out.println();
			else
				out.println(",");
		}
		/* End of properties */
		out.println("]");
		out.println("}");
	}
}
