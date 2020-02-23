package nl.utwente.ewi.fmt.EXPRES;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Models a reduction from a composed automaton to a Markovian model,
 * with the following properties:
 * - non-Markovian loops are interrupted at an undefined location.
 * - States with both non-Markovian and Markovian transitions (after
 *   loop-breaking) will only take the non-Markovian transition.
 * - If non-Markovian transitions are nondeterminstic, it is undefined
 *   which action is taken, but only one action will be.
 */
public class MarkovianComposition implements LTS
{
	private class StateComparator implements Comparator<int[]>
	{
		public int compare(int[] one, int[] other)
		{
			int i, l;
			l = one.length;
			if (l > other.length)
				return 1;
			if (l < other.length)
				return -1;
			for (i = 0; i < l; i++) {
				if (one[i] > other[i])
					return 1;
				if (one[i] < other[i])
					return -1;
			}
			return 0;
		}
	}

	private final LTS original;

	public MarkovianComposition(LTS c)
	{
		original = c;
	}

	public int[] getInitialState()
	{
		boolean done = false;
		int[] ret = original.getInitialState();
		TreeSet<int[]> visited = new TreeSet<int[]>(new StateComparator());
		while (!done) {
			Set<Transition> outgoing;
			try {
				outgoing = original.getTransitions(ret);
			} catch (NondeterminismException e) {
				throw new UnsupportedOperationException(e);
			}
			done = true;
			for (Transition t : outgoing) {
				if (visited.contains(t.target))
					continue;
				if (t.label.charAt(0) != 'r') {
					done = false;
					visited.add(ret);
					ret = t.target;
					break;
				}
			}
		}
		return ret;
	}

	private boolean timedCompatible(String s, String t)
	{
		if (t.charAt(0) == 't')
			return true;
		return s.charAt(0) != 't';
	}

	/* Return a state at some maximal distance from this transition */
	private Transition forward(Transition t) throws NondeterminismException
	{
		TreeSet<int[]> visited = new TreeSet<>(new StateComparator());
		Transition next = t;
		while (next != null) {
			t = next;
			next = null;
			visited.add(t.target);
			Set<Transition> outgoing = original.getTransitions(t.target);
			for (Transition s : outgoing) {
				if (s.label.charAt(0) != 'i')
					continue;
				if (!timedCompatible(s.label, t.label))
					continue;
				if (s.guard.evaluate(original, t.target).doubleValue() == 0)
					continue;
				if (!s.assignments.isEmpty())
					throw new UnsupportedOperationException("Assignments remain in Markovian composition.");
				if (!visited.contains(s.target)) {
					next = new Transition(t.label, s.target,
							null, null);
					break;
				}
			}
		}
		/* No more outgoing transitions */
		return t;
	}

	public Set<Transition> getTransitions(int[] from)
			throws NondeterminismException
	{
		return getTransitions(from, false);
	}

	private Set<Transition> getTransitions(int[] from, boolean checkEmpty)
			throws NondeterminismException
	{
		Set<Transition> direct = original.getTransitions(from);
		Set<Transition> ret = new TreeSet<Transition>();
		if (checkEmpty)
			return direct;
		for (Transition t : direct) {
			if (getTransitions(t.target, true).isEmpty()) {
				System.err.format("No further transitions from %s\n", Arrays.toString(t.target));
				ret.add(t);
			} else {
				Transition f = forward(t);
				/*
				System.err.format("Forward transitions from %s go %s\n", Arrays.toString(t.target), Arrays.toString(f.target));
				System.err.println("Visited:");
				for (int[] st : visited)
					System.err.println(Arrays.toString(st));
					*/
				ret.add(f);
			}
		}
		//System.err.format("Final transition set %d, explored %d states\n", ret.size(), Composition.statesExplored);
		return ret;
	}

	@Override public int stateSize() { return original.stateSize(); }
	@Override public int getVarValue(String v, int[] s) {
		return original.getVarValue(v, s);
	}
	@Override public Map<String, Integer> getVarValues(int[] s) {
		return original.getVarValues(s);
	}
}
