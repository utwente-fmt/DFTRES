package nl.utwente.ewi.fmt.EXPRES;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public interface LTS
{
	public static class Transition implements Comparable<Transition> {
		public final String label;
		public final int[] target;
		public final Expression guard;
		public final Map<String, Expression> assignments;

		public Transition(String l, int[] t, Expression g,
		                  Map<String, Expression> assts)
		{
			label = l;
			target = t;
			if (g != null)
				guard = g;
			else
				guard = ConstantExpression.TRUE;
			if (assts != null)
				assignments = assts;
			else
				assignments = Map.of();
		}

		public boolean equals(Object o)
		{
			if (!(o instanceof Transition))
				return false;
			Transition other = (Transition) o;
			if (!label.equals(other.label))
				return false;
			if ((guard == null) != (other.guard == null))
				return false;
			if (guard != null && !guard.equals(other.guard))
				return false;
			if ((assignments == null) != (other.assignments == null))
				return false;
			if (assignments != null) {
				if (!assignments.equals(other.assignments))
					return false;
			}
			return Arrays.equals(target, other.target);
		}

		public int compareTo(Transition other)
		{
			if (this == other)
				return 0;
			if (!label.equals(other.label))
				return label.compareTo(other.label);
			if (target.length > other.target.length)
				return 1;
			if (target.length < other.target.length)
				return -1;
			for (int i = 0; i < target.length; i++) {
				if (target[i] > other.target[i])
					return 1;
				if (target[i] < other.target[i])
					return -1;
			}
			if (guard == null && other.guard != null)
				return -1;
			if (guard != null && other.guard == null)
				return 1;
			if (guard != null) {
				String ours = guard.toString();
				String theirs = other.guard.toString();
				int r = ours.compareTo(theirs);
				if (r != 0)
					return r;
			}
			if (assignments == null && other.assignments != null)
				return -1;
			if (assignments != null && other.assignments == null)
				return 1;
			if (assignments == null)
				return 0;
			if (assignments.size() < other.assignments.size())
				return -1;
			if (assignments.size() > other.assignments.size())
				return 1;
			if (assignments.equals(other.assignments))
				return 0;
			TreeSet<String> ours = new TreeSet<>(assignments.keySet());
			TreeSet<String> theirs = new TreeSet<>(other.assignments.keySet());
			String s1 = ours.first(), s2 = theirs.first();
			do {
				int r = s1.compareTo(s2);
				if (r != 0)
					return r;
				s1 = ours.higher(s1);
				s2 = ours.higher(s2);
				String e1 = assignments.get(s1).toString();
				String e2 = assignments.get(s2).toString();
				r = e1.compareTo(e2);
				if (r != 0)
					return r;
			} while (s1 != null);
			return 0;
		}

		public int hashCode()
		{
			return label.hashCode() + Arrays.hashCode(target);
		}

		public String toString()
		{
			return label + " -> " + Arrays.toString(target);
		}
	}

	public static class StateWrapper implements Comparable<StateWrapper> {
		public final int[] state;

		public StateWrapper(int[] state)
		{
			this.state = state;
		}

		public boolean equals(Object o)
		{
			if (!(o instanceof StateWrapper))
				return false;
			StateWrapper other = (StateWrapper) o;
			return Arrays.equals(state, other.state);
		}

		public int compareTo(StateWrapper other)
		{
			int[] s = state;
			int[] o = other.state;
			if (s.length > o.length)
				return 1;
			if (s.length < o.length)
				return -1;
			for (int i = 0; i < s.length; i++) {
				if (s[i] > o[i])
					return 1;
				if (s[i] < o[i])
					return -1;
			}
			return 0;
		}

		public int hashCode()
		{
			return Arrays.hashCode(state);
		}

		public String toString()
		{
			return Arrays.toString(state);
		}
	}

	public int[] getInitialState();
	public Set<Transition> getTransitions(int[] from);
	public Map<String, Integer> getVarValues(int[] state);
	public int getVarValue(String var, int[] state);
	public int stateSize();
}
