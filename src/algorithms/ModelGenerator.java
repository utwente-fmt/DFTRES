package algorithms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ModelGenerator{

	public String name;
	
	public StateSpace X; // generated part of the state space
	public StateSpace XUnderQ;
	
	public double epsilon;
	
	public int minOrder;
	public double totalRate;
	
	public List<Integer> neighbours;
	public List<Integer> orders;
	public List<Double> probs;
	
	public ModelGenerator() {}

	public ModelGenerator(ModelGenerator other)
	{
		name = other.name;
		epsilon = other.epsilon;
		minOrder = other.minOrder;
		totalRate = other.totalRate;
		if (other.neighbours != null)
			neighbours = new ArrayList<Integer>(other.neighbours);
		if (other.orders != null)
			orders = new ArrayList<Integer>(other.orders);
		if (other.probs != null)
			probs = new ArrayList<Double>(other.probs);
		X = (StateSpace) other.X.clone();
		if (XUnderQ != null)
			XUnderQ = (StateSpace) other.XUnderQ.clone();
	}

	public Object clone()
	{
		return new ModelGenerator(this);
	}

	public void resetEpsilon(double epsilon) {this.epsilon = epsilon;}

	/* Horribly unsafe for reentrant functions */
	private StateSpace.StateWrapper cachedWrapper = new StateSpace.StateWrapper(new int[0]);

	private int find(int[] x) {
		cachedWrapper.state = x;
		Integer z = X.knownStates.get(cachedWrapper);
		if (z != null)
			return z;
		else
			return -1;
	}
	
	public int findOrCreate(int[] x) {
		int s = find(x);
		if(s == -1) {
			X.reserve(x);
			return X.size()-1;
		}
		return s;
	}
	
	public void initialise() {
		X = new StateSpace();
		int[] stateV = new int[getDimension()];
		for(int i=0;i<stateV.length;i++) {stateV[i] = -1;}
		X.init(stateV);
	}
	
	public void newSuccessor(int[] x, int epsilon_order, double probability) {
		int[] y = new int[x.length];
		for(int j=0;j<y.length;j++) {y[j] = x[j];}
		int z = findOrCreate(y);
		
		neighbours.add(z);
		orders.add(epsilon_order);
		probs.add(probability);
	}
	
	public void newSuccessor(int[] x, double probability) {
		newSuccessor(x, (int) Math.floor(Math.log(probability)/Math.log(epsilon)), probability);
	}
	
	public void initNeighbours() {
		neighbours = new ArrayList<Integer>();
		orders = new ArrayList<Integer>();
		probs = new ArrayList<Double>();
	}
	
	public void processNeighbours(int s) {
		int n = neighbours.size();
		int[] n_neighbours = new int[n];
		int[] n_orders = new int[n];
		double[] n_probs = new double[n];
		for(int i=0;i<n;i++) {
			n_neighbours[i] = neighbours.get(i);
			n_orders[i] = orders.get(i);
			n_probs[i] = probs.get(i);
		}
		
		double totProb = 0;
		int minOrder = Integer.MAX_VALUE;
		
		for(int i=0;i<n;i++) {totProb += n_probs[i]; minOrder = Math.min(minOrder, n_orders[i]);}
		for(int i=0;i<n;i++) {n_probs[i] /= totProb; n_orders[i] -= minOrder;}
		
		X.successors.set(s, n_neighbours);
		X.orders.set(s, n_orders);
		X.probs.set(s, n_probs);
		X.exitRates[s] =  totProb;
	}
	
	public int getDimension() {
		return 0;
	}
	
	public boolean isRed(int s) {
		return false;
	}
	
	public boolean isBlue(int s) {
		return false;
	}
	
	public void findNeighbours(int z) {
		
	}
	
	public String stateString(int state) {
		return "state "+ state +", ="+Arrays.toString(X.states.get(state));
	}
}
