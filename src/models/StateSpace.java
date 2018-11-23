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
	static class StateWrapper
	{
		public int[] state;

		public StateWrapper(int[] s)
		{
			state = s;
		}

		public boolean equals(Object o)
		{
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
	private HashMap<StateWrapper, Integer> knownStates;
	private final StateSpace parent;
	public List<int[]> states;

	public final double epsilon;

	public double exitRates[];
	public List<int[]> successors;
	public List<int[]> orders;
	public List<double[]> probs;
	public BitSet inHPC;

	public class HPCState {
		public int num;
		public int[] successors;
		public double[] probs;
		
		public HPCState clone()
		{
			HPCState ret = new HPCState();
			ret.successors = successors.clone();
			ret.probs = probs.clone();
			ret.num = num;
			return ret;
		}
	}
	public HashMap<Integer, HPCState> hpcs;
	
	public int getOrder(int s, int z) {
		int[] nbs = successors.get(s);
		for(int i=0;i<nbs.length;i++) {
			if(nbs[i] == z) return orders.get(s)[i];
		}
		return Integer.MAX_VALUE;
	}
	
	public double getProb(int s, int z) {
		int[] nbs = successors.get(s);
		for(int i=0;i<nbs.length;i++) {
			if(nbs[i] == z) return probs.get(s)[i];
		}
		return 0;
	}

	private void reserve(int[] x) {
		knownStates.put(new StateWrapper(x), states.size());
		states.add(x);
		int p = states.size();
		if (exitRates.length < p)
			exitRates = Arrays.copyOf(exitRates, p * 2);
		successors.add(null);
		orders.add(null);
		probs.add(null);
	}

	public StateSpace(double epsilon, int[] initialState) {
		this.epsilon = epsilon;
		knownStates = new HashMap<StateWrapper, Integer>();
		states = new ArrayList<int[]>();
		successors = new ArrayList<int[]>();
		orders = new ArrayList<int[]>();
		probs = new ArrayList<double[]>();
		exitRates = new double[1024];
		inHPC = new BitSet();
		hpcs = new HashMap<Integer, HPCState>();
		parent = null;

		reserve(initialState);
	}

	/** Follows the behaviour of snapshot() */
	protected StateSpace(StateSpace other) {
		parent = other;
		exitRates = other.exitRates.clone();
		knownStates = new HashMap<>(other.knownStates);
		states = new ArrayList<>(other.states);
		successors = new ArrayList<>(other.successors);
		orders = new ArrayList<>(other.orders);
		probs = new ArrayList<>(other.probs);
		inHPC = (BitSet)other.inHPC.clone();

		hpcs = new HashMap<Integer, HPCState>();
		for (Map.Entry<Integer, HPCState> e : other.hpcs.entrySet())
			hpcs.put(e.getKey(), e.getValue().clone());
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

	public void addHPC(int x) {
		if(!inHPC.get(x)) {
			inHPC.set(x);
			HPCState s = new HPCState();
			// deep copy [Enno: Not sure why]
			s.successors = successors.get(x).clone();
			s.probs = probs.get(x).clone();
			s.num = hpcs.size();
			hpcs.put(x, s);
		}
	}

	private ThreadLocal<StateWrapper> cachedWrapper = new ThreadLocal<>();

	public int find(int[] x) {
		StateWrapper w = cachedWrapper.get();
		if (w == null) {
			w = new StateWrapper(x);
			cachedWrapper.set(w);
		}
		w.state = x;
		Integer z = knownStates.get(w);
		if (z != null)
			return z;
		else
			return -1;
	}

	protected int findOrCreate(int[] x) {
		int s = find(x);
		if(s == -1) {
			reserve(x);
			return size()-1;
		}
		return s;
	}

	public int[] getInitialState()
	{
		return states.get(0);
	}

	/** Instantiate the relevant fields for the specified state.
	 *
	 * This method should populate the s'th entries of successors,
	 * orders, and probs with correctly filled arrays, and set
	 * exitRates[s] to the correct value.
	 */
	public abstract void findNeighbours(int x);

	public String stateString(int state) {
		return "state "+ state +", ="+Arrays.toString(states.get(state));
	}

	public Number getVarValue(String variable, int state) {
		return null;
	}
}
