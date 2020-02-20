package algorithms;

import java.text.DecimalFormat;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class SimulationResult {
	public final Property property;
	public final long simTimeNanos;
	public final int storedStates;

	public final long N;
	public final long M;

	public final double mean;
	public final double var;
	public final double lbound, ubound;
	public final double alpha;

	public static double CIwidth(double alpha)
	{
		/* Approximation of the inverse error function:
		 * By Sergei Winitzki
		 * https://1e47a410-a-62cb3a1a-s-sites.googlegroups.com/site/winitzki/sergei-winitzkis-files/erf-approx.pdf
		 */
		/* x = confidence - 1; */
		if (alpha > 0.5)
			alpha = 1-alpha;
		double a = 0.147;
		double f = 2 / (Math.PI * a);
		double y = 4*Math.fma(alpha, -alpha, alpha);
		/* y == 1 - x*x */
		double s = f + 0.5 * Math.log(y);
		double t = Math.fma(s, s, -Math.log(y)/a);
		double r = Math.sqrt(t);
		double e = Math.sqrt(2*(r-s));
		/* Widen to include maximal relative error */
		return e / 0.9995;
	}
	
	
	public SimulationResult(Property prop, double alpha, double mean,
	                        double var, long sims[], long time, int states)
	{
		this.mean = mean;
		if (Double.isNaN(var) || !Double.isFinite(var) || var < 0)
			var = 0; // to avoid floating point errors 
		this.var = var;
		this.property = prop;
		this.alpha = alpha;
		this.N = sims[0];
		this.M = sims[1];
		this.simTimeNanos = time;
		this.storedStates = states;
		double halfWidth = CIwidth(alpha / 2);
		double lbound = Math.fma(halfWidth, -Math.sqrt(var/N), mean);
		if (lbound < 0)
			lbound = 0;
		this.lbound = lbound;
		ubound = Math.fma(halfWidth, Math.sqrt(var/N), mean);
		assert(ubound <= 1.0);
	}

	public SimulationResult(Property prop, double mean, double alpha,
	                        double var, double lbound, double ubound,
	                        long sims[], long time, int states)
	{
		this.mean = mean;
		if (Double.isNaN(var) || !Double.isFinite(var) || var < 0)
			var = 0;  // to avoid floating point errors
		this.var = var;
		this.alpha = alpha;
		this.property = prop;
		this.N = sims[0];
		this.M = sims[1];
		this.simTimeNanos = time;
		this.storedStates = states;
		if (lbound < 0)
			lbound = 0;
		this.lbound = lbound;
		assert(ubound <= 1.0);
		this.ubound = ubound;
	}

	public String getCI() {
		return lbound + ", " + ubound;
	}

	public double getRelErr() {
		return (ubound - lbound) / (ubound + lbound);
	}

	public String toString() {
		double CIHalfWidth = (ubound - lbound) / 2;
		String ret = "point estimate: "+mean+",\n";
		ret       += "var: "+var+",\n";
		ret       += "CI: ["+getCI()+"]";
		if ((ubound + lbound) / 2 == mean)
			ret += " = " + mean+" +/- "+CIHalfWidth+"\n";
		else
			ret += "\n";
		return ret;
	}
}
