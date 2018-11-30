package nl.utwente.ewi.fmt.EXPRES;

import java.util.Arrays;
import java.util.Comparator;
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
public class MarkovianComposition extends Composition
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

	private final Composition original;

	public MarkovianComposition(Composition c)
	{
		original = c;
	}

	public int[] getInitialState()
	{
		boolean done = false;
		int[] ret = original.getInitialState();
		TreeSet<int[]> visited = new TreeSet<int[]>(new StateComparator());
		while (!done) {
			Set<Transition> outgoing = original.getTransitions(ret);
			done = true;
			for (Transition t : outgoing) {
				if (visited.contains(t.target))
					continue;
				if (!t.label.startsWith("rate ")) {
					done = false;
					visited.add(ret);
					ret = t.target;
					break;
				}
			}
		}
		return ret;
	}

	private Set<Transition> getTransitions(
			int[] from,
			TreeSet<int[]> visited)
	{
		visited.add(from);
		int[] farForward = original.getBigTransition(from);
		visited.add(farForward);
		Set<Transition> orig = original.getTransitions(farForward);
		//System.err.format("Original transitions from %s: %s\n", Arrays.toString(from), orig);
		Set<Transition> reduced = new TreeSet<Transition>();
		for (Transition t : orig) {
			if (t.label.startsWith("rate "))
				reduced.add(t);
			if (!visited.contains(t.target))
				reduced.add(t);
		}
		//System.err.format("Reduced transitions from %s: %s\n", Arrays.toString(from), orig);
		if (reduced.isEmpty())
			return reduced;
		Transition nonMarkov = null;
		for (Transition t : reduced) {
			if (!t.label.startsWith("rate ")) {
				nonMarkov = t;
				break;
			}
		}
		if (nonMarkov == null) {
			return reduced;
		}
		//System.err.format("Taking transition %s\n", nonMarkov.label);
		return getTransitions(nonMarkov.target, visited);
	}

	/* Return a state at some maximal distance from this transition */
	private Transition forward(Transition t, Set<int[]> visited)
	{
		visited.add(t.target);
		Set<Transition> outgoing = original.getTransitions(t.target);
		//System.err.format("Original forward outgoing transitions from %s: %s\n", Arrays.toString(t.target), outgoing);
		Set<Transition> reduced = new TreeSet<Transition>();
		for (Transition s : outgoing) {
			if (s.label.startsWith("rate "))
				continue;
			if (s.guard.evaluate(getVarValues(t.target)).doubleValue() == 0)
				continue;
			if (!s.assignments.isEmpty())
				throw new UnsupportedOperationException("Assignments remain in Markovian composition.");
			if (!visited.contains(s.target)) {
				s = new Transition(t.label, s.target,
				                   null, null);
				return forward(s, visited);
			}
		}
		/* No more outgoing transitions */
		return t;
	}

	public Set<Transition> getTransitions(int[] from)
	{
		return getTransitions(from, false);
	}

	private Set<Transition> getTransitions(int[] from, boolean checkEmpty)
	{
		TreeSet<int[]> visited = new TreeSet<int[]>(new StateComparator());
		Set<Transition> direct = getTransitions(from, visited);
		Set<Transition> ret = new TreeSet<Transition>();
		if (checkEmpty)
			return direct;
		for (Transition t : direct) {
			if (getTransitions(t.target, true).isEmpty()) {
				System.err.format("No further transitions from %s\n", Arrays.toString(t.target));
				ret.add(t);
			} else {
				Transition f = forward(t, visited);
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
}
