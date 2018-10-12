package algorithms;

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

public class StateSpace {

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
	public HashMap<StateWrapper, Integer> knownStates;

	public List<int[]> states;
	public int d[];
	public int dp[];
	public double v[];
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
	
	public void reserve(int[] x) {
		knownStates.put(new StateWrapper(x), states.size());
		states.add(x);
		int p = states.size();
		if (d.length < p) {
			d = Arrays.copyOf(d, p * 2);
			dp = Arrays.copyOf(dp, p * 2);
			exitRates = Arrays.copyOf(exitRates, p * 2);
			v = Arrays.copyOf(v, p * 2);
		}
		d[p - 1] = Integer.MAX_VALUE;
		dp[p - 1] = Integer.MAX_VALUE;
		
		successors.add(null);
		orders.add(null);
		probs.add(null);
	}
	
	public void init(int[] x0) {
		knownStates = new HashMap<StateWrapper, Integer>();
		states = new ArrayList<int[]>();
		d = new int[1024];
		dp = new int[1024];
		v = new double[1024];
		
		successors = new ArrayList<int[]>();
		orders = new ArrayList<int[]>();
		probs = new ArrayList<double[]>();
		exitRates = new double[1024];
		inHPC = new BitSet();
		
		hpcs = new HashMap<Integer, HPCState>();
		
		reserve(x0);
		dp[0] =  0;
	}
	
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
	
	public StateSpace clone() {
		StateSpace ret = new StateSpace();
		ret.d = d.clone();
		ret.dp = dp.clone();
		ret.v = v.clone();
		ret.exitRates = exitRates.clone();
		ret.inHPC = (BitSet)inHPC.clone();
		ret.knownStates = new HashMap<StateWrapper, Integer>();
		int size = states.size();
		ret.states = new ArrayList<int[]>(size);

		ret.successors = new ArrayList<int[]>(successors.size());
		ret.orders = new ArrayList<int[]>(orders.size());
		ret.probs = new ArrayList<double[]>(probs.size());

		for(int[] i : states) {
			int[] k = i.clone();
			ret.knownStates.put(new StateWrapper(k),
			                    ret.states.size());
			ret.states.add(k);
		}

		for(int[] i : successors) {
			if(i == null)
				ret.successors.add(null);
			else
				ret.successors.add(i.clone());
		}
		for(int[] i : orders) {
			if(i == null)
				ret.orders.add(null);
			else
				ret.orders.add(i.clone());
		}
		for(double[] i : probs) {
			if(i == null)
				ret.probs.add(null);
			else
				ret.probs.add(i.clone());
		}

		ret.hpcs = new HashMap<Integer, HPCState>();
		for (Map.Entry<Integer, HPCState> e : hpcs.entrySet())
			ret.hpcs.put(e.getKey(), e.getValue().clone());

		return ret;
	}
	
	public void printStateSpace() {
		System.out.println("--X--");
		System.out.println(toString());
		System.out.println("-----");
	}
	
	public void printFullStateSpaceToFile(String filepath) {
		try {
			PrintStream out = new PrintStream(new FileOutputStream(new File(filepath)));
	    	
			out.println("--X--");
			for(int i=0;i<size();i++) {
				String dd = Double.toString(d[i]);
				if(d[i] == Integer.MAX_VALUE)
					dd = "Infinity";
				out.println(i+": "+Arrays.toString(states.get(i)) +", dp="+dp[i] +", d="+dd+", w="+v[i]+", ex.r.="+exitRates[i]);
				if(successors.get(i) != null) {
					for(int j=0;j<successors.get(i).length;j++) {
						if(successors.get(i)[j] > -1) {
							out.println("-> "+Arrays.toString(states.get(successors.get(i)[j]))+" (="+successors.get(i)[j]+"), o="+orders.get(i)[j]+", p="+probs.get(i)[j]);
						}
					}
				}
			}
			out.println("-----");
	    	
	    	out.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	public void printFullStateSpaceToFile(String filepath, ModelGenerator m) {
		try {
			PrintStream out = new PrintStream(new FileOutputStream(new File(filepath)));
	    	
			out.println("--X--");
			for(int i=0;i<size();i++) {
				String dd = ""+d[i]; if(d[i] == Integer.MAX_VALUE) {dd = "Infinity";}
				out.print(i+": "+Arrays.toString(states.get(i)) +", dp="+dp[i] +", d="+dd+", w="+v[i]+", ex.r.="+exitRates[i]);
				if(m.isRed(i)) out.print(", red");
				if(m.isBlue(i)) out.print(", blue");
				out.println();
				if(successors.get(i) != null) {
					for(int j=0;j<successors.get(i).length;j++) {
						if(successors.get(i)[j] > -1) {
							out.println("-> "+Arrays.toString(states.get(successors.get(i)[j]))+" (="+successors.get(i)[j]+"), o="+orders.get(i)[j]+", p="+probs.get(i)[j]);
						}
					}
				}
			}
			out.println("-----");
	    	
	    	out.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	public void printFullStateSpace() {
		System.out.println("--X--");
		for(int i=0;i<size();i++) {
			String dd = ""+d[i]; if(d[i] == Integer.MAX_VALUE) {dd = "Infinity";}
			System.out.println(i+": "+Arrays.toString(states.get(i)) +", dp="+dp[i] +", d="+dd+", w="+v[i]+", ex.r.="+exitRates[i]);
			if(successors.get(i) != null) {
				for(int j=0;j<successors.get(i).length;j++) {
					if(successors.get(i)[j] > -1) {
						System.out.println("-> "+Arrays.toString(states.get(successors.get(i)[j]))+" (="+successors.get(i)[j]+"), o="+orders.get(i)[j]+", p="+probs.get(i)[j]);
					}
				}
			}
		}
		System.out.println("-----");
	}
	
	public String toString() {
		String result = "";
		for(int i=0;i<size();i++) {
			String dd = ""+d[i]; if(d[i] == Integer.MAX_VALUE) {dd = "Infinity";}
			result += (i+": "+Arrays.toString(states.get(i)) +", dp="+dp[i] +", d="+dd+", w="+v[i]+"\n");
		}
		return result;
	}

}
