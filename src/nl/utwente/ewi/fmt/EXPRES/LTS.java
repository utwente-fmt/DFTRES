package nl.utwente.ewi.fmt.EXPRES;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

public interface LTS
{
	public static class Transition implements Comparable<Transition> {
		public final String label;
		public final int[] target;

		public Transition(String l, int[] t)
		{
			label = l;
			target = t;
		}

		public boolean equals(Object o)
		{
			if (!(o instanceof Transition))
				return false;
			Transition other = (Transition) o;
			if (!label.equals(other.label))
				return false;
			return Arrays.equals(target, other.target);
		}

		public int compareTo(Transition other)
		{
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
	public int stateSize();
}
