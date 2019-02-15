package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import nl.ennoruijters.util.JSONParser;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

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
	/* rateTransitions[haveRateTransitions[i]][s] is a list of the
	 * transitions from state 's' of automata 'haveRateTransitions[i]'
	 * that have a Markovian label.
	 */
	private int[] haveRateTransitions;
	private int[][][] rateTransitions;

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
	public Composition(String filename, String type,
	                   Set<Property> propertiesOut,
	                   Map<String, Number> constants)
			throws IOException
	{
		markLabels = new TreeMap<String, Integer>();
		Set<Property> props = new TreeSet<>();
		switch (type) {
			case "exp":
				readExpFile(filename);
				break;
			case "jani":
				props = readJaniFile(filename, constants);
				if (propertiesOut != null)
					propertiesOut.addAll(props);
				break;
			default:
				throw new IllegalArgumentException("Unsupported composition type");
		}
		afterParsing();
	}

	public Composition(String filename, String type) throws IOException
	{
		this(filename, type, null, Collections.emptyMap());
	}

	Composition()
	{
		markLabels = new TreeMap<String, Integer>();
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
					if (k == i)
						break;
				if (k < 0)
					continue;
				String[] labels = vLabels.get(j);
				String ilabel = labels[k];
				String label = ilabel.substring(1);
				boolean keepInteractive = false;
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
					String rateStr = parts[0].substring(1);
					String sLabel = sLabels.get(j);
					if (sLabel == null) {
						sLabel = 'r' + rateStr;
					} else if (sLabel.charAt(0) == 'r') {
						sLabel = mult(sLabel, parts[0]);
					} else {
						System.err.format("Caution: discarding synchronization result label '%s' in Markovian synchronization.");
						sLabel = 'r' + rateStr;
					}
					String[] newLabels = labels.clone();
					newLabels[k] = action;
					vAuts.add(auts);
					vLabels.add(newLabels);
					sLabels.add(sLabel);
				}
				if (!keepInteractive) {
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
			else
				oldName = 'i' + oldName;
			oldName = oldName.intern();
			if (s[1].charAt(0) == '"')
				s[1] = s[1].substring(1, s[1].length() - 1);
			String newName = s[1];
			if (newName.startsWith("rate "))
				newName = 'r' + newName.substring(5);
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
		System.err.println("Removing nondeterminism for " + from);
		for (int i = vectorAutomata.length - 1; i > 0; i--) {
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
			vectorAutomata[pos] = vectorAutomata[i];
			synchronizedLabels[pos] = synchronizedLabels[i];
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

		for (int i = 0; i < vectorAutomata.length; i++) {
			int auts[] = vectorAutomata[i];
			String labels[] = vectorLabels[i];
			boolean ok = true;
			for (int j = 0; j < auts.length; j++) {
				Set<String> acts = actions.get(auts[j]);
				if (!acts.contains(labels[j])) {
					ok = false;
					break;
				}
			}
			if (!ok) {
				int j = vectorAutomata.length - 1;
				vectorAutomata[i] = vectorAutomata[j];
				vectorLabels[i] = vectorLabels[j];
				synchronizedLabels[i] = synchronizedLabels[j];
				vectorAutomata = Arrays.copyOf(vectorAutomata, j);
				vectorLabels = Arrays.copyOf(vectorLabels, j);
				synchronizedLabels = Arrays.copyOf(synchronizedLabels, j);
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
	 * effect on any globally visible automaton except through the
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
	private Set<Integer> getDependents(int automaton, Set<Integer> globals,
	                                   Set<String> outwardTransitions)
	{
		TreeSet<Integer> ret = new TreeSet<>();
		for (int i = 0; i < automata.length; i++) {
			if (i != automaton && !globals.contains(i))
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
		if (globals.contains(automaton)) {
			/* Special case: Actions that affect global
			 * variables also need to be preserved.
			 */
			Automaton aut = automata[automaton];
			for (int i = aut.getNumStates() - 1; i >= 0; i--) {
				int j = 0;
				while (aut.getTransitionTarget(i, j) != -1) {
					Map assigns = aut.getAssignments(i, j);
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
		System.err.println("Globally visible: " + globalVisible);
		List<Set<Integer>> dependents = new ArrayList<>();
		List<Set<String>> preserveActions = new ArrayList<>();
		for (int i = 0; i < automata.length; i++) {
			Set<String> acts = new TreeSet<>();
			dependents.add(getDependents(i, globalVisible, acts));
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
			synchronizedLabels[n - 1] = "i";
		}
		boolean changed = true;
		while (changed)
			changed = removeImpossibleActions();
		buildVectorTransitions();
	}

	private void afterParsing()
	{
		removeInternalNondeterminism();
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
		haveRateTransitions = new int[0];
		rateTransitions = new int[0][][];
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
					if (l.charAt(0) != 'r')
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
				int n = haveRateTransitions.length;
				haveRateTransitions = Arrays.copyOf(haveRateTransitions, n + 1);
				haveRateTransitions[n] = i;
				rateTransitions = Arrays.copyOf(rateTransitions, n + 1);
				rateTransitions[n] = fromThisState;
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
		return ret;
	}

	public int getVarValue(String var, int[] state)
	{
		if (var.equals("marked") && globalVars.isEmpty()) {
			return state[state.length - 1];
		}
		int[] vData = globalVars.get(var);
		if (vData == null)
			throw new IllegalArgumentException("Attempt to read value of undeclared variable '" + var + "'");
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
		for (int i = 0; i < haveRateTransitions.length; i++) {
			int a = haveRateTransitions[i];
			Automaton aut = automata[a];
			int orig = from[a];
			if (rateTransitions[i][orig] == null)
				continue;
			for (int j : rateTransitions[i][orig]) {
				String l = aut.getTransitionLabel(orig, j);
				if (l == null)
					break;
				Expression g = automata[i].getTransitionGuard(orig, j);
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
			m = markLabels.get(synchronizedLabels[i]);
			if (m != null)
				target[automata.length] = m;
			for (j = 0; j < needed.length; j++) {
				if (needed[j] != null)
					target[j] = t[j];
			}
			if (assigns != null)
				doAssigns(target, assigns);
			//System.err.println("Transition possible: " + synchronizedLabels[i]);
			ret.add(new Transition(synchronizedLabels[i], target, ConstantExpression.TRUE, Map.of()));
		}

		return new LTS.TransitionSet(ret, true);
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
					if (l.charAt(0) != 'r')
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
					"\"type\":\"bool\","+
					"\"initial-value\":false}],");
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
		if (!("max".equals(fun) || "min".equals(fun) || "avg".equals(fun) || "values".equals(fun)))
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
		else if ("Emax".equals(op) || "Emin".equals(op))
			propType = Property.Type.EXPECTED_VALUE;
		else
			throw new UnsupportedOperationException("Unsupported property operation: " + op);
		double timeBound = 0;
		Expression reachTarget = null;
		if (propType == Property.Type.STEADY_STATE
		    || propType == Property.Type.REACHABILITY)
		{
			expO = values.get("exp");
		} else {
			expO = values.get("reach");
		}
		if (expO instanceof String) {
			reachTarget = Expression.fromJani(expO);
		} else if (expO instanceof Map) {
			expr = (Map)expO;
			if ("U".equals(expr.get("op"))) {
				if (!Boolean.TRUE.equals(expr.get("left")))
					throw new UnsupportedOperationException("Until formulae currently only supported with 'true' left operand.");
				expO = expr.get("right");
				reachTarget = Expression.fromJani(expO);
			} else if ("F".equals(expr.get("op"))) {
				expO = expr.get("exp");
				reachTarget = Expression.fromJani(expO);
			} else {
				try {
					reachTarget = Expression.fromJani(expO);
				} catch (UnsupportedOperationException e) {
					throw new UnsupportedOperationException("The only currently supported formulae are variables and formulae 'F variable' or 'true U variable' (with time bound); Ignoring property '" + name + "'");
				}
			}
			Object boundO = expr.get("time-bounds");
			if (boundO == null) {
				timeBound = Double.POSITIVE_INFINITY;
			} else if (boundO instanceof Map) {
				Map bound = (Map)boundO;
				for (Object o : bound.keySet()) {
					if ("upper".equals(o)) {
						o = bound.get("upper");
						timeBound = JaniUtils.getConstantDouble(o, constants).doubleValue();
					} else if (!"upper-exclusive".equals(o)) {
						throw new UnsupportedOperationException("Only constant-valued upper bounds currently supported.");
					}
				}
			}
		} else if (propType == Property.Type.EXPECTED_VALUE
		           && expO == null)
		{
			reachTarget = null;
		} else {
			throw new IllegalArgumentException("Property expression should be identifier or expression.");
		}
		if (reachTarget != null)
			reachTarget = reachTarget.simplify(constants);
		if (propType == Property.Type.EXPECTED_VALUE) {
			timeBound = Double.POSITIVE_INFINITY;
			if (values.containsKey("step-instant"))
				throw new UnsupportedOperationException("Step-bounded properties currently not supported.");
			if (values.containsKey("reward-instants"))
				throw new UnsupportedOperationException("Reward-instant queries currently not supported.");
			expO = values.get("exp");
			if (expO == null)
				throw new IllegalArgumentException("Expected-reward query without reward expression.");
			Expression transientRew = Expression.fromJani(expO);
			Expression cumulativeRew = null;
			expO = values.get("accumulate");
			if (expO != null) {
				if (!(expO instanceof Object[]))
					throw new IllegalArgumentException("Reward accumulation should be an array.");
				Object[] accs = (Object[])expO;
				if (accs.length > 1)
					throw new UnsupportedOperationException("Only time-accumulating or time-instant reward queries currently supported.");
				if (accs.length == 1) {
					if (!"time".equals(accs[0]))
						throw new UnsupportedOperationException("Only time-accumulating or time-instant reward queries currently supported.");
				}
				cumulativeRew = transientRew;
				transientRew = null;
			}
			if (values.containsKey("time-instant")) {
				Expression instant = Expression.fromJani(values.get("time-instant"));
				instant = instant.simplify(constants);
				if (instant.getReferencedVariables().size() > 0) {
					throw new UnsupportedOperationException("Time-instant expression involves variables.");
				}
				timeBound = instant.evaluate(constants).doubleValue();
			}
			return new Property(propType, timeBound, reachTarget, name, cumulativeRew, transientRew);
		}
		return new Property(propType, timeBound, reachTarget, name);
	}

	private Set<Property> readJaniFile(String filename,
	                                   Map<String, Number> overrideConsts)
			throws IOException
	{
		globalVars = new TreeMap<>();
		transientGlobals = new TreeMap<>();
		Object jani = JSONParser.readJsonFromFile(filename);
		if (!(jani instanceof Map))
			throw new IllegalArgumentException("JANI file root is not an object.");
		Map root = (Map) jani;
		Object janiVers = root.get("jani-version");
		if (!Long.valueOf(1).equals(janiVers))
			System.err.println("Jani version != 1 may not be supported (file is version " + janiVers + ").");
		Object type = root.get("type");
		if (!"ma".equals(type) && !"ctmc".equals(type))
			throw new IllegalArgumentException("Only Markov Automata are currently supported.");
		TreeMap<String, Number> constants = new TreeMap<>(overrideConsts);
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
				if (overrideConsts.containsKey(name))
					continue;
				Object vO = c.get("value");
				if (vO == null)
					throw new IllegalArgumentException("Model parameter '" + name + "' not specified.");
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
				int[] bounds = JaniUtils.typeBounds(to, constants);
				Object io = vm.get("initial-value");
				long initial = 0;
				initial = JaniUtils.getConstantLong(io, constants);
				if (initial < Integer.MIN_VALUE || initial > Integer.MAX_VALUE)
					throw new IllegalArgumentException("Initial value of variable '" + name + "' exceeds 32 bits.");
				if (initial < bounds[0])
					bounds[0] = (int)initial;
				if (initial > bounds[1])
					bounds[1] = (int)initial;
				int[] vals = new int[]{0,bounds[1],(int)initial, bounds[0]};
				if (vm.get("transient") == Boolean.TRUE)
					transientGlobals.put(name, new ConstantExpression(initial));
				globalVars.put(name, vals);
			}
		}
		if (root.get("restrict-initial") != null) {
			Object iO = root.get("restrict-initial");
			if (!(iO instanceof Map))
				throw new IllegalArgumentException("Unexpected JSON type of 'restrict-initial': Expected Object, found " + iO);
			Map restrict = (Map)iO;
			iO = restrict.get("exp");
			if (iO != Boolean.TRUE)
				throw new IllegalArgumentException("Explicit initial states currently not supported.");
		}
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
			vectorAutomata = null;
			vectorLabels = null;
			synchronizedLabels = null;
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
						vectorLabels[i][numAutomata] = 'i' + o.toString();
						numAutomata++;
					}
					j++;
				}
				Object resultAction = syncMap.get("result");
				if (resultAction != null)
					synchronizedLabels[i] = "i" + resultAction.toString();
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
