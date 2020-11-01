package nl.utwente.ewi.fmt.EXPRES;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public interface LTS
{
	static final int HASH_FACTOR = 32771;

	public static class Transition implements Comparable<Transition> {
		public final String label;
		public final int[] target;
		public final Expression guard;
		public final Map<String, Expression> assignments;
		private int cachedHashCode = -1;

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
			if (cachedHashCode != -1 && other.cachedHashCode != -1){
				if (cachedHashCode != other.cachedHashCode)
					return false;
			}
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
			if (cachedHashCode == -1) {
				cachedHashCode = label.hashCode();
				cachedHashCode *= 3;
				cachedHashCode += Arrays.hashCode(target);
				cachedHashCode *= 3;
				cachedHashCode += guard.hashCode();
				cachedHashCode *= 3;
				cachedHashCode += assignments.hashCode();
			}
			return cachedHashCode;
		}

		public String toString()
		{
			return label + " -> " + Arrays.toString(target);
		}
	}

	public static interface StateWrapperLike {
		public int[] getState();
	};

	public static class ReducedStateWrapper implements StateWrapperLike {
		public final byte[] state;

		private ReducedStateWrapper(int[] state) {
			final byte[] tmp = new byte[state.length];
			for (int i = state.length - 1; i >= 0; i--) {
				if (state[i] > Byte.MAX_VALUE)
					throw new AssertionError();
				if (state[i] < Byte.MIN_VALUE)
					throw new AssertionError();
				tmp[i] = (byte)state[i];
			}
			this.state = tmp;
		}

		public int[] getState() {
			int[] ret = new int[state.length];
			for (int i = state.length - 1; i >= 0; i--)
				ret[i] = state[i];
			return ret;
		}

		public boolean equals(Object o)
		{
			if (o instanceof ReducedStateWrapper) {
				ReducedStateWrapper r = (ReducedStateWrapper)o;
				return Arrays.equals(r.state, state);
			}
			if (o instanceof StateWrapper) {
				StateWrapper w = (StateWrapper)o;
				final int[] their = w.state;
				final byte[] our  = state;
				if (their.length != our.length)
					return false;
				for (int i = our.length - 1; i >= 0; i--) {
					if (their[i] != our[i])
						return false;
				}
				return true;
			}
			return false;
		}

		public int hashCode()
		{
			int ret = 0;
			for (int i = state.length - 1; i >= 0; i--)
				ret = (ret * HASH_FACTOR) + state[i];
			return ret;
		}

	}

	public static class TransitionSet implements Set<Transition>
	{
		private final Transition[] elements;
		public TransitionSet(Collection<Transition> ts) {
			if (ts.size() == 0) {
				elements = null;
			} else if (ts instanceof Set) {
				elements = new Transition[ts.size()];
				ts.toArray(elements);
			} else if (ts.size() < 10) {
				elements = new Transition[ts.size()];
				ts.toArray(elements);
				for (int i = 0; i < elements.length; i++) {
					int j;
					for (j = 0; j < elements.length; j++) {
						if (i == j)
							continue;
						if (elements[i].equals(elements[j]))
							throw new IllegalArgumentException("Elements of transition set not unique");
					}
				}
			} else {
				Set<Transition> s = new HashSet<>(ts);
				if (s.size() != ts.size())
					throw new IllegalArgumentException("Elements of transition set not unique");
				elements = new Transition[ts.size()];
				ts.toArray(elements);
			}
		}

		public TransitionSet(Collection<Transition> ts, boolean trusted)
		{
			if (ts.isEmpty()) {
				elements = null;
			} else {
				if (!trusted) {
					throw new UnsupportedOperationException();
				} else {
					elements = new Transition[ts.size()];
					ts.toArray(elements);
				}
			}
		}

		public boolean contains(Object o) {
			if (elements == null)
				return false;
			for (Transition t : elements)
				if (t.equals(o))
					return true;
			return false;
		}

		public boolean containsAll(Collection<?> c) {
			for (Object o : c)
				if (!contains(o))
					return false;
			return true;
		}

		public boolean equals(Object o) {
			if (!(o instanceof Set))
				return false;
			Set<?> other = (Set<?>)o;
			if (elements == null)
				return other.isEmpty();
			if (other.size() != elements.length)
				return false;
			for (Transition t : elements)
				if (!(other.contains(t)))
					return false;
			return true;
		}

		public int hashCode() {
			int ret = 0;
			if (elements == null)
				return 0;
			for (Transition t : elements)
				ret += t.hashCode();
			return ret;
		}

		public boolean isEmpty() {
			return elements == null;
		}

		public Iterator<Transition> iterator() {
			if (elements == null) {
				return new Iterator<Transition> () {
					public boolean hasNext() {
						return false;
					}
					public Transition next() {
						throw new java.util.NoSuchElementException();
					}
				};
			}
			return new Iterator<Transition> () {
				private int i = 0;
				public boolean hasNext() {
					return i < elements.length;
				}
				public Transition next() {
					return elements[i++];
				}
			};
		}

		public int size() {
			if (elements == null)
				return 0;
			return elements.length;
		}

		public Object[] toArray() {
			if (elements == null)
				return new Transition[0];
			return elements.clone();
		}

		public <T> T[] toArray(T[] a) {
			if (elements == null && a.length > 0) {
				a[0] = null;
				return a;
			}
			if (a.length >= elements.length) {
				System.arraycopy(elements, 0, a, 0, elements.length);
				if (a.length > elements.length)
					a[elements.length] = null;
				return a;
			}
			return new TreeSet<Transition>(this).toArray(a);
		}

		public String toString() {
			if (elements == null)
				return "[]";
			String ret = "[\n\t";
			boolean first = true;
			for (Transition t : elements) {
				if (!first)
					ret += ",\n\t";
				first = false;
				ret += t;
			}
			ret += "\n]";
			return ret;
		}


		public boolean add(Transition e) { throw new UnsupportedOperationException(); };
		public boolean addAll(Collection <? extends Transition> c) { throw new UnsupportedOperationException(); };
		public void clear() { throw new UnsupportedOperationException(); };
		public boolean remove(Object e) { throw new UnsupportedOperationException(); };
		public boolean removeAll(Collection <?> c) { throw new UnsupportedOperationException(); };
		public boolean retainAll(Collection <?> c) { throw new UnsupportedOperationException(); };
	}

	public static class StateWrapper implements Comparable<StateWrapper>, StateWrapperLike {
		public final int[] state;

		public StateWrapper(int[] state)
		{
			this.state = state;
		}

		public int[] getState() {
			return state;
		}

		public boolean equals(Object o)
		{
			if (!(o instanceof StateWrapper)) {
				if (o instanceof ReducedStateWrapper)
					return ((ReducedStateWrapper)o).equals(this);
				return false;
			}
			StateWrapper other = (StateWrapper) o;
			return Arrays.equals(state, other.state);
		}

		public StateWrapperLike tryReduce() {
			for (int i = state.length - 1; i >= 0; i--) {
				if (state[i] > Byte.MAX_VALUE)
					return this;
				if (state[i] < Byte.MIN_VALUE)
					return this;
			}
			return new ReducedStateWrapper(state);
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
			int ret = 0;
			for (int i = state.length - 1; i >= 0; i--)
				ret = (ret * HASH_FACTOR) + state[i];
			return ret;
		}

		public String toString()
		{
			return Arrays.toString(state);
		}
	}

	public int[] getInitialState();
	public Set<Transition> getTransitions(int[] from) throws NondeterminismException;
	public Map<String, Integer> getVarValues(int[] state);
	public int getVarValue(String var, int[] state);
	public int stateSize();
	public static StateWrapperLike wrapUncomparable(final int[] state)
	{
		for (int i = state.length - 1; i >= 0; i--) {
			if (state[i] > Byte.MAX_VALUE)
				return new StateWrapper(state);
			if (state[i] < Byte.MIN_VALUE)
				return new StateWrapper(state);
		}
		return new ReducedStateWrapper(state);
	}
}
