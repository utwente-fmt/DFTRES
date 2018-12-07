package models;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
		public final double[] meanTimes;
		public HPCState(ExploredState orig, int[] ns, short[] os, double[] ps)
		{
			this(orig, ns, os, ps, null);
		}

		public HPCState(ExploredState orig, int[] ns, short[] os, double[] ps, double[] mt)
		{
			super(orig.state, ns, os, ps, orig.exitRate, orig.number);
			origNeighbours = orig.neighbours;
			origProbs = orig.probs;
			meanTimes = mt;
		}
	}

	private HashMap<State, State> knownStates;
	private volatile State[] states;
	private volatile int nStates;
	public final double epsilon;
	/* Locks are public since HPC removal needs synchronization
	 * across multiple state updates.
	 */
	public Lock writeLock, readLock;

	private State reserve(int[] x) {
		State ret;
		try {
			writeLock.lock();
			ret = new State(x, nStates);
			nStates++;
			if (ret.number >= states.length)
				states = Arrays.copyOf(states, states.length*2);
			states[ret.number] = ret;
			knownStates.put(ret, ret);
		} finally {
			writeLock.unlock();
		}
		return ret;
	}

	public StateSpace(double epsilon, int[] initialState) {
		this.epsilon = epsilon;
		knownStates = new HashMap<State, State>();
		states = new State[1024];
		nStates = 0;
		ReentrantReadWriteLock locks = new ReentrantReadWriteLock();
		writeLock = locks.writeLock();
		readLock = locks.readLock();
		reserve(initialState);
	}

	/** Follows the behaviour of snapshot() */
	protected StateSpace(StateSpace other) {
		knownStates = new HashMap<>(other.knownStates);
		states = other.states.clone();
		nStates = other.nStates;
		epsilon = other.epsilon;
		ReentrantReadWriteLock locks = new ReentrantReadWriteLock();
		writeLock = locks.writeLock();
		readLock = locks.readLock();
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
		return nStates;
	}

	public State getState(int s)
	{
		return states[s];
	}

	public void addHPC(ExploredState orig, int[] newNeighbours,
	                   short[] newOrders, double[] newProbs,
			   double[] meanTimes)
	{
		if (orig instanceof HPCState)
			return;
		try {
			writeLock.lock();
			HPCState n = new HPCState(orig, newNeighbours,
			                          newOrders, newProbs,
			                          meanTimes);
			knownStates.remove(orig);
			knownStates.put(n, n);
			states[n.number] = n;
		} finally {
			writeLock.unlock();
		}
	}

	private ThreadLocal<StateWrapper> cachedWrapper = new ThreadLocal<>();

	public State find(int[] x) {
		StateWrapper w = cachedWrapper.get();
		if (w == null) {
			w = new StateWrapper(x);
			cachedWrapper.set(w);
		}
		w.state = x;
		try {
			readLock.lock();
			return knownStates.get(w);
		} finally {
			readLock.unlock();
		}
	}

	protected State findOrCreate(int[] x) {
		State s;
		s = find(x);
		if(s == null) {
			try {
				writeLock.lock();
				s = find(x);
				if (s == null)
					s = reserve(x);
			} finally {
				writeLock.unlock();
			}
		}
		return s;
	}

	protected ExploredState explored(State s, int[] neighbours,
	                                 short[] orders, double[] probs,
	                                 double exitRate)
	{
		ExploredState ret = new ExploredState(s, neighbours, orders,
		                                      probs, exitRate);
		try {
			writeLock.lock();
			State prev = knownStates.get(s);
			if (prev instanceof ExploredState)
				return (ExploredState)prev;
			knownStates.remove(s);
			knownStates.put(ret, ret);
			states[ret.number] = ret;
		} finally {
			writeLock.unlock();
		}
		return ret;
	}

	public State getInitialState()
	{
		return states[0];
	}

	/** Instantiate the relevant fields for the specified state.
	 *
	 * This method should populate the s'th entries of successors,
	 * orders, and probs with correctly filled arrays, and set
	 * exitRates[s] to the correct value.
	 */
	public abstract ExploredState findNeighbours(State x);

	public String stateString(int state) {
		return "state "+ state +", ="+states[state].toString();
	}

	public Number getVarValue(String variable, State state) {
		return null;
	}
}
