package algorithms;
import models.StateSpace;
import models.StateSpace.ExploredState;
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
import java.util.concurrent.LinkedBlockingDeque;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class SearchAlgorithm {
	private Set<Integer> Lambda;
	private Set<Integer> Gamma;
	private ArrayList<ArrayList<ExploredState>> predecessors;
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
		dp = new int[] {Integer.MAX_VALUE};
		predecessors = new ArrayList<>();
		forwardPhase();
		double[] ret = backwardPhase();
		dp = null;
		return ret;
	}

	private ExploredState findNeighbours(StateSpace.State state)
	{
		ExploredState ret = model.findNeighbours(state);
		if (dp.length < model.size()) {
			int len = dp.length;
			dp = Arrays.copyOf(dp, model.size() * 2);
			Arrays.fill(dp, len, dp.length, Integer.MAX_VALUE);
		}
		if (ret != state) {
			for (int zz : ret.neighbours) {
				while (predecessors.size() <= zz)
					predecessors.add(new ArrayList<>());
				predecessors.get(zz).add(ret);
			}
		}
		return ret;
	}

	/* Returns whether any changes were actually made. */
	private boolean removeHpc(ExploredState s) {
		HashSet<ExploredState> A = new HashSet<>();
		HashSet<ExploredState> B = new HashSet<>();
		A.add(s);
		B.add(s);
		ArrayDeque<ExploredState> pot_A = new ArrayDeque<>();
		ArrayDeque<ExploredState> pot_B = new ArrayDeque<>();
		pot_A.add(s);
		pot_B.add(s);

		// What follows is actually not the exact implementation described lines 1-16 of Algorithm 2 in the paper - we do not do A 
		// and B at the same time as in lines 4-8, but rather first A and then B. This may result in the algorithm not terminating 
		// within finite time if A is infinite.
		if (Simulator.showProgress)
			System.err.print("\nRemoving HPC.");
		
		while (!pot_A.isEmpty()) {
			ExploredState x = pot_A.poll();
			if (Simulator.showProgress && (A.size() % 32768) == 0)
				System.err.format("\rRemoving HPC, A: %d", A.size());
			for (int i = 0; i < x.neighbours.length; i++) {
				if (x.orders[i] > 0)
					continue;
				int z = x.neighbours[i];
				StateSpace.State zz = model.getState(z);
				if (prop.isBlue(model, zz))
					continue;
				ExploredState es = findNeighbours(zz);
				if (A.add(es))
					pot_A.add(es);
			}
		}

		ArrayList<ExploredState> preds;
		while (!pot_B.isEmpty()) {
			ExploredState z = pot_B.poll();
			if (Simulator.showProgress && (B.size() % 32768) == 0)
				System.err.format("\rRemoving HPC, A: %d, B %d", A.size(), B.size());
			preds = predecessors.get(z.number);
			for (ExploredState x : preds) {
				if (x.getOrderTo(z.number) != 0)
					continue;
				if (!A.contains(x) || prop.isBlue(model, x))
					continue;
				if (B.add(x))
					pot_B.add(x);
			}
		}

		if (trace)
			System.out.println("A: "+A);
		HashSet<ExploredState> Ls = A;
		Ls.retainAll(B);
		HashSet<StateSpace.State> Ds = new HashSet<>();
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
			if (Simulator.showProgress)
				System.err.format("\r%d Found not to be HPC.\n", s.number);
			return false; // false alarm: HPC consists of one state only
		}
		if (Simulator.showProgress)
			System.err.format("\rRemoving HPC from %d, size %d\n", s.number, Ls.size());

		A = B = null;
		for (ExploredState x : Ls) {
			for (int z : x.neighbours) {
				StateSpace.State zz = model.getState(z);
				if (!Ls.contains(zz)) {
					Ds.add(zz);
					if(trace)
						System.out.println("in D: "+z);
				}
			}
		}
		if (Ds.size() == 0)
			System.err.println("HPC without destinations.");

		int[] D = new int[Ds.size()];
		int[] L = new int[Ls.size()];
		int i = 0;
		for (StateSpace.State d : Ds)
			D[i++] = d.number;
		Ds = null;

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

		ExploredState[] La = Ls.toArray(new ExploredState[0]);
		Ls = null;

		i = 0;
		for (ExploredState l : La) {
			L[i] = l.number;
			int[] succs = l.neighbours;
			int j = 0;
			for (ExploredState z : La)
				T[i][j++] = l.getProbTo(z.number);
			for (j = 0; j < D.length; j++) {
				int d = D[j];
				T[i][j + L.length] = l.getProbTo(d);
				if (T[i][j + L.length] > 0) {
					short ord = l.getOrderTo(d);
					if (ord < orders[j])
						orders[j] = ord;
				}
			}
			i++;
		}
		Ls = null;

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
		if (La.length == 2) {
			meanTimes = new double[2][D.length];
			for (i = 0; i < D.length; i++) {
				double v;
				double m0 = 1 / La[0].exitRate;
				double m1 = 1 / La[1].exitRate;
				double P01 = La[0].getProbTo(La[1].number);
				double P10 = La[1].getProbTo(La[0].number);
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

		for (i = 0; i < La.length; i++) {
			ExploredState l = La[i];
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
					StateSpace.State s;
					try {
						o = q.take();
					} catch (InterruptedException e) {
					}
					if (o instanceof StateSpace.State) {
						s = (StateSpace.State)o;
						model.findNeighbours(s);
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
		ArrayDeque<StateSpace.State> current = new ArrayDeque<>();
		LinkedBlockingDeque<Object> needsExploration = null;
		StateSpace.State x = model.getInitialState();
		BitSet done = new BitSet();
		int dReach = Integer.MAX_VALUE, dCur = 0;
		int minUnexpl = 0;
		int[] skipNeighbours = new int[0];

		if (Simulator.coresToUse > 1) {
			needsExploration = new LinkedBlockingDeque<>();
			startExplorers(needsExploration);
		}

		while(x != null && dCur <= dReach) {
			ExploredState es = null;
			int[] nbs = skipNeighbours;
			if (!done.get(x.number)) {
				x = es = findNeighbours(x);
				nbs = es.neighbours;
			}
			done.set(x.number);

			for (int i = 0; i < nbs.length; i++) {
				StateSpace.State nb = model.getState(nbs[i]);
				if (nb instanceof StateSpace.HPCState)
					continue;
				int z = nb.number;
				int dZ = dCur + es.orders[i];
				if (dZ < dp[z])
					dp[z] = dZ;
				else
					dZ = dp[z];
				if (dZ == dCur) {
					if (needsExploration != null)
						needsExploration.push(nb);
					current.add(nb);
				}
				if (dZ < dReach && prop.isRed(model, nb))
					dReach = dZ;
				if (done.get(nb.number) && dZ == dCur) {
					assert(nb instanceof ExploredState);
					/* Possible HPC */
					if (removeHpc((ExploredState)nb)) {
						x = model.getState(x.number);
						assert(x instanceof ExploredState);
						es = (ExploredState)x;
						nbs = es.neighbours;
						i = -1;
					}
					if (Simulator.showProgress)
						System.err.format("\rForward search: %d states (distance %d)", model.size(), dCur);
				}
			}
			if (!current.isEmpty()) {
				x = current.poll();
			} else {
				ArrayList<StateSpace.State> toExplore = null;
				if (needsExploration != null)
					toExplore = new ArrayList<>();
				needsExploration.clear();
				dCur = dReach;
				int i = done.nextClearBit(minUnexpl);
				minUnexpl = i;
				while (i < model.size()) {
					int dZ = dp[i];
					if (dZ < dCur) {
						if (toExplore != null)
							toExplore.clear();
						current.clear();
						dCur = dZ;
					}
					if (dZ == dCur) {
						StateSpace.State zz = model.getState(i);
						if (toExplore != null)
							toExplore.add(zz);
						current.add(zz);
					}
					i = done.nextClearBit(i + 1);
				}
				if (toExplore != null)
					needsExploration.addAll(toExplore);
				if (current.isEmpty())
					x = null;
				else
					x = current.poll();
			}
			if (trace && x != null)
				System.out.format("fwd (%d): %s\n", dp[x.number], x);
			if (Simulator.showProgress && ((model.size() % 32768) == 0))
				System.err.format("\rForward search: %d states (distance %d)", model.size(), dCur);
		}

		if (Simulator.coresToUse > 1) {
			needsExploration.clear();
			needsExploration.push(0);
		}

		if (Simulator.showProgress)
			System.err.println("\nForward search completed, explored " + model.size() + " states, minimal distance " + dReach);
		Gamma = new HashSet<Integer>();
		Lambda = new HashSet<Integer>();
		//assumes that the only states with a listing in X so far are either in Lambda or Gamma:
		for(int z=0; z<model.size(); z++) {
			if (model.getState(z) instanceof ExploredState)
				Lambda.add(z);
			else
				Gamma.add(z);
		}
	}

	private double[] backwardPhase() {
		d = new int[model.size()];
		double v[] = new double[model.size()];
		if(trace) System.out.println("-----"+Lambda.size()+", "+Gamma.size());
		BitSet LambdaP = new BitSet();
		BitSet potentials = new BitSet();
		BitSet redsAndGamma = new BitSet();
		
		//System.out.println(predecessors);

		for (int s : Lambda) {
			StateSpace.State st = model.getState(s);
			if(prop.isRed(model, st)) {
				v[s] = 1;
				redsAndGamma.set(s);
			} else {
				d[s] = Integer.MAX_VALUE;
				if (!prop.isBlue(model, st))
					potentials.set(s);
			}
		}

		for (int s : Gamma) {
			v[s] = 1;
			redsAndGamma.set(s);
		}
		if(trace) System.out.println("Reds and Gamma size: "+redsAndGamma.size());

		int counter = 0;

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

			for (ExploredState pred : predecessors.get(x)) {
				int z = pred.number;
				int dZ = d[x] + pred.getOrderTo(x);
				if (dZ < d[z]) {
					v[z] = 0;
					d[z] = dZ;
				}
				if(d[z] == dZ && !prop.isRed(model, pred))
					v[z] += v[x] * pred.getProbTo(x);
			}
		}

		ArrayDeque<Integer> currentSuitables = new ArrayDeque<Integer>();
		BitSet suitables = new BitSet();
		int dCur = Integer.MAX_VALUE;

		iter = potentials.stream().iterator();
		while (iter.hasNext()) {
			int z = iter.nextInt();
			boolean suitable = true;
			StateSpace.State st = model.getState(z);
			if (!(st instanceof ExploredState))
				throw new AssertionError("Unexplored state encountered in backward search.");
			ExploredState es = (ExploredState)st;
			for (int x : es.neighbours) {
				if (!Lambda.contains(x))
					continue;
				if (redsAndGamma.get(x)) {
					suitable = false;
					break;
				}
				StateSpace.State nb = model.getState(x);
				int dXZ = es.getOrderTo(z);
				if (!(prop.isBlue(model, nb) || dXZ > 0)) {
					suitable = false;
					break;
				}
			}
			if (suitable) {
				suitables.set(z);
				if (d[z] < dCur) {
					dCur = d[z];
					currentSuitables.clear();
				}
				if (d[z] == dCur)
					currentSuitables.add(z);
			}
		}

		// then: Lambda

		while(true) {
			if (currentSuitables.isEmpty()) {
				dCur = Integer.MAX_VALUE;
				int s = suitables.nextSetBit(0);
				if (s == -1)
					break;
				while (s != -1) {
					if (d[s] < dCur) {
						dCur = d[s];
						currentSuitables.clear();
					}
					if (d[s] == dCur)
						currentSuitables.add(s);
					s = suitables.nextSetBit(s + 1);
				}
			}
			int x = currentSuitables.poll();

			if (trace) {
				System.out.println("** state "+x+": d="+d[x]);
				counter++;
				//System.out.println("count: "+counter+": "+x);
			}
			LambdaP.set(x);
			suitables.clear(x);

			for (ExploredState z : predecessors.get(x)) {
				int zn = z.number;
				//System.out.println(x+", "+z+": "+X.size());
				int dZ = d[x] + z.getOrderTo(x);
				if (dZ < d[zn]) {
					v[zn] = 0;
					d[zn] = dZ;
					if (dZ < dCur) {
						currentSuitables.clear();
						dCur = dZ;
					}
					if (dZ == dCur)
						currentSuitables.add(zn);
				}
				if (d[zn] == dZ && !prop.isRed(model, z))
					v[zn] += v[x] * z.getProbTo(x);

				if (suitables.get(zn)
				    || LambdaP.get(zn)
				    || !potentials.get(zn))
					continue;
				boolean suitable = true;
				for (int xx : z.neighbours) {
					if (!Lambda.contains(xx))
						continue;
					StateSpace.State st = model.getState(xx);
					if (!(st instanceof ExploredState))
						throw new AssertionError("Unexplored state encountered in backward search.");
					ExploredState es = (ExploredState)st;
					int rxxz = es.getOrderTo(zn);
					if (!(prop.isBlue(model, st) || LambdaP.get(xx) || redsAndGamma.get(xx) || rxxz > 0)) {
						suitable = false;
						break;
					}
				}
				if (suitable) {
					suitables.set(zn);
					int md = d[zn];
					if (md < dCur) {
						dCur = md;
						currentSuitables.clear();
					}
					if (md == dCur)
						currentSuitables.add(zn);
				}
			}
		}

		// finallY: reset blue states
		for(int z : Lambda) {
			if(prop.isBlue(model, model.getState(z)))
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
