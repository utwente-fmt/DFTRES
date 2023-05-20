package models;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public abstract class StateSpace {
	public static class Neighbours {
		public final State[] neighbours;
		public final short[] orders;
		public final double[] probs;
		public final double exitRate;

		public Neighbours(State[] n, short[] o, double[] p, double R)
		{
			neighbours = n;
			orders = o;
			probs = p;
			exitRate = R;
		}

		public double getProbTo(State state)
		{
			State[] ns = neighbours;
			double[] probs = this.probs;
			for (int i = ns.length - 1; i >= 0; i--)
				if (ns[i] == state)
					return probs[i];
			return 0;
		}
	}
	public class State {
		public final int[] state;
		private int cachedHashCode = 0;
		private SoftReference<Neighbours> neighbours;

		public State(int[] s)
		{
			state = s;
			neighbours = null;
		}

		public boolean equals(Object o)
		{
			if (o == this)
				return true;
			if (o instanceof StateWrapper)
				return Arrays.equals(state, ((StateWrapper)o).state);
			else if (o instanceof State)
				return Arrays.equals(state, ((State)o).state);
			return false;
		}

		public int hashCode()
		{
			if (cachedHashCode != 0)
				return cachedHashCode;
			return cachedHashCode = Arrays.hashCode(state);
		}

		public String toString()
		{
			return Arrays.toString(state);
		}

		public Neighbours getNeighbours()
		{
			Neighbours ns = null;
			if (neighbours != null)
				ns = neighbours.get();
			if (ns == null) {
				ns = findNeighbours(this);
				neighbours = new SoftReference<>(ns);
			}
			return ns;
		}

		public double getProbTo(State state)
		{
			Neighbours nbs = getNeighbours();
			State[] ns = nbs.neighbours;
			double[] probs = nbs.probs;
			for (int i = ns.length - 1; i >= 0; i--)
				if (ns[i] == state)
					return probs[i];
			return 0;
		}

		public short getOrderTo(State state)
		{
			Neighbours nbs = getNeighbours();
			State[] ns = nbs.neighbours;
			short[] orders = nbs.orders;
			for (int i = ns.length - 1; i >= 0; i--)
				if (ns[i] == state)
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
	public class HPCState extends State {
		public final Neighbours origNeighbours;
		public final double[] meanTimes;
		private final Neighbours lockedNeighbours; /* Prevent the soft
							      reference to the
							      neighbours array
							      being freed */
		public HPCState(State orig, State[] ns, short[] os, double[] ps)
		{
			this(orig, ns, os, ps, null);
		}

		public HPCState(State orig, State[] ns, short[] os, double[] ps, double[] mt)
		{
			super(orig.state);
			Neighbours prevNs = orig.getNeighbours();
			origNeighbours = new Neighbours(prevNs.neighbours, null, prevNs.probs, prevNs.exitRate);
			lockedNeighbours = explored(this, ns, os, ps, prevNs.exitRate);
			meanTimes = mt;
		}
	}

	private WeakHashMap<State, State> knownStates;
	private volatile State initialState;
	public final double epsilon;
	/* Locks are public since HPC removal needs synchronization
	 * across multiple state updates.
	 */
	public Lock writeLock, readLock;

	public StateSpace(double epsilon, int[] initialState) {
		this.epsilon = epsilon;
		knownStates = new WeakHashMap<>();
		ReentrantReadWriteLock locks = new ReentrantReadWriteLock();
		writeLock = locks.writeLock();
		readLock = locks.readLock();
		this.initialState = findOrCreate(initialState);
	}

	/** Follows the behaviour of snapshot() */
	protected StateSpace(StateSpace other) {
		knownStates = new WeakHashMap<>(other.knownStates);
		epsilon = other.epsilon;
		ReentrantReadWriteLock locks = new ReentrantReadWriteLock();
		writeLock = locks.writeLock();
		readLock = locks.readLock();
		initialState = other.initialState;
	}

	public int size() {
		return knownStates.size();
	}

	public void cleanupHPCs()
	{
		StateWrapper w = cachedWrapper.get();
		if (w == null) {
			w = new StateWrapper(null);
			cachedWrapper.set(w);
		}
		try {
			writeLock.lock();
			for (State s : knownStates.keySet()) {
				Neighbours nbs = null;
				if (s.neighbours != null)
					nbs = s.neighbours.get();
				if (nbs == null)
					continue;
				State[] arr = nbs.neighbours;
				for (int i = 0; i < arr.length; i++) {
					w.state = arr[i].state;
					arr[i] = knownStates.get(w);
				}
				if (!(s instanceof HPCState))
					continue;
				nbs = ((HPCState)s).origNeighbours;
				arr = nbs.neighbours;
				for (int i = 0; i < arr.length; i++) {
					w.state = arr[i].state;
					arr[i] = knownStates.get(w);
				}
			}
		} finally {
			writeLock.unlock();
		}
	}

	public HPCState addHPC(State orig, State[] newNeighbours,
	                   short[] newOrders, double[] newProbs,
			   double[] meanTimes)
	{
		if (orig instanceof HPCState)
			return (HPCState) orig;
		try {
			writeLock.lock();
			HPCState n = new HPCState(orig, newNeighbours,
			                          newOrders, newProbs,
			                          meanTimes);
			knownStates.remove(orig);
			knownStates.put(n, n);
			if (orig == initialState)
				initialState = n;
			return n;
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

	public State find(State x) {
		try {
			readLock.lock();
			return knownStates.get(x);
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
				if (s == null) {
					s = new State(x);
					knownStates.put(s, s);
				}
			} finally {
				writeLock.unlock();
			}
		}
		return s;
	}

	protected static Neighbours explored(State s, State[] neighbours,
	                                     short[] orders, double[] probs,
	                                     double exitRate)
	{
		Neighbours ns = new Neighbours(neighbours, orders, probs,
		                               exitRate);
		s.neighbours = new SoftReference<Neighbours>(ns);
		return ns;
	}

	public State getInitialState()
	{
		return initialState;
	}

	/** Explore the specified state and return its neighbours.
	 *
	 * Ideally, should return the result of explored(x, ...), but this
	 * is not strictly required.
	 */
	protected abstract Neighbours findNeighbours(State x);

	public String stateString(State state) {
		return Arrays.toString(state.state);
	}

	public Number getVarValue(String variable, State state) {
		return null;
	}
}
