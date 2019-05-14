package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public class Automaton implements LTS {
	/** The initial state of the automaton */
	public final int initState;
	/* targets[i][j] denotes the target state of the j'th transition
	 * from state i. labels[i][j] denotes the label of that
	 * transition. */
	private int targets[][];
	private String labels[][];
	private Expression guards[][];
	private Map<String, Expression> assignments[][];
	private Map<String, Integer> transitions[];
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

	/** Generate an explicit-state automaton out of the given LTS,
	 * possibly with a restricted action set.
	 *
	 * The automaton will replicate all behaviour of the LTS,
	 * made explicit as much as possible (e.g., guards will not
	 * refer to local variables anymore, since the variables are now
	 * part of the state).
	 *
	 * If a set of permitted actions is provided, only transitions
	 * will be kept that are either Markovian or contained in the
	 * permitted action set.
	 *
	 * @param system	The system to make explicit.
	 * @param permitted	The set of permitted actions.
	 */
	public Automaton(LTS system, Set<String> permitted)
	{
		HashMap<LTS.StateWrapper, Integer> states = new HashMap<>();
		ArrayDeque<LTS.StateWrapper> queue = new ArrayDeque<>();
		queue.add(new LTS.StateWrapper(system.getInitialState()));
		states.put(queue.getFirst(), 0);
		targets = new int[1][];
		labels = new String[1][];
		assignments = createAssignmentArray(1);
		boolean anyHasAssignments = false;
		while (!queue.isEmpty()) {
			if (targets.length < states.size()) {
				int n = states.size();
				targets = Arrays.copyOf(targets, n);
				labels = Arrays.copyOf(labels, n);
				assignments = Arrays.copyOf(assignments, n);
			}
			LTS.StateWrapper cur = queue.poll();
			int[] state = cur.state;
			int num = states.get(cur);
			Set<LTS.Transition> ts;
			try {
				ts = system.getTransitions(state);
			} catch (NondeterminismException e) {
				throw new UnsupportedOperationException(e);
			}
			targets[num] = new int[ts.size()];
			labels[num] = new String[ts.size()];
			assignments[num] = Arrays.copyOf(assignments[0], ts.size());
			int i = 0;
			for (LTS.Transition t : ts) {
				if (permitted != null
				    && t.label.charAt(0) != 'r'
				    && !permitted.contains(t.label))
				{
					continue;
				}
				LTS.StateWrapper tgt = new LTS.StateWrapper(t.target);
				Integer tgtNum = states.get(tgt);
				if (tgtNum == null) {
					tgtNum = states.size();
					states.put(tgt, tgtNum);
					queue.add(tgt);
				}
				targets[num][i] = tgtNum;
				labels[num][i] = t.label;
				Expression guard = t.guard;
				try {
					guard.evaluate(Map.of());
				} catch (IllegalArgumentException e) {
					System.err.println("Error evaluating: " + guard);
					throw e;
				}
				if (guard.evaluate(Map.of()) != null) {
					if (guard.evaluate(Map.of()).doubleValue() == 0)
						throw new IllegalArgumentException("Symbolic automaton returned transition with FALSE guard.");
				} else {
					if (guards == null)
						guards = new Expression[num][];
					if (guards.length <= num)
						guards = Arrays.copyOf(guards, num + 1);
					if (guards[num] == null)
						guards[num] = new Expression[ts.size()];
					guards[num][i] = t.guard;
				}
				assignments[num][i] = t.assignments;
				if (t.assignments != null && !t.assignments.isEmpty())
				{
					anyHasAssignments = true;
				}
				i++;
			}
			if (i != labels[num].length) {
				labels[num] = Arrays.copyOf(labels[num], i);
				targets[num] = Arrays.copyOf(targets[num], i);
				assignments[num] = Arrays.copyOf(assignments[num], i);
				if (guards != null && guards[num] != null)
					guards[num] = Arrays.copyOf(guards[num], i);
			}
		}
		if (!anyHasAssignments)
			assignments = null;
		createTransitionArray();
		initState = 0;
	}

	public Automaton(LTS system)
	{
		this(system, null);
	}

	public static Automaton fromJani(Map janiData,
	                                 Map<String, Number> constants)
	{
		return new Automaton(SymbolicAutomaton.fromJani(janiData, constants));
	}	

	public int hashCode()
	{
		int ret = initState;
		for (int ts[] : targets) {
			for (int t : ts) {
				ret = (ret * 31) + t;
			}
		}
		for (String ls[] : labels) {
			for (String l : ls) {
				ret = (ret * 5) + l.hashCode();
			}
		}
		if (assignments == null)
			return ret;
		for (Map<String, Expression> assignss[] : assignments) {
			for (Map<String, Expression> assigns : assignss) {
				ret = (ret * 3) + assigns.hashCode();
			}
		}
		return ret;
	}

	public boolean equals(Object otherO)
	{
		if (!(otherO instanceof Automaton))
			return false;
		Automaton other = (Automaton)otherO;
		if (other.initState != initState)
			return false;
		if (!Arrays.deepEquals(other.targets, targets))
			return false;
		if (!Arrays.deepEquals(other.labels, labels))
			return false;
		if (!Arrays.deepEquals(other.assignments, assignments))
			return false;
		return true;
	}

	@SuppressWarnings("unchecked")
	private void createTransitionArray()
	{
		transitions = (HashMap<String, Integer>[]) new HashMap[labels.length];
		for (int i = labels.length - 1; i >= 0; i--) {
			transitions[i] = new HashMap<String, Integer>();
			for (int j = labels[i].length - 1; j >= 0; j--) {
				if (labels[i][j].charAt(0) == 'r')
					continue;
				if (transitions[i].containsKey(labels[i][j])) {
					transitions[i] = null;
					break;
				}
				transitions[i].put(labels[i][j], j);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Expression>[][] createAssignmentArray(int len)
	{
		return (Map<String, Expression>[][]) new Map[len][0];
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
			createTransitionArray();
		}
	}

	private int readBcg(String filename) throws IOException
	{
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

	public Automaton removeInternalNondet(Map<String, String> renames)
	{
		boolean needsChanges = false;
		TreeSet<String> presentActions = new TreeSet<>();
		for(int i = 0; i < targets.length; i++) {
			TreeSet<String> stateActions = new TreeSet<>();
			for (int j = 0; j < targets[i].length; j++) {
				String act = labels[i][j];
				if (act.charAt(0) == 'r')
					continue;
				presentActions.add(act);
				if (!stateActions.add(act))
					needsChanges = true;
			}
		}
		if (!needsChanges)
			return this;

		Automaton ret = new Automaton(this, Map.of());
		for(int i = 0; i < targets.length; i++) {
			TreeSet<String> stateActions = new TreeSet<>();
			int k = 0;
			for (int j = 0; j < labels[i].length; j++) {
				String act = labels[i][j];
				if (act.charAt(0) == 'r')
					continue;
				if (stateActions.add(act))
					continue;
				act = renames.get(act);
				if (act == null)
					act = "a" + (k++);
				while (presentActions.contains(act))
					act = "a" + (k++);
				renames.put(act, labels[i][j]);
				ret.labels[i] = ret.labels[i].clone();
				ret.labels[i][j] = act;
			}
		}
		ret.createTransitionArray();
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
			String label;
			if (parts[1].startsWith("rate ")) {
				label = 'r' + parts[1].substring(5);
			} else {
				label = 'i' + parts[1];
			}
			labels[from][targets[from].length - 1] = label.intern();
		}
		createTransitionArray();
		return ret;
	}

	private void addDontCaresMutating(String dontCare,
	                                  String notCared,
	                                  Set<String> preserve)
	{
		/* First, identify all states from which we can never
		 * perform a preserved action.
		 * (Technically, we could also check for states from
		 * which we can always perform preserved actions, and
		 * various combinations for different actions, but
		 * I don't have any models exhibiting such behaviour).
		 */
		BitSet dontCareStates = new BitSet(targets.length);
		dontCareStates.set(0, targets.length);
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = labels.length - 1; i >= 0; i--) {
				if (!dontCareStates.get(i))
					continue;
				for (String l : labels[i]) {
					if (preserve.contains(l)) {
						dontCareStates.clear(i);
						changed = true;
						break;
					}
				}
				if (!dontCareStates.get(i))
					continue;
				for (int t : targets[i]) {
					if (!dontCareStates.get(t)) {
						dontCareStates.clear(i);
						changed = true;
						break;
					}
				}
			}
		}
		
		/* Fint or create a target state that can do nothing
		 * (except tell others we have stopped caring).
		 */
		int finalState = dontCareStates.nextSetBit(0);
		if (finalState == -1) {
			finalState = targets.length;
			targets = Arrays.copyOf(targets, finalState+1);
			labels = Arrays.copyOf(labels, finalState + 1);
		}
		/* The final state cannot have guards or
		 * assignments.
		 */
		targets[finalState] = new int[1];
		targets[finalState][0] = finalState;
		labels[finalState] = new String[1];
		labels[finalState][0] = dontCare;

		/* Anything going to any state where we don't care
		 * should go to the final state.
		 */
		for (int i = 0; i < targets.length; i++) {
			for (int j = 0; j < targets[i].length; j++) {
				if (dontCareStates.get(targets[i][j]))
					targets[i][j] = finalState;
			}
		}

		/* Add an unguarded transition from every other state to the
		 * don't care state. */
		for (int i = 0; i < targets.length; i++) {
			if (i == finalState)
				continue;
			int n = targets[i].length;
			targets[i] = Arrays.copyOf(targets[i], n + 1);
			labels[i] = Arrays.copyOf(labels[i], n + 1);
			targets[i][n] = finalState;
			labels[i][n] = notCared;
		}
		createTransitionArray();
	}

	public Automaton addDontCares(String dontCare,
	                              String notCared,
	                              Set<String> preserve)
	{
		Automaton ret = new Automaton(this);
		ret.addDontCaresMutating(dontCare, notCared, preserve);
		return ret;
	}

	public Automaton stopCaring(String signal, String dontCareSignal, String stopCaringSignal)
	{
		Automaton ret = new Automaton(this);
		int dontCareState = -1;
		for (int i = 0; i < ret.labels.length; i++) {
			if (ret.targets[i].length != 1)
				continue;
			if (ret.targets[i][0] != i)
				continue;
			if (!ret.labels[i][0].equals(dontCareSignal))
				continue;
			dontCareState = i;
			break;
		}
		if (dontCareState == -1)
			throw new IllegalStateException("Attempt to redirect \"don't care\" signal in automaton that doesn't have \"don't care\" state.");
		int targetState = -1;
		for (int i = 0; i < ret.labels.length; i++) {
			if (ret.targets[i].length != 1)
				continue;
			if (ret.targets[i][0] != dontCareState)
				continue;
			if (!ret.labels[i][0].equals(stopCaringSignal))
				continue;
			targetState = i;
			break;
		}
		if (targetState == -1) {
			int n = ret.targets.length;
			ret.targets = Arrays.copyOf(ret.targets, n + 1);
			ret.labels = Arrays.copyOf(ret.labels, n + 1);
			ret.targets[n] = new int[1];
			ret.labels[n] = new String[1];
			ret.targets[n][0] = dontCareState;
			ret.labels[n][0] = stopCaringSignal;
			targetState = n;
		}

		for (int i = 0; i < ret.labels.length; i++) {
			for (int j = 0; j < ret.labels[i].length; j++) {
				if (ret.labels[i][j].equals(signal))
					ret.targets[i][j] = targetState;
			}
		}
		ret.createTransitionArray();
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
	 * @return The label of the n'th transition from state 'from',
	 * or null if 'from' has fewer than n transitions.
	 */
	public String getTransitionLabel(int from, int n)
	{
		if (labels[from].length <= n)
			return null;
		return labels[from][n];
	}

	/**
	 * @return The guard of the n'th transition from state 'from',
	 * or null if 'from' has fewer than n transitions.
	 */
	public Expression getTransitionGuard(int from, int n)
	{
		if (guards == null || guards.length <= from)
			return null;
		if (guards[from] == null || guards[from].length <= n)
			return null;
		return guards[from][n];
	}

	/**
	 * @return The number of the named transition, or -1 if no such
	 * transition exists.
	 */
	public int getTransitionNum(int from, String transition)
	{
		if (transitions[from] == null)
			throw new UnsupportedOperationException("Internal nondeterminism in context expecting determinised model.");
		Integer r = transitions[from].get(transition);
		if (r == null)
			return -1;
		return r;
	}

	/**
	 * @param includeMarkov Whether to include Markovian
	 * transitions.
	 * @return The set of all actions that occur in this automaton.
	 */
	public Set<String> getAllActions(boolean includeMarkov)
	{
		Set<String> ret = new TreeSet<>();
		int i = 0;
		for (String[] acts : labels) {
			int j = 0;
			for (String act : acts) {
				if (includeMarkov || act.charAt(0) != 'r')
					ret.add(act);
				j++;
			}
			i++;
		}
		return ret;
	}

	public boolean isAlwaysEnabled(String action)
	{
		for (String[] labs : labels) {
			boolean enabled = false;
			for (String label : labs) {
				if (label.equals(action)) {
					enabled = true;
					break;
				}
			}
			if (!enabled)
				return false;
		}
		return true;
	}

	public boolean hasAnyAssignments()
	{
		if (assignments == null)
			return false;
		for (Map<String, Expression> assigns[] : assignments) {
			if (assigns == null)
				continue;
			for (Map<String, Expression> assign : assigns) {
				if (!assign.isEmpty())
					return true;
			}
		}
		return false;
	}

	public Map<String, Expression> getAssignments(int from, int n)
	{
		if (assignments == null)
			return null;
		if (assignments[from].length <= n)
			return null;
		return assignments[from][n];
	}

	public Map<String, Integer> getVarValues(int[] state)
	{
		return new HashMap<String, Integer>();
	}

	public int getVarValue(String var, int[] state)
	{
		throw new IllegalArgumentException("Attempt to read variable '" + var + "' of automaton without variables.");
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
		int target, src = from[0];
		for (int i = 0; i < targets[src].length; i++) {
			target = targets[src][i];
			String label = labels[src][i];
			Map<String, Expression> assigns = Map.of();
			if (assignments != null
			    && assignments.length > src
			    && assignments[src].length > i)
			{
				assigns = assignments[src][i];
			}
			ret.add(new LTS.Transition(label, new int[]{target},
			                           ConstantExpression.TRUE,
			                           assigns));
		}
		return ret;
	}

	/** Return a reduced automaton that can only execute the
	 * specified labels (in addition to Markovian rates).
	 */
	public Automaton trim(Set<String> keep) {
		boolean anyChange = false;
		boolean needsChange[] = new boolean[labels.length];
		for (int i = 0; i < labels.length; i++) {
			for (int j = 0; j < labels[i].length; j++) {
				if (labels[i][j].charAt(0) == 'r')
					continue;
				if (!keep.contains(labels[i][j])) {
					needsChange[i] = true;
					anyChange = true;
					break;
				}
			}
		}
		if (!anyChange)
			return this;
		return new Automaton(this, keep);
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

	public void printJaniAutomaton(String name, PrintStream out)
	{
		out.println("\t{\"name\":\""+name+"\",");
		out.print  ("\t \"locations\":[");
		for (int i = 0; i < targets.length; i++) {
			if (i > 0)
				out.print(",");
			out.print("{\"name\":\"l"+i+"\"}");
		}
		out.println("],"); /* End of locations */
		out.println("\t \"initial-locations\":[\"l"+initState+"\"],");
		out.println("\t \"edges\":[");
		boolean firstEdge = true;
		for (int i = 0; i < targets.length; i++) {
			for (int j = 0; j < targets[i].length; j++) {
				if (!firstEdge)
					out.println(",");
				firstEdge = false;
				out.println("\t\t{\"location\":\"l"+i+"\",");
				if (assignments == null || assignments[i] == null || assignments[i].length <= j || assignments[i][j] == null) {
					out.println("\t\t \"destinations\":[{\"location\":\"l"+targets[i][j]+"\"}],");
				} else {
					out.println("\t\t \"destinations\":[{");
					out.println("\t\t\t\"location\": \"l" + targets[i][j] + "\",");
					out.println("\t\t\t\"assignments\": [");
					boolean first = true;
					for (String var : assignments[i][j].keySet()) {
						Expression val = assignments[i][j].get(var);
						if (!first)
							out.println(",");
						first = false;
						out.print("\t\t\t\t{\"ref\": \"" + var + "\", \"value\": ");
						val.writeJani(out, 5);
						out.print("}");
					}
					out.println();
					out.println("\t\t\t]");
					out.println("\t\t }],");
				}
				if (labels[i][j].charAt(0) == 'r') {
					out.println("\t\t \"rate\":{\"exp\":"+labels[i][j].substring(1)+"}");
				} else {
					out.println("\t\t \"action\":\""+labels[i][j].substring(1)+"\"");
				}
				out.print("\t\t}");
			}
		}
		out.print("\n\t]}");
	}
}
