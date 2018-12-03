package algorithms;
import models.StateSpace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.PriorityQueue;
import java.util.Set;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class SearchAlgorithm {
	private Set<Integer> Lambda;
	private Set<Integer> Gamma;
	private final StateSpace model;
	private final Property prop;

	private int dp[];
	public int d[];

	private final boolean trace;
	
	public SearchAlgorithm(StateSpace m, boolean t, Property prop) {
		this.model = m;
		trace = t;
		this.prop = prop;
	}
	
	public SearchAlgorithm(StateSpace m, Property prop) {
		this(m, false, prop);
	}

	public double[] runAlgorithm() {
		dp = new int[200];
		forwardPhase();
		double[] ret = backwardPhase();
		dp = null;
		return ret;
	}

	private void findNeighbours(int state)
	{
		model.findNeighbours(state);
		if (dp.length < model.size()) {
			int len = dp.length;
			dp = Arrays.copyOf(dp, len * 2);
			Arrays.fill(dp, len, dp.length, Integer.MAX_VALUE);
		}
	}

	/* Returns whether any changes were actually made. */
	private boolean removeHpc(int s) {
		HashSet<Integer> A = new HashSet<Integer>();
		HashSet<Integer> B = new HashSet<Integer>();
		ArrayList<Integer> pot_A = new ArrayList<Integer>();
		ArrayList<Integer> pot_B = new ArrayList<Integer>();
		pot_A.add(s);
		pot_B.add(s);
		
		//X.printFullStateSpace();
		
		// What follows is actually not the exact implementation described lines 1-16 of Algorithm 2 in the paper - we do not do A 
		// and B at the same time as in lines 4-8, but rather first A and then B. This may result in the algorithm not terminating 
		// within finite time if A is infinite.
		if (Simulator.showProgress)
			System.err.print("\nRemoving HPC.");
		
		while(pot_A.size()>0) {
			//System.out.println("A: "+A+", pot_A:"+pot_A);
			for(int j=pot_A.size()-1;j>=0;j--) {
				int x = pot_A.remove(j);
				if (Simulator.showProgress && (A.size() % 32768) == 0)
					System.err.format("\rRemoving HPC, A: %d", A.size());
				A.add(x);
				for (int z : model.successors.get(x)) {
					if(model.getOrder(x,z) == 0
					   && !prop.isBlue(model, x)
					   && !A.contains(z)
					   && !pot_A.contains(z)
					   && z > -1)
					{
						if(model.successors.get(z) == null)
							findNeighbours(z);
						pot_A.add(z);
					}
				}
			}
		}

		HashMap<Integer, int[]> predecessors = determinePredecessors(A);

		while(pot_B.size()>0) {
			for(int j=pot_B.size()-1;j>=0;j--) {
				Integer z = pot_B.remove(j);
				B.add(z);
				if (Simulator.showProgress && (B.size() % 32768) == 0)
					System.err.format("\rRemoving HPC, A: %d, B %d", A.size(), B.size());
				for (int x : predecessors.get(z)) {
					if(model.getOrder(x,z) != 0)
					      continue;
					if (prop.isBlue(model, x))
						continue;
					Integer xx = x;
					if (A.contains(xx) && !B.contains(xx)
					    && !pot_B.contains(xx))
					{
						pot_B.add(xx);
					}
				}
			}
		}

		HashSet<Integer> Ls = new HashSet<Integer>();
		HashSet<Integer> Ds = new HashSet<Integer>();
		if(trace) System.out.println("A: "+A);
		if(trace) System.out.println("B: "+B);
		if(trace) System.out.println("pred: "+predecessors);
		Ls = A;
		Ls.retainAll(B);
		if(trace) System.out.println("L: "+Ls);
		if(Ls.size() == 1) {
			if (Simulator.showProgress)
				System.err.format("\r%d Found not to be HPC.\n", s);
			return false; // false alarm: HPC consists of one state only
		}
		if (Simulator.showProgress)
			System.err.format("\rRemoving HPC from %d, A: %d, B %d (final)\n", s, A.size(), B.size());
		if(Ls.size() > 5 && trace) System.out.println("HPC starting in state "+s+", size "+Ls.size());
		else if (trace) {System.out.println("HPC in states "+Ls);}
		A = B = null;
		for(Integer x : Ls) {
			for(Integer z : model.successors.get(x)) {
				if(!Ls.contains(z) && z > -1) {
					Ds.add(z);
					if(trace) System.out.println("in D: "+z);
				}
			}
		}
		if (Ds.size() == 0)
			System.err.println("HPC without destinations.");

		predecessors = null;
		int[] D = new int[Ds.size()];
		int[] L = new int[Ls.size()];
		int i = 0;
		for (int d : Ds)
			D[i++] = d;
		i = 0;
		for (int l : Ls)
			L[i++] = l;
		Ds = Ls = null;

		/* We find the transition matrix of the Markov chain
		 * formed by the HPC and its 1-step-reachable states.
		 * This matrix has the form
		 *
		 * T = [TI, TL
		 *       0,  1]
		 *
		 * where TI is the internal transition matrix (i.e.
		 * transitions from state i to state j where both i and
		 * j are in the HPC), and TL is the leaving transition
		 * matrix (i.e. transitions from state i to state j
		 * where i is in the HPC and j is outside of it.
		 * the bottom part indicates that once we leave the
		 * HPC, we stay the our new state permanently.
		 *
		 * Since the bottom part is constant, we don't bother to
		 * explicitly store it.
		 */

		double[][] T = new double[L.length][L.length + D.length];
		int[] orders = new int[D.length];
		Arrays.fill(orders, Integer.MAX_VALUE);
		
		for(i=0;i<L.length;i++) {
			int x = L[i];
			int[] succs = model.successors.get(x);
			model.addHPC(x);
			for(int j=0;j<L.length;j++) {
				int z = L[j];
				T[i][j] = model.getProb(x, z);
			}
			for(int j=0;j<D.length;j++) {
				int z = D[j];
				T[i][j + L.length] = model.getProb(x, z);
				if (T[i][j + L.length] > 0)
					orders[j] = Math.min(orders[j], model.getOrder(x, z));
			}
			model.orders.set(x, orders);
		}

		/* To calculate the probabilities of the outgoing states
		 * from the HPC, we approximate T^inf, and read off the
		 * right-hand side of that.
		 * We approximate until we either reach T^MAX_ITS, or
		 * until the probability masses on the left-hand side
		 * are sufficiently low.
		 */

		int MAX_ITS = Integer.MAX_VALUE;
		solveEventualProbabilities(MAX_ITS, T);
		
		// We then reroute the transitions within the states in L
		
		int minOrder = Integer.MAX_VALUE;
		for (int o : orders) {
			if (o < minOrder)
				minOrder = o;
		}
		if (trace)
			System.err.println("Minimal order: " + minOrder);
		for(i=0;i<orders.length;i++)
			orders[i] -= minOrder;


		for(i=0;i<L.length;i++) {
			int x = L[i];
			double[] prbs = new double[D.length];
			
			// add the new transitions
			
			for(int j=0;j<D.length;j++)
				prbs[j] = T[i][L.length + j];
			model.successors.set(x, D);
			model.probs.set(x, prbs);
		}

		for (int z : D) {
			if(dp[z] < Integer.MAX_VALUE)
				dp[z] = dp[z] - minOrder;
		}
		return true;
	}

	private double[][] solveEventualProbabilities(int maxIts, double[][] T)
	{
		ReachabilitySolver solv = new ReachabilitySolver(T);
		return solv.solve(maxIts);
	}
	
	private void forwardPhase() {
		ArrayDeque<Integer> current = new ArrayDeque<Integer>();
		BitSet iLambda = new BitSet();
		int x = 0;
		int dpxb = Integer.MAX_VALUE;
		int curDp = dp[0];
		int lowestNonLambda = 0;
		while(x > -1 && dp[x] <= dpxb) {
			boolean skip = false;
			if (iLambda.get(x))
				skip = true;
			iLambda.set(x);
			int[] nbs = model.successors.get(x);
			if(nbs == null) {
				findNeighbours(x);
				nbs = model.successors.get(x);
			}

			for(int i=0;!skip && i<nbs.length;i++) {
				int z = nbs[i];
				if (z < 0)
					continue;
				if (model.inHPC.get(z))
					continue;
				int order = model.getOrder(x, z);
				int dpx = Math.min(dp[z], dp[x] + order);
				dp[z] = dpx;
				if (dpx == curDp)
					current.add(z);
				if(dpxb == Integer.MAX_VALUE
				   && prop.isRed(model, z))
					dpxb = dpx;
				if(iLambda.get(z) && dpx == dp[x]) {
					//System.out.println("Possible HPC!!! x = "+x+" = "+Arrays.toString(X.states.get(x))+", z = "+z+" = "+Arrays.toString(X.states.get(z)));
					if (removeHpc(z)) {
						nbs = model.successors.get(x);
						i = -1;
					}
					if (Simulator.showProgress)
						System.err.format("\rForward search: %d states (distance %d)", model.size(), curDp);
				}
			}
			if (!current.isEmpty()) {
				x = current.poll();
			} else {
				curDp = Integer.MAX_VALUE;
				int z = -1;
				while ((z = iLambda.nextClearBit(z + 1)) < model.size()) {
					int dpz = dp[z];
					if (dpz < curDp) {
						current.clear();
						curDp = dpz;
					}
					if (dpz == curDp)
						current.add(z);
				}
				if (current.isEmpty())
					x = -1;
				else
					x = current.poll();
			}
			if (trace && x >= 0)
				System.out.format("fwd (%d): %d\n", dp[x], x);
			if (Simulator.showProgress && ((model.size() % 32768) == 0))
				System.err.format("\rForward search: %d states (distance %d)", model.size(), curDp);
		}

		if (Simulator.showProgress)
			System.err.println("\nForward search completed.");
		Gamma = new HashSet<Integer>();
		Lambda = new HashSet<Integer>();
		//assumes that the only states with a listing in X so far are either in Lambda or Gamma:
		for(int z=0; z<model.size(); z++) {
			if (iLambda.get(z))
				Lambda.add(z);
			else
				Gamma.add(z);
		}
	}

	private HashMap<Integer, int[]> determinePredecessors(
			final Set<Integer> states)
	{
		ArrayList<HashMap<Integer, ArrayList<Integer>>> intermediates;

		int cores = Runtime.getRuntime().availableProcessors();
		if (cores > 2)
			cores -= 1;
		final List<int[]> succs = model.successors;
		intermediates = new ArrayList<>(cores);
		final int nStates = model.size();
		int tmpMinState = Integer.MAX_VALUE, tmpMaxState = 0;
		for (Integer i : states) {
			if (i < tmpMinState)
				tmpMinState = i;
			if (i > tmpMaxState)
				tmpMaxState = i;
		}
		final int minState = tmpMinState, maxState = tmpMaxState;

		for (int i = 0; i < cores; i++) {
			final int lBound = i * (nStates / cores);
			int tmp = (i + 1) * (nStates / cores);
			if (i == (cores - 1))
				tmp = nStates;
			final int uBound = tmp;
			final int wNum = i;
			Runnable worker = new Runnable () {
				public void run() {
					HashMap<Integer, ArrayList<Integer>> r;
					r = new HashMap<>();
					for(int s = lBound; s < uBound; s++) {
						int[] succ = succs.get(s);
						if (succ == null)
							continue;
						for (int zz : succ) {
							if (zz < minState)
								continue;
							if (zz > maxState)
								continue;
							Integer z = zz;
							if (!states.contains(z))
								continue;
							ArrayList<Integer> l;
						       	l = r.get(z);
							if (l == null) {
								l = new ArrayList<>();
								r.put(z, l);
							}
							l.add(s);
						}
					}
					synchronized(intermediates) {
						intermediates.add(r);
						intermediates.notifyAll();
					}
				}
			};
			new Thread(worker).start();
		}

		synchronized(intermediates) {
			while (intermediates.size() < cores) {
				try {
					intermediates.wait();
				} catch (InterruptedException e) {
				}
			}
		}

		HashMap<Integer, int[]> ret = new HashMap<>();

		for (Integer s : states) {
			int size = 0;
			for (int i = 0; i < cores; i++) {
				ArrayList<Integer> tmp;
				tmp = intermediates.get(i).get(s);
				if (tmp != null)
					size += tmp.size();
			}
			int[] r = new int[size];
			for (int i = 0; i < cores; i++) {
				ArrayList<Integer> tmp;
				tmp = intermediates.get(i).remove(s);
				if (tmp == null)
					continue;
				for (Integer p : tmp)
					r[--size] = p;
			}
			ret.put(s, r);
		}

		return ret;
	}

	private ArrayList<ArrayList<Integer>> determinePredecessors() {
		ArrayList<ArrayList<Integer>> predecessors = new ArrayList<ArrayList<Integer>>();
		for(int i = model.size(); i>= 0; i--) {
			predecessors.add(new ArrayList<Integer>(1));
		}

		for(Integer s = model.size() - 1; s >= 0; s--) {
			int[] succ = model.successors.get(s);
			if (succ != null) {
				for(int j=0; j<succ.length; j++) {
					int z = succ[j];
					if(z > -1)
						predecessors.get(z).add(s);
				}
			}
		}

		return predecessors;
	}

	private double[] backwardPhase() {
		d = new int[model.size()];
		double v[] = new double[model.size()];
		if(trace) System.out.println("-----"+Lambda.size()+", "+Gamma.size());
		BitSet LambdaP = new BitSet();
		BitSet potentials = new BitSet();
		BitSet redsAndGamma = new BitSet();
		
		ArrayList<ArrayList<Integer>> predecessors = determinePredecessors();
		//System.out.println(predecessors);

		for (int s : Lambda) {
			if(prop.isRed(model, s)) {
				v[s] = 1;
				redsAndGamma.set(s);
			} else {
				d[s] = Integer.MAX_VALUE;
				if (!prop.isBlue(model, s))
					potentials.set(s);
			}
		}

		for (int s : Gamma) {
			v[s] = 1;
			redsAndGamma.set(s);
		}
		if(trace) System.out.println("Reds and Gamma size: "+redsAndGamma.size());

		int counter = 0;

		for(int i=0;i<Lambda.size();i++) {
			predecessors.add(new ArrayList<Integer>());
		}

		// first: reds and Gamma

		PrimitiveIterator.OfInt iter = redsAndGamma.stream().iterator();
		while (iter.hasNext()) {
			int x = iter.nextInt();
			if (trace) {
				System.out.println("*  state "+x);
				if(counter % 100 == 0)
					System.out.println("count: "+counter+": "+x);
				counter++;
			}
			
			for(int j=0;j<predecessors.get(x).size();j++) {
				int z = predecessors.get(x).get(j);
				int rzx = model.getOrder(z,x);//z.getTransitionOrder(x);
				if (rzx < Integer.MAX_VALUE) {
					if(rzx + d[x] < d[z]) {
						v[z] = 0;
					}
					d[z] = Math.min(d[z], rzx + d[x]);
					if(d[z] == d[x] + rzx && !prop.isRed(model, z)) {
						v[z] = v[z] + v[x] * model.getProb(z,x);
					}
				}
			}
		}

		ArrayDeque<Integer> currentSuitables = new ArrayDeque<Integer>();
		BitSet suitables = new BitSet();
		int curDp = Integer.MAX_VALUE;

		iter = potentials.stream().iterator();
		while (iter.hasNext()) {
			int z = iter.nextInt();
			boolean suitable = true;
			for (int xx : model.successors.get(z)) {
				if(xx > -1 && Lambda.contains(xx)) {
					int rxxz = model.getOrder(xx,z);
					if (!(prop.isBlue(model, xx)
					      || redsAndGamma.get(xx)
					      || rxxz > 0))
					{
						suitable = false;
						break;
					}
				}
			}
			if (suitable) {
				suitables.set(z);
				if (d[z] < curDp) {
					curDp = d[z];
					currentSuitables.clear();
				}
				if (d[z] == curDp)
					currentSuitables.add(z);
			}
		}

		// then: Lambda

		while(true) {
			if (currentSuitables.isEmpty()) {
				curDp = Integer.MAX_VALUE;
				iter = suitables.stream().iterator();
				if (!iter.hasNext())
					break;
				while (iter.hasNext()) {
					int x = iter.nextInt();
					if (d[x] < curDp) {
						curDp = d[x];
						currentSuitables.clear();
					}
					if (d[x] == curDp)
						currentSuitables.add(x);
				}
			}
			int x = currentSuitables.poll();
			int dMin = d[x];

			if (trace)
				System.out.println("** state "+x+": d="+dMin);
			//if(trace) System.out.println("count: "+counter+": "+x);
			if (trace)
				counter++;
			LambdaP.set(x);
			suitables.clear(x);

			for (int z : predecessors.get(x)) {
				//System.out.println(x+", "+z+": "+X.size());
				int rzx = model.getOrder(z,x);//z.getTransitionOrder(x);
				if(rzx < Integer.MAX_VALUE) {
					if(rzx + d[x] < d[z])
						v[z] = 0;
					int old = d[z];
					int md = Math.min(old, rzx + d[x]);
					if (md != old) {
						d[z] =  md;
						if (md < curDp) {
							currentSuitables.clear();
							curDp = md;
						}
						if (md == curDp)
							currentSuitables.add(z);
					}
					if (md == d[x] + rzx && !prop.isRed(model, z)) {
						v[z] = v[z] + v[x] * model.getProb(z,x);
					}
				}
				if (suitables.get(z) || LambdaP.get(z) || !potentials.get(z))
					continue;
				boolean suitable = true;
				for (int xx : model.successors.get(z)) {
					if (xx < 0 || !Lambda.contains(xx))
						continue;
					int rxxz = model.getOrder(xx,z);
					if (!(prop.isBlue(model, xx) || LambdaP.get(xx) || redsAndGamma.get(xx) || rxxz > 0)) {
						suitable = false;
						break;
					}
				}
				if (suitable) {
					suitables.set(z);
					int md = d[z];
					if (md < curDp) {
						curDp = md;
						currentSuitables.clear();
					}
					if (md == curDp)
						currentSuitables.add(z);
				}
			}
		}

		// finallY: reset blue states
		for(int z : Lambda) {
			if(prop.isBlue(model, z))
				v[z] = 0;
		}
		return v;
	}
	
	/*
	public StateSpace determineXUnderQ(Scheme scheme) {
		StateSpace XUnderQ = model.clone();
		
		for(int k=0; k<model.probs.size();k++) {
			double[] probs = generator.X.probs.get(k);
			if(probs != null) {
				double[] probsQ = new double[probs.length];
				scheme.computeNewProbs(k);
				for(int i=0;i<probs.length;i++) {
					probsQ[i] = scheme.stateWeightsIS[i] / scheme.totalStateWeightIS;
				}
				XUnderQ.probs.set(k, probsQ);
			}
		}
		return XUnderQ;
	}
	*/
}
