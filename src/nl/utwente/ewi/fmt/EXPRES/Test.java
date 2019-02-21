package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.List;
import java.util.Set;

public class Test {
	static BitSet visited = new BitSet(32768);
	static TreeMap<String, Integer> stateNums = new TreeMap<String, Integer>();
	static int transitionCount = 0;
	static boolean INTERACTIVE = true;

	public static void main(String[] args)
			throws IOException, NondeterminismException
	{
		int[] state;
		LTS l;
		if (args[0].endsWith(".aut")) {
			Automaton a = new Automaton(args[0], "aut");
			printTransitions(a, 0);
			l = a;
		} else {
			Composition c;
			if (args[0].endsWith(".exp")) {
				c = new Composition(args[0], "exp");
				c.markStatesAfter("FAIL", 1);
				c.markStatesAfter("REPAIR", 0);
				c.markStatesAfter("ONLINE", 0);
			} else {
				c = new Composition(args[0], "jani");
			}
			l = c;
		}
		//LTS l = new MarkovReducedLTS(c);
		state = l.getInitialState();
		System.err.format("Initial state: %s\n", Arrays.toString(state));
		printTransitions(l, state, false);

		System.out.format("des (%d,%d,%d)\n",
		                  stateNums.get(Composition.stateString(state)),
				  transitionCount,
				  stateNums.size());
		visited = new BitSet(stateNums.size());
		printTransitions(l, state, true);
	}

	public static void printTransitions(Automaton a, int state)
	{
		if (visited.get(state))
			return;
		if ((stateNums.size() & 32767) == 0) {
			System.err.format("Processed %d states\n",
					stateNums.size());
		}
		visited.set(state);
		int k = 0;
		while (a.getTransitionTarget(state, k) != -1) {
			int target = a.getTransitionTarget(state, k);
			String label = a.getTransitionLabel(state, k);
			transitionCount++;
			System.out.format("(%d,\"%s\",%d)\n", state, label, target);
			printTransitions(a, target);
			k++;
		}
	}

	public static void printTransitions(LTS c, int[] state, boolean inDef)
		throws NondeterminismException
	{
		String sState = Composition.stateString(state);
		Integer stateNum = stateNums.get(sState);
		if (stateNum != null && visited.get(stateNum))
			return;
		if ((stateNums.size() & 32767) == 0) {
			System.err.format("Processed %d states\n",
					stateNums.size());
		}
		if (!inDef) {
			stateNum = stateNums.size();
			stateNums.put(sState, stateNum);
			try {
				visited.set(stateNum);
			} catch (IndexOutOfBoundsException e) {
				BitSet tmp = new BitSet(visited.length() * 2);
				tmp.or(visited);
				visited = tmp;
			}
			System.err.format("Number of state %s: %d\n",
					  Arrays.toString(state), stateNums.size() - 1);
			System.err.format("Current variables: %s\n", c.getVarValues(state));
			if (state[state.length - 1] != 0) {
				System.err.format("Marked state: %d\n",
				                  stateNums.size() - 1);
			}
		}
		visited.set(stateNum);
		Set<Composition.Transition> s = c.getTransitions(state);
		transitionCount += s.size();
		if (INTERACTIVE) {
			Set<Composition.Transition> rem = new HashSet<>();
			System.out.format("All transitions:\n");
			for (Composition.Transition t : s) {
				System.out.format("(%d,\"%s\",%s)\n",
						stateNums.get(sState),
						t.label,
						Arrays.toString(t.target));
			}
			for (Composition.Transition t : s) {
				System.out.format("Remove/Only \"%s\" ->%s)? ",
						t.label,
						//stateNums.get(Composition.stateString(t.target)));
						Arrays.toString(t.target));
				int r = 'a';
				try {
					while (r != 'y' && r != 'n' && r != 'o')
						r = System.in.read();
				} catch (java.io.IOException e) {
				}
				if (r == 'y')
					rem.add(t);
				if (r == 'o') {
					s = new HashSet<>();
					s.add(t);
					break;
				}
			}
			System.out.println();
			s = new HashSet<LTS.Transition>(s);
			s.removeAll(rem);
		}
		for (Composition.Transition t : s) {
			String tString = Composition.stateString(t.target);
			Integer tNum = stateNums.get(tString);
			if (inDef) {
				System.out.format("(%d,\"%s\",%d(%s))\n",
						stateNums.get(sState),
						t.label,
						//stateNums.get(Composition.stateString(t.target)));
						tNum,
						Arrays.toString(t.target));
			}
			printTransitions(c, t.target, inDef);
		}
	}
}
