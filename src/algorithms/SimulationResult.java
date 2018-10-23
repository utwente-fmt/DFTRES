package algorithms;

import java.text.DecimalFormat;

public class SimulationResult {
	public String property;
	public final long simTimeNanos;
	public final int storedStates;
	
	public final long N;
	public final long M;
	
	public final double mean;
	public final double var;
	public final double lbound, ubound;
	
	public final static double Zvalue = 1.95996398454005423552;
	
	public SimulationResult(double mean, double var, long hits[],
			long time, int states)
	{
		this.mean = mean;
		if(var < 0)
			var = 0; // to avoid floating point errors 
		this.var = var;
		this.N = hits[0];
		this.M = hits[1];
		this.simTimeNanos = time;
		this.storedStates = states;
		double lbound = Math.fma(Zvalue, -Math.sqrt(var/N), mean);
		if (lbound < 0)
			lbound = 0;
		this.lbound = lbound;
		ubound = Math.fma(Zvalue, Math.sqrt(var/N), mean);
	}

	public SimulationResult(double mean, double lbound, double ubound,
	                        long hits[], long time, int states)
	{
		this.mean = mean;
		this.var = Double.NaN;
		this.N = hits[0];
		this.M = hits[1];
		this.simTimeNanos = time;
		this.storedStates = states;
		if (lbound < 0)
			lbound = 0;
		this.lbound = lbound;
		this.ubound = ubound;
	}

	public SimulationResult(double[] Y, long hits[], long time, int states)
	{
		this (Y[0] / hits[0],
		      (Y[1]/hits[0] - (Y[0]/hits[0])*(Y[0]/hits[0])) / (hits[0] - 1),
		      hits,
		      time,
		      states);
	}

	public String getCI() {
		return lbound + ", " + ubound;
	}

	public String toString() {
		double CIHalfWidth = Zvalue*Math.sqrt(var/N);
		return "mean: "+mean+",\n"
		     + "var: "+var+",\n"
		     + "CI: ["+getCI()+"] = "+mean+" +/- "+CIHalfWidth+"\n";
	}
}
