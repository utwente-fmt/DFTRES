package models;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public abstract class StateSpace {
	public class State {
		public final int[] state;
		public final int number;

		public State(int[] s, int num)
		{
			state = s;
			number = num;
		}

		public boolean equals(Object o)
		{
			if (o instanceof State)
				return ((State)o).number == number;
			if (!(o instanceof StateWrapper))
				return false;
			return Arrays.equals(state, ((StateWrapper)o).state);
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
	public class ExploredState extends State {
		public final int[] neighbours;
		public final short[] orders;
		public final double[] probs;
		public final double exitRate;

		public ExploredState(int[] s, int[] n, short[] o, double[] p,
		                     double R, int num)
		{
			super(s, num);
			neighbours = n;
			orders = o;
			probs = p;
			exitRate = R;
		}

		private ExploredState(ExploredState other)
		{
			this(other, other.neighbours, other.orders,
			     other.probs, other.exitRate);
		}

		public ExploredState(State other, int[] n, short[] o,
		                     double[] p, double R)
		{
			this(other.state, n, o, p, R, other.number);
		}

		public double getProbTo(int state)
		{
			for (int i = 0; i < neighbours.length; i++)
				if (neighbours[i] == state)
					return probs[i];
			return 0;
		}

		public short getOrderTo(int state)
		{
			for (int i = 0; i < neighbours.length; i++)
				if (neighbours[i] == state)
					return orders[i];
			return Short.MAX_VALUE;
		}
	}
	private static class StateWrapper {
		public int[] state;

		public StateWrapper(int[] s)
		{
			state = s;
		}

		public boolean equals(Object o)
		{
			if (o instanceof State)
				return Arrays.equals(state, ((State)o).state);
			if (!(o instanceof StateWrapper))
				return false;
			return Arrays.equals(state, ((StateWrapper)o).state);
		}

		public int hashCode()
		{
			return Arrays.hashCode(state);
		}

		public Object clone()
		{
			return new StateWrapper(state);
		}
	}
	public class HPCState extends ExploredState {
		public final int[] origNeighbours;
		public final double[] origProbs;
		public HPCState(ExploredState orig, int[] ns, short[] os, double[] ps)
		{
			super(orig.state, ns, os, ps, orig.exitRate, orig.number);
			origNeighbours = orig.neighbours;
			origProbs = orig.probs;
		}
	}

	/* Regarding the public fields, only the following modifications
	 * may be made:
	 * - Changing any of the bitsets.
	 * - FULLY replacing any array in successors, orders, or probs.
	 * - Adding new states (extending the length of every List and
	 *   the size of knownStates) via reserve().
	 * - Modifying the value of exitRates[i] for any i for which
	 *   successors.get(i) == null.
	 *
	 * Any other modifications may mess up snapshotting.
	 */
	private HashMap<State, State> knownStates;
	private final StateSpace parent;
	private List<State> states;
	public final double epsilon;

	private State reserve(int[] x) {
		State ret = new State(x, states.size());
		knownStates.put(ret, ret);
		states.add(ret);
		return ret;
	}

	public StateSpace(double epsilon, int[] initialState) {
		this.epsilon = epsilon;
		knownStates = new HashMap<State, State>();
		states = new ArrayList<>();
		parent = null;
		reserve(initialState);
	}

	/** Follows the behaviour of snapshot() */
	protected StateSpace(StateSpace other) {
		parent = other;
		knownStates = new HashMap<>(other.knownStates);
		states = new ArrayList<>(other.states);
		epsilon = other.epsilon;
	}

	/**
	 * Create a new StateSpace with the following properties:
	 *
	 * - Modifications to the snapshot are not visible in the
	 *   parent.
	 * - Modifications to separate snapshots are not visible to each
	 *   other.
	 * - Modifications to the parent have undefined behaviour in the
	 *   snapshots.
	 * - Modifications to intended-to-be-constant entries in any
	 *   snapshot have undefined behaviour.
	 */
	public abstract StateSpace snapshot();

	public int size() {
		return states.size();
	}

	public State getState(int s)
	{
		return states.get(s);
	}

	public void addHPC(ExploredState orig, int[] newNeighbours,
	                   short[] newOrders, double[] newProbs)
	{
		if (orig instanceof HPCState)
			return;
		HPCState n = new HPCState(orig, newNeighbours, newOrders, 
		                          newProbs);
		knownStates.remove(orig);
		knownStates.put(n, n);
		states.set(n.number, n);
	}

	private ThreadLocal<StateWrapper> cachedWrapper = new ThreadLocal<>();

	public State find(int[] x) {
		StateWrapper w = cachedWrapper.get();
		if (w == null) {
			w = new StateWrapper(x);
			cachedWrapper.set(w);
		}
		w.state = x;
		return knownStates.get(w);
	}

	protected State findOrCreate(int[] x) {
		State s = find(x);
		if(s == null)
			s = reserve(x);
		return s;
	}

	protected ExploredState explored(State s, int[] neighbours,
	                                 short[] orders, double[] probs,
	                                 double exitRate)
	{
		ExploredState ret = new ExploredState(s, neighbours, orders,
		                                      probs, exitRate);
		knownStates.remove(s);
		knownStates.put(ret, ret);
		states.set(ret.number, ret);
		return ret;
	}

	public State getInitialState()
	{
		return states.get(0);
	}

	/** Instantiate the relevant fields for the specified state.
	 *
	 * This method should populate the s'th entries of successors,
	 * orders, and probs with correctly filled arrays, and set
	 * exitRates[s] to the correct value.
	 */
	public abstract ExploredState findNeighbours(State x);

	public String stateString(int state) {
		return "state "+ state +", ="+states.get(state).toString();
	}

	public Number getVarValue(String variable, State state) {
		return null;
	}
}
