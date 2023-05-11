package algorithms;
import models.StateSpace;
import models.StateSpace.State;
import models.StateSpace.Neighbours;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class SearchAlgorithm {
	private Set<State> Lambda;
	private Set<State> Gamma;
	private Set<State> nonHPCs = new HashSet<>();
	private HashMap<State, ArrayList<State>> predecessors;
	private HashSet<State> neighboursSet = new HashSet<>();
	private final StateSpace model;
	private final Property prop;
	private static final boolean VERBOSE = false;

	private HashMap<State, Integer> dp;
	public HashMap<State, Integer> d;

	private final boolean trace;
	
	public SearchAlgorithm(StateSpace m, boolean t, Property prop) {
		this.model = m;
		trace = t;
		this.prop = prop;
	}
	
	public SearchAlgorithm(StateSpace m, Property prop) {
		this(m, false, prop);
	}

	public HashMap<State, Double> runAlgorithm() {
		dp = new HashMap<>();
		predecessors = new HashMap<>();
		forwardPhase();
		model.cleanupHPCs();
		HashMap<State, Double> ret = backwardPhase();
		dp = null;
		return ret;
	}

	private Neighbours findNeighbours(StateSpace.State state)
	{
		Neighbours ret = state.getNeighbours();
		if (dp.get(state) == null)
			dp.put(state, Integer.MAX_VALUE);
		if (!neighboursSet.contains(state)) {
			for (State zz : ret.neighbours) {
				ArrayList<State> preds = predecessors.get(zz);
				if (preds == null) {
					preds = new ArrayList<>();
					predecessors.put(zz, preds);
				}
				if (!dp.containsKey(zz))
					dp.put(zz, Integer.MAX_VALUE);
				preds.add(state);
			}
			neighboursSet.add(state);
		}
		return ret;
	}

	/* Returns whether any changes were actually made. */
	private boolean removeHpc(State s) {
		if (nonHPCs.contains(s))
			return false;
		HashSet<State> A = new HashSet<>();
		HashSet<State> B = new HashSet<>();
		A.add(s);
		B.add(s);
		ArrayDeque<State> pot_A = new ArrayDeque<>();
		ArrayDeque<State> pot_B = new ArrayDeque<>();
		pot_A.add(s);
		pot_B.add(s);

		// What follows is actually not the exact implementation described lines 1-16 of Algorithm 2 in the paper - we do not do A 
		// and B at the same time as in lines 4-8, but rather first A and then B. This may result in the algorithm not terminating 
		// within finite time if A is infinite.
		if (VERBOSE && Simulator.showProgress)
			System.err.print("\nRemoving HPC.");
		
		while (!pot_A.isEmpty()) {
			State x = pot_A.poll();
			if (Simulator.showProgress && (A.size() % 32768) == 0)
				System.err.format("\rRemoving HPC, A: %d", A.size());
			Neighbours nb = findNeighbours(x);
			for (int i = 0; i < nb.neighbours.length; i++) {
				if (nb.orders[i] > 0)
					continue;
				State z = nb.neighbours[i];
				if (prop.isBlue(model, z))
					continue;
				if (A.add(z))
					pot_A.add(z);
			}
		}

		ArrayList<State> preds;
		while (!pot_B.isEmpty()) {
			State z = pot_B.poll();
			if (Simulator.showProgress && (B.size() % 32768) == 0)
				System.err.format("\rRemoving HPC, A: %d, B %d", A.size(), B.size());
			preds = predecessors.get(z);
			for (State x : preds) {
				if (x.getOrderTo(z) != 0)
					continue;
				if (!A.contains(x) || prop.isBlue(model, x))
					continue;
				if (B.add(x))
					pot_B.add(x);
			}
		}

		if (trace)
			System.out.println("A: "+A);
		HashSet<State> Ls = A;
		Ls.retainAll(B);
		HashSet<State> Ds = new HashSet<>();
		if (trace) {
			System.out.println("B: "+B);
			System.out.println("pred: "+predecessors);
			System.out.println("L: "+Ls);
			if (Ls.size() > 5)
				System.out.println("HPC starting in state "
				                   +s+", size "+Ls.size());
			else
				System.out.println("HPC in states "+Ls);
		}
		if (Ls.size() == 1) {
			if (VERBOSE && Simulator.showProgress)
				System.err.format("\r%s Found not to be HPC.\n", s);
			nonHPCs.add(s);
			return false; // false alarm: HPC consists of one state only
		}
		if (VERBOSE && Simulator.showProgress)
			System.err.format("\rRemoving HPC from %s, size %d\n", s, Ls.size());

		A = B = null;
		for (State x : Ls) {
			Neighbours xn = findNeighbours(x);
			for (State z : xn.neighbours) {
				if (!Ls.contains(z)) {
					Ds.add(z);
					if(trace)
						System.out.println("in D: "+z);
				}
			}
		}
		if (Ds.size() == 0)
			System.err.println("HPC without destinations.");

		State[] D = Ds.toArray(new State[Ds.size()]);
		Ds = null;
		State[] L = Ls.toArray(new State[Ls.size()]);
		Ls = null;

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
		short[] orders = new short[D.length];
		Arrays.fill(orders, Short.MAX_VALUE);

		Ls = null;

		int i = 0;
		for (State l : L) {
			int j = 0;
			for (State z : L)
				T[i][j++] = l.getProbTo(z);
			for (j = 0; j < D.length; j++) {
				State d = D[j];
				T[i][j + L.length] = l.getProbTo(d);
				if (T[i][j + L.length] > 0) {
					short ord = l.getOrderTo(d);
					if (ord < orders[j])
						orders[j] = ord;
				}
			}
			i++;
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

		double[][] meanTimes = null;
		if (L.length == 2) {
			/* For 2 we can solve exactly (probably also for
			 * slightly larger ones but I can't be bothered
			 * to implement them.
			 */
			meanTimes = new double[2][D.length];
			Neighbours nb0 = L[0].getNeighbours();
			Neighbours nb1 = L[1].getNeighbours();
			for (i = 0; i < D.length; i++) {
				double v;
				double m0 = 1 / nb0.exitRate;
				double m1 = 1 / nb1.exitRate;
				double P01 = L[0].getProbTo(L[1]);
				double P10 = L[1].getProbTo(L[0]);
				double P0sink = T[0][L.length + i];
				double P1sink = T[1][L.length + i];
				v = m0 + P01 * P1sink * m1 / P0sink;
				v /= (1 - P01 * P10);
				meanTimes[0][i] = v;

				v = m1 + P10 * P0sink * m0 / P1sink;
				v /= (1 - P10 * P01);
				meanTimes[1][i] = v;
			}
		}
		
		// We then reroute the transitions within the states in L
		
		int minOrder = Integer.MAX_VALUE;
		for (int o : orders) {
			if (o < minOrder)
				minOrder = o;
		}
		if (trace)
			System.err.println("Minimal order: " + minOrder);

		for (i = 0; i < L.length; i++) {
			State l = L[i];
			double[] prbs = new double[D.length];
			double[] mt = null;
			if (meanTimes != null)
				mt = meanTimes[i];
			for(int j=0;j<D.length;j++)
				prbs[j] = T[i][L.length + j];
			model.addHPC(l, D, orders, prbs, mt);
		}
		return true;
	}

	private double[][] solveEventualProbabilities(int maxIts, double[][] T)
	{
		ReachabilitySolver solv = new ReachabilitySolver(T);
		return solv.solve(maxIts);
	}

	private void startExplorers(LinkedBlockingDeque<Object> q)
	{
		int cores = Simulator.coresToUse - 1;
		Runnable explorer = new Runnable() {
			public void run() {
				Object o = null;
				while (true) {
					State s;
					try {
						o = q.take();
					} catch (InterruptedException e) {
					}
					if (o instanceof State) {
						((State)o).getNeighbours();
					} else {
						q.push(o);
						return;
					}
				}
			}
		};
		for (int i = 0; i < cores; i++)
			new Thread(explorer).start();
	}

	private void forwardPhase() {
		ArrayDeque<State> current = new ArrayDeque<>();
		LinkedBlockingDeque<Object> needsExploration = null;
		State x = model.getInitialState();
		predecessors.put(x, new ArrayList<>());
		dp.put(x, 0);
		/* Invariant: A state is either done, current, or unexplored.
		 */
		HashSet<State> done = new HashSet<>();
		int dReach = Integer.MAX_VALUE, dCur = 0;
		State[] skipNeighbours = new State[0];

		if (Simulator.coresToUse > 1) {
			needsExploration = new LinkedBlockingDeque<>();
			startExplorers(needsExploration);
		}

		while(x != null && dCur <= dReach) {
			Neighbours nbdata = null;
			State[] nbs = skipNeighbours;
			if (done.add(x)) {
				nbdata = findNeighbours(x);
				nbs = nbdata.neighbours;
			}

			for (int i = 0; i < nbs.length; i++) {
				State z = nbs[i];
				if (z instanceof StateSpace.HPCState)
					continue;
				int dZ = dCur + nbdata.orders[i];
				Integer oldDp = dp.get(z);
				if (dZ < oldDp)
					dp.put(z, dZ);
				else
					dZ = oldDp;
				if (dZ == dCur) {
					if (needsExploration != null)
						needsExploration.push(z);
					current.add(z);
				}
				if (dZ < dReach && prop.isRed(model, z))
					dReach = dZ;
				if (done.contains(z) && dZ == dCur) {
					/* Possible HPC */
					if (removeHpc(z)) {
						x = model.find(x);
						nbdata = findNeighbours(x);
						nbs = nbdata.neighbours;
						i = -1;
					}
					if (Simulator.showProgress)
						System.err.format("\rForward search: %d states (distance %d)", model.size(), dCur);
				}
			}
			if (!current.isEmpty()) {
				x = current.poll();
			} else {
				ArrayList<State> toExplore = null;
				if (needsExploration != null) {
					toExplore = new ArrayList<>();
					needsExploration.clear();
				}
				dCur = dReach;
				for (State z : dp.keySet()) {
					if (done.contains(z))
						continue;
					int dZ = dp.get(z);
					if (dZ < dCur) {
						if (toExplore != null)
							toExplore.clear();
						current.clear();
						dCur = dZ;
					}
					if (dZ == dCur) {
						if (toExplore != null)
							toExplore.add(z);
						current.add(z);
					}
				}
				if (toExplore != null)
					needsExploration.addAll(toExplore);
				if (current.isEmpty())
					x = null;
				else
					x = current.poll();
			}
			if (trace && x != null)
				System.out.format("fwd (%d): %s\n", dp.get(x), x);
			if (Simulator.showProgress && ((dp.size() % 32768) == 0))
				System.err.format("\rForward search: %d states (distance %d)", dp.size(), dCur);
		}

		if (Simulator.coresToUse > 1) {
			needsExploration.clear();
			needsExploration.push(0);
		}

		if (Simulator.showProgress)
			System.err.println("\nForward search completed, explored " + model.size() + " states, minimal distance " + dReach);
		Gamma = new HashSet<State>();
		Lambda = new HashSet<State>();
		for (State z : dp.keySet()) {
			if (dp.get(z) <= dReach)
				Lambda.add(z);
			else
				Gamma.add(z);
		}
	}

	private HashMap<State, Double> backwardPhase() {
		d = new HashMap<State, Integer>();
		HashMap<State, Double> v = new HashMap<>();
		if(trace) System.out.println("-----"+Lambda.size()+", "+Gamma.size());
		HashSet<State> lambdaP = new HashSet<>();
		HashSet<State> potentials = new HashSet<>();
		HashSet<State> redsAndGamma = new HashSet<>();
		
		Double one = 1.0;
		Double zero = 0.0;
		for (State st : Lambda) {
			if(prop.isRed(model, st)) {
				v.put(st, one);
				d.put(st, 0);
				redsAndGamma.add(st);
			} else {
				v.put(st, zero);
				d.put(st, Integer.MAX_VALUE);
				if (!prop.isBlue(model, st))
					potentials.add(st);
			}
		}

		for (State st : Gamma) {
			d.put(st, 0);
			v.put(st, one);
			redsAndGamma.add(st);
		}
		one = null;
		if(trace) System.out.println("Reds and Gamma size: "+redsAndGamma.size());

		int counter = 0;

		// first: reds and Gamma

		for (State x : redsAndGamma) {
			if (trace) {
				System.out.println("*  state "+x);
				if(counter % 100 == 0)
					System.out.println("count: "+counter+": "+x);
				counter++;
			}

			for (State z : predecessors.get(x)) {
				int dZ = d.get(x) + z.getOrderTo(x);
				if (dZ < d.get(z)) {
					v.put(z, zero);
					d.put(z, dZ);
				}
				if(d.get(z) == dZ && !prop.isRed(model, z)) {
					double vZ = v.get(z);
					double vX = v.get(x);
					double pZX = z.getProbTo(x);
					vZ = Math.fma(vX, pZX, vZ);
					v.put(z, vZ);
				}
			}
		}

		ArrayDeque<State> currentSuitables = new ArrayDeque<>();
		HashSet<State> suitables = new HashSet<>();
		int dCur = Integer.MAX_VALUE;

		for (State z : potentials) {
			if (d.get(z) == Integer.MAX_VALUE)
				continue;
			boolean suitable = true;
			Neighbours es = findNeighbours(z);
			for (State x : es.neighbours) {
				if (!Lambda.contains(x))
					continue;
				if (redsAndGamma.contains(x))
					continue;
				if (prop.isBlue(model, x))
					continue;
				int dXZ = x.getOrderTo(z);
				if (dXZ > 0)
					continue;
				suitable = false;
				break;
			}
			if (suitable) {
				suitables.add(z);
				int dZ = d.get(z);
				if (dZ < dCur) {
					dCur = dZ;
					currentSuitables.clear();
				}
				if (dZ == dCur)
					currentSuitables.add(z);
			}
		}

		// then: Lambda

		while(true) {
			if (currentSuitables.isEmpty()) {
				dCur = Integer.MAX_VALUE;
				if (suitables.isEmpty())
					break;
				for (State s : suitables) {
					int dS = d.get(s);
					if (dS < dCur) {
						dCur = dS;
						currentSuitables.clear();
					}
					if (dS == dCur)
						currentSuitables.add(s);
				}
			}
			State x = currentSuitables.poll();

			if (trace) {
				System.out.println("** state "+x+": d="+d.get(x));
				counter++;
				//System.out.println("count: "+counter+": "+x);
			}
			lambdaP.add(x);
			suitables.remove(x);

			for (State z : predecessors.get(x)) {
				//System.out.println(x+", "+z+": "+X.size());
				int dZ = d.get(x) + z.getOrderTo(x);
				if (dZ < d.get(z)) {
					suitables.add(z);
					v.put(z, zero);
					d.put(z, dZ);
					if (dZ < dCur) {
						currentSuitables.clear();
						dCur = dZ;
					}
					if (dZ == dCur)
						currentSuitables.add(z);
				}
				if (d.get(z) == dZ && !prop.isRed(model, z)) {
					double vZ = v.get(z);
					double vX = v.get(x);
					double pZX = z.getProbTo(x);
					vZ = Math.fma(vX, pZX, vZ);
					v.put(z, vZ);
				}

				if (suitables.contains(z)
				    || lambdaP.contains(z)
				    || !potentials.contains(z))
					continue;
				boolean suitable = true;
				for (State xx : z.getNeighbours().neighbours) {
					if (!suitables.contains(xx))
						continue;
					int rzxx = z.getOrderTo(xx);
					if (!(prop.isBlue(model, xx) || lambdaP.contains(xx) || redsAndGamma.contains(xx) || rzxx > 0)) {
						suitable = false;
						break;
					}
				}
				if (suitable) {
					suitables.add(z);
					int md = d.get(z);
					if (md < dCur) {
						dCur = md;
						currentSuitables.clear();
					}
					if (md == dCur)
						currentSuitables.add(z);
				}
			}
		}

		// finallY: reset blue states
		for(State z : Lambda) {
			if(prop.isBlue(model, z))
				v.put(z, zero);
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
