package algorithms;
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

public class MainAlgorithm {

	public double epsilon;
	
	public Set<Integer> Lambda;
	public Set<Integer> LambdaQ;
	public Set<Integer> Gamma;
	public Set<Integer> GammaQ;
	public ModelGenerator generator;
	
	boolean trace;
	
	public MainAlgorithm(ModelGenerator g, boolean t) {
		this.generator = g;
		trace = t;
	}
	
	public MainAlgorithm(ModelGenerator g) {
		this(g, false);
	}
	
	public void setTrace(boolean b) {
		trace = b;
	}
	
	public void runAlgorithm() {
		forwardPhase();
		//backwardPhase(this.Lambda, this.Gamma);
	}

	/* Returns whether any changes were actually made. */
	public boolean removeHpc(int s) {
		HashSet<Integer> A = new HashSet<Integer>();
		HashSet<Integer> B = new HashSet<Integer>();
		ArrayList<Integer> pot_A = new ArrayList<Integer>();
		ArrayList<Integer> pot_B = new ArrayList<Integer>();
		pot_A.add(s);
		pot_B.add(s);
		StateSpace X = generator.X;
		
		//X.printFullStateSpace();
		
		// What follows is actually not the exact implementation described lines 1-16 of Algorithm 2 in the paper - we do not do A 
		// and B at the same time as in lines 4-8, but rather first A and then B. This may result in the algorithm not terminating 
		// within finite time if A is infinite.
		
		while(pot_A.size()>0) {
			//System.out.println("A: "+A+", pot_A:"+pot_A);
			for(int j=pot_A.size()-1;j>=0;j--) {
				int x = pot_A.remove(j);
				A.add(x);
				for(int i=0;i<X.successors.get(x).length;i++) {//Edge e : x.outgoingEdges) {
					int z = X.successors.get(x)[i];
					if(X.getOrder(x,z) == 0 && !generator.isBlue(x) && !A.contains(z) && !pot_A.contains(z) && z > -1) {
						if(generator.X.successors.get(z) == null) generator.findNeighbours(z);
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
				for (int x : predecessors.get(z)) {
					if(X.getOrder(x,z) != 0)
					      continue;
					if (generator.isBlue(x))
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
		Ls = A; A = null;
		Ls.retainAll(B); B = null;
		if(trace) System.out.println("L: "+Ls);
		if(Ls.size() == 1) return false; // false alarm: HPC consists of one state only
		if(Ls.size() > 5 && trace) System.out.println("HPC starting in state "+s+", size "+Ls.size());
		else if (trace) {System.out.println("HPC in states "+Ls);}
		for(Integer x : Ls) {
			for(Integer z : X.successors.get(x)) {
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
			int[] succs = X.successors.get(x);
			X.addHPC(x);
			for(int j=0;j<L.length;j++) {
				int z = L[j];
				T[i][j] = X.getProb(x, z);
			}
			for(int j=0;j<D.length;j++) {
				int z = D[j];
				T[i][j + L.length] = X.getProb(x, z);
				if (T[i][j + L.length] > 0)
					orders[j] = Math.min(orders[j], X.getOrder(x, z));
			}
			X.orders.set(x, orders);
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
			X.successors.set(x, D);
			X.probs.set(x, prbs);
		}

		for (int z : D) {
			if(X.dp[z] < Integer.MAX_VALUE)
				X.dp[z] = X.dp[z] - minOrder;
		}
		return true;
	}

	private double[][] solveEventualProbabilities(int maxIts, double[][] T)
	{
		ReachabilitySolver solv = new ReachabilitySolver(T);
		return solv.solve(maxIts);
	}
	
	public void forwardPhase() {
		generator.initialise(); // if parts of the state space have already been generated, this must be undone
		ArrayDeque<Integer> current = new ArrayDeque<Integer>();
		BitSet iLambda = new BitSet();
		StateSpace X = generator.X;
		int x = 0;
		int dpxb = Integer.MAX_VALUE;
		int curDp = X.dp[0];
		int lowestNonLambda = 0;
		while(x > -1 && X.dp[x] <= dpxb) {
			boolean skip = false;
			if (iLambda.get(x))
				skip = true;
			iLambda.set(x);
			int[] nbs = generator.X.successors.get(x);
			if(nbs == null) {
				generator.findNeighbours(x);
				nbs = generator.X.successors.get(x);
			}

			for(int i=0;!skip && i<nbs.length;i++) {
				int z = nbs[i];
				if (z < 0)
					continue;
				if (X.inHPC.get(z))
					continue;
				int order = X.getOrder(x, z);
				int dp = Math.min(X.dp[z], X.dp[x] + order);
				X.dp[z] = dp;
				if (dp == curDp)
					current.add(z);
				if(dpxb == Integer.MAX_VALUE && generator.isRed(z))
					dpxb = dp;
				if(iLambda.get(z) && dp == X.dp[x]) {
					//System.out.println("Possible HPC!!! x = "+x+" = "+Arrays.toString(X.states.get(x))+", z = "+z+" = "+Arrays.toString(X.states.get(z)));
					if (removeHpc(z)) {
						nbs = generator.X.successors.get(x);
						i = -1;
					}
				}
			}
			if (!current.isEmpty()) {
				x = current.poll();
			} else {
				curDp = Integer.MAX_VALUE;
				int z = -1;
				while ((z = iLambda.nextClearBit(z + 1)) < X.size()) {
					int dp = X.dp[z];
					if (dp < curDp) {
						current.clear();
						curDp = dp;
					}
					if (dp == curDp)
						current.add(z);
				}
				if (current.isEmpty())
					x = -1;
				else
					x = current.poll();
			}
			if (trace && x >= 0)
				System.out.format("fwd (%d): %d\n", X.dp[x], x);
		}

		Gamma = new HashSet<Integer>();
		Lambda = new HashSet<Integer>();
		//assumes that the only states with a listing in X so far are either in Lambda or Gamma:
		for(int z=0; z<X.size(); z++) {
			if (iLambda.get(z))
				Lambda.add(z);
			else
				Gamma.add(z);
		}

	}

	public HashMap<Integer, int[]> determinePredecessors(
			final Set<Integer> states)
	{
		ArrayList<HashMap<Integer, ArrayList<Integer>>> intermediates;

		int cores = Runtime.getRuntime().availableProcessors();
		if (cores > 2)
			cores -= 1;
		final List<int[]> succs = generator.X.successors;
		intermediates = new ArrayList<>(cores);
		final int nStates = generator.X.size();
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

	public ArrayList<ArrayList<Integer>> determinePredecessors() {
		StateSpace X = generator.X;
		ArrayList<ArrayList<Integer>> predecessors = new ArrayList<ArrayList<Integer>>();
		for(int i = X.size(); i>= 0; i--) {
			predecessors.add(new ArrayList<Integer>(1));
		}

		for(Integer s = X.size() - 1; s >= 0; s--) {
			int[] succ = X.successors.get(s);
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

	public void backwardPhase() {
		backwardPhase(generator.X);
	}
	
	public void backwardPhase(StateSpace X) {
		if(trace) System.out.println("-----"+Lambda.size()+", "+Gamma.size());
		BitSet LambdaP = new BitSet();
		BitSet potentials = new BitSet();
		BitSet redsAndGamma = new BitSet();
		
		ArrayList<ArrayList<Integer>> predecessors = determinePredecessors();
		//System.out.println(predecessors);

		for (int s : Lambda) {
			if(generator.isRed(s)) {
				X.d[s] = 0; X.v[s] = 1;
				redsAndGamma.set(s);
			} else {
				X.d[s] = Integer.MAX_VALUE; X.v[s] = 0;
				if (!generator.isBlue(s))
					potentials.set(s);
			}
		}

		for (int s : Gamma) {
			X.d[s] = 0; X.v[s] = 1;
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
				int rzx = X.getOrder(z,x);//z.getTransitionOrder(x);
				if (rzx < Integer.MAX_VALUE) {
					if(rzx + X.d[x] < X.d[z]) {
						X.v[z] = 0;
					}
					X.d[z] = Math.min(X.d[z], rzx + X.d[x]);
					if(X.d[z] == X.d[x] + rzx && !generator.isRed(z)) {
						X.v[z] = X.v[z] + X.v[x] * X.getProb(z,x);
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
			for (int xx : X.successors.get(z)) {
				if(xx > -1 && Lambda.contains(xx)) {
					int rxxz = X.getOrder(xx,z);
					if (!(generator.isBlue(xx)
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
				if (X.d[z] < curDp) {
					curDp = X.d[z];
					currentSuitables.clear();
				}
				if (X.d[z] == curDp)
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
					if (X.d[x] < curDp) {
						curDp = X.d[x];
						currentSuitables.clear();
					}
					if (X.d[x] == curDp)
						currentSuitables.add(x);
				}
			}
			int x = currentSuitables.poll();
			int dMin = X.d[x];

			if (trace)
				System.out.println("** state "+x+": d="+dMin);
			//if(trace) System.out.println("count: "+counter+": "+x);
			if (trace)
				counter++;
			LambdaP.set(x);
			suitables.clear(x);

			for (int z : predecessors.get(x)) {
				//System.out.println(x+", "+z+": "+X.size());
				int rzx = X.getOrder(z,x);//z.getTransitionOrder(x);
				if(rzx < Integer.MAX_VALUE) {
					if(rzx + X.d[x] < X.d[z])
						X.v[z] = 0.;
					int old = X.d[z];
					int d = Math.min(old, rzx + X.d[x]);
					if (d != old) {
						X.d[z] =  d;
						if (d < curDp) {
							currentSuitables.clear();
							curDp = d;
						}
						if (d == curDp)
							currentSuitables.add(z);
					}
					if (d == X.d[x] + rzx && !generator.isRed(z)) {
						X.v[z] = X.v[z] + X.v[x] * X.getProb(z,x);
					}
				}
				if (suitables.get(z) || LambdaP.get(z) || !potentials.get(z))
					continue;
				boolean suitable = true;
				for (int xx : X.successors.get(z)) {
					if (xx < 0 || !Lambda.contains(xx))
						continue;
					int rxxz = X.getOrder(xx,z);
					if (!(generator.isBlue(xx) || LambdaP.get(xx) || redsAndGamma.get(xx) || rxxz > 0)) {
						suitable = false;
						break;
					}
				}
				if (suitable) {
					suitables.set(z);
					int d = X.d[z];
					if (d < curDp) {
						curDp = d;
						currentSuitables.clear();
					}
					if (d == curDp)
						currentSuitables.add(z);
				}
			}
		}

		// finallY: reset blue states
		for(int z : Lambda) {
			if(generator.isBlue(z)) {
				X.d[z] =  Integer.MAX_VALUE;
				X.v[z] =  0;
			}
		}
	}
	
	public void determineXUnderQ(Scheme scheme) {
		//scheme.init(scheme.rng, generator);
		generator.XUnderQ = generator.X.clone();
		
		for(int k=0; k<generator.X.probs.size();k++) {
			double[] probs = generator.X.probs.get(k);
			if(probs != null) {
				double[] probsQ = new double[probs.length];
				scheme.computeNewProbs(k);
				for(int i=0;i<probs.length;i++) {
					probsQ[i] = scheme.stateWeightsIS[i] / scheme.totalStateWeightIS;///= totProbQ;
				}
				generator.XUnderQ.probs.set(k, probsQ);
			}
		}
		
	}
}
