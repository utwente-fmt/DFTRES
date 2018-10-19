package algorithms;

import java.text.DecimalFormat;

public class SimulationResult {
	public String property;
	public long simTimeNanos;
	public long storedStates;
	
	public double sumX;
	public double sumX2;
	public double sumXnotdom;
	public double sumX2notdom;
	
	public long N;
	public long M;
	
	public double mean;
	public double var;
	public double relErr;
	public double relHalfWidth;
	public double logRatio;
	
	public double pdom;
	public double qdom;

	private final static double Zvalue = 1.959963984540;
	
	public SimulationResult(double mean, double var, long N) {
		this.mean = mean;
		this.var = var;
		if(var < 0) var = 0; // to avoid floating point errors 
		relHalfWidth = Zvalue*Math.sqrt(var/N)/mean;
		relErr = Math.sqrt(var)/mean;
		this.N = N;
	}
	
	public SimulationResult(double[] Y, double[] probs, long N, long M) {
		sumX = Y[0];
		sumX2 = Y[1];
		mean = Y[0]/N;
		var = (Y[1]/N - mean*mean) / (N - 1);
		//var = (N * Y[1] - Y[0]*Y[0])/N/(N-1);
		if(var < 0) var = 0; // to avoid floating point errors 
		if (Y.length>2) {
			sumXnotdom = Y[2];
			sumX2notdom = Y[3];
		}
		relHalfWidth = Zvalue*Math.sqrt(var/N)/mean;
		relErr = Math.sqrt(var)/mean;
		logRatio = Math.log(Y[1]/N)/Math.log(mean);
		this.N = N;
		this.M = M;
		this.pdom = probs[0];
		this.qdom = probs[1];
	}
	
	// separates a number into a constant (namely $z$) times 10 to the power $n$
	static private double[] sepBaseFromExp(double x, int acc, int shift) {
		int n = (int) Math.floor(Math.log(x)/Math.log(10));
		if(n == Integer.MIN_VALUE) return new double[] {0, 0};
		double z = Math.round(Math.pow(10,-n)*x*Math.pow(10,acc))/Math.pow(10,acc+shift);
		if(Double.parseDouble(round(z, acc)) == 10) {z /= 10; n++;}
		return new double[] {z, n};
	}
	
	// string representation of a separated number
	static private String floatRep(double x, int acc, int shift, boolean exp) {
		double[] number = sepBaseFromExp(x, acc, shift);
		if(number[0] == 0.0) return "0";
		String result = "$"+round(number[0], acc)+"$";
		if((int) number[1] != 0 && exp) result += "\\ex{"+((int) number[1])+"}";
		return result;
	}
	
	// floatRep with a default shift of 0
	static public String floatRep(double x, int acc, boolean exp) {
		return floatRep(x, acc, 0, exp);
	}
	
	// floatRep with the addition of 10^n in the string set to TRUE
	static public String floatRep(double x, int acc) {
		return floatRep(x, acc, true);
	}
	
	// confidence interval, acc1 decimals in the estimate, acc2 decimals in the bounds
	static private String CI(double x, double y, int acc1, int acc2) {
		if(y == 0) return floatRep(x,acc1,true)+"$\\pm$ 0.00";
		return floatRep(x,acc1,true)+"$\\pm$ "+floatRep(y,acc2,true);
	}
	
	// confidence interval with default accuracy {3, 2}.
	static public String CI(double x, double y) {
		return CI(x,y,3,2);
	}
	
	// confidence interval where the amount of decimals in the estimate depends on the bounds
	private String relativelyShiftedSplitCI(double x, double y, int acc) {
		double[] decX = sepBaseFromExp(x, acc, 0);
		double[] decY = sepBaseFromExp(y, acc, 0);
		int n1 = (int) decX[1];//(int) Math.floor(Math.log(x)/Math.log(10));
		int n2 = (int) decY[1];//Math.floor(Math.log(y)/Math.log(10));
		//System.out.println(y+", "+n2);
		if(Double.isNaN(x) && qdom != 0.0) {
			return " "+floatRep(pdom,2*acc+2,true)+" & $\\pm$ 0 ";
		}
		if(y == 0 || y == 0.0) {
			if(x == 0 || x == 0.0) return " --- & --- ";
			return ""+floatRep(x,2*acc+2,false)+"\\ex{"+n1+"} & $\\pm$ 0";
		}
		return ""+floatRep(x,Math.max(acc, n1-n2+acc),false)+"\\ex{"+n1+"} & $\\pm$ "+floatRep(y,acc,0,false)+"\\ex{"+n2+"}";
	}
	
	static private String relativelyShiftedCombinedCI(double x, double y, int acc1, int acc2) {
		double[] decX = sepBaseFromExp(x, acc1, 0);
		double[] decY = sepBaseFromExp(y, acc2, 0);
		int n1 = (int) decX[1];//(int) Math.floor(Math.log(x)/Math.log(10));
		int n2 = (int) decY[1];//Math.floor(Math.log(y)/Math.log(10));
		//System.out.println(y+", "+n2);
		if(y == 0 || y == 0.0) {
			if(x == 0 || x == 0.0) return " --- ";
			return ""+floatRep(x,acc1,false)+"\\ex{"+n1+"} $\\pm$ 0";
		}
		return "("+floatRep(x,acc1,false)+" $\\pm$ "+floatRep(y,acc2,n1-n2,false)+")\\ex{"+n1+"}";
	}
	
	static private String relativelyShiftedRelativeCI(double x, double y, int acc1, int acc2) {
		double[] decX = sepBaseFromExp(x, acc1, 0);
//		double[] decY = sepBaseFromExp(y, acc2, 0);
		int n1 = (int) decX[1];//(int) Math.floor(Math.log(x)/Math.log(10));
//		int n2 = (int) decY[1];//Math.floor(Math.log(y)/Math.log(10));
		//System.out.println(y+", "+n2);
		if(y == 0 || y == 0.0) {
			if(x == 0 || x == 0.0) return " \\multicolumn{3}{c}{---} ";
			return ""+floatRep(x,acc1,false)+"\\ex{"+n1+"} & $\\pm$ & ---";
		}
		String ci = round(y/x*100,acc2,false);
		if(y/x > 1) ci = round(y/x*100,acc2-2,false);
		else if(y/x > 0.1) ci = round(y/x*100,acc2-1,false);
		if(!Double.isNaN(y/x)) ci+= "\\%";
		return floatRep(x,acc1,false)+"\\ex{"+n1+"} & $\\pm$ & "+ci;
	}

	// rounds a number to a fixed amount of decimal places
	static public String round(double x, int acc) {
		return round(x, acc, false);
	}
	
	static public String round(double x, int acc, boolean mathFormat) {
		if(Double.isNaN(x)) return " --- ";
		if(acc == 0) return ""+ (int) Math.round(x);
		String dfs = "0."; for(int i=0;i<acc;i++) {dfs += "0";}
		DecimalFormat df = new DecimalFormat(dfs);
		if(mathFormat) return "$"+df.format(x)+"$";
		return df.format(x);
	}

	//add spaces every three digits for a big integer
	static public String numb(long N) {
		String temp = ""+N;
		int n = (int) (temp.length()-1)/3+1;
		int k = temp.length()-3*(n-1);
		//System.out.println(n+", "+k);
		String result = temp.substring(0,k);
		//System.out.println(result);
		for(int i=0;i<n-1;i++) {
			result+="\\,"+temp.substring(k+3*(i),k+3*(i+1));
		}
		return "$"+result+"$";
	}
	
//	private String CIString(double[] Y, long N) {
//		double meanY = Y[0]/N;
//		double varY = Y[1]/N-meanY*meanY;
//		double err = Math.sqrt(varY)/meanY;
//		if(meanY == 0) return "--- & --- & ---";
//		return ShiftedCI(meanY,(1.96*Math.sqrt(varY/N)))+" & "+floatRep(err,3,false)+" & "+numb((int) N);
//	}
	
	
	// prints the relative half-width for the standard, plus and plusplus methods
	
	public String getRelHw(int acc) {
		if(mean == 0 || var == 0) return " --- ";
		//return Simulator.ShiftedCI(mean,(1.96*Math.sqrt(var/N)));
		//return ""+relErr;
		return floatRep(Zvalue*Math.sqrt(var/N)/mean,acc,true);
	}
	
	public String getRelHwPlus( int acc) {
		double newMean = sumXnotdom/N + pdom;
		double newVar = (sumX2notdom/N-sumXnotdom/N*sumXnotdom/N);
		if(newMean == 0 || newVar <= 0) return " --- ";
		//return Simulator.ShiftedCI(mean,(1.96*Math.sqrt(var/N)));
		//return ""+relErr;
		return floatRep(Zvalue*Math.sqrt(newVar/N)/newMean,acc,true);
	}
	
	public String getRelHwPlusPlus( int acc) {
		double newMean = sumXnotdom/M*(1-qdom)+pdom;
		double newVar = (sumX2notdom/M-sumXnotdom/M*sumXnotdom/M)*(1-qdom)*(1-qdom);
		if(newMean == 0 || newVar <= 0) return " --- ";
		//if(mean == 0 ) return " --- ";
		//return Simulator.ShiftedCI(mean,(1.96*Math.sqrt(var/N)));
		//return ""+relErr;
		return floatRep(Zvalue*Math.sqrt(newVar/M)/newMean,acc,true);
	}
	
	// similar but for the relative errors
	
	public String getRelErr(int acc) {
		if(mean == 0 ) return " --- ";
		//return Simulator.ShiftedCI(mean,(1.96*Math.sqrt(var/N)));
		//return ""+relErr;
		return round(relErr,acc, true);
	}
	
	public String getRelErrPlus(int acc) {
		double newMean = sumXnotdom/N + pdom;
		double newVar = (sumX2notdom/N-sumXnotdom/N*sumXnotdom/N);
		//return Simulator.ShiftedCI(mean,(1.96*Math.sqrt(var/N)));
		//return ""+relErr;
		return round(Math.sqrt(newVar)/newMean,acc, true);
	}
	
	public String getRelErrPlusPlus(int acc) {
		double newMean = sumXnotdom/M*(1-qdom)+pdom;
		double newVar = (sumX2notdom/M-sumXnotdom/M*sumXnotdom/M)*(1-qdom)*(1-qdom);
		//if(mean == 0 ) return " --- ";
		//return Simulator.ShiftedCI(mean,(1.96*Math.sqrt(var/N)));
		//return ""+relErr;
		return round(Math.sqrt(newVar)/newMean,acc, true);
	}
	
	private String getCIString(double mean, double estVar, int typeCI, int cols) {
		String result = "";
		if(typeCI == 0) result += CI(mean,(Zvalue*Math.sqrt(estVar))); 									// e.g., $1.012$\ex{-1}$\pm$ $1.32$\ex{-3}
		else if (typeCI == 1) result += relativelyShiftedSplitCI(mean,(Zvalue*Math.sqrt(estVar)),3); 			// e.g., $1.0121234$\ex{-1}$\pm$ $1.32$\ex{-6}
		else if (typeCI == 2) result += relativelyShiftedCombinedCI(mean,(Zvalue*Math.sqrt(estVar)),3,2);
		else if (typeCI == 3) result += relativelyShiftedRelativeCI(mean,(Zvalue*Math.sqrt(estVar)),3,cols);
		//else if (typeCI == 2) result += relativelyShiftedCI(mean,(1.96*Math.sqrt(estVar)),3); 			// e.g., $1.012$\ex{-1}$\pm$ $0.0132$\ex{-1}
		if (typeCI != 3) {
			if(cols == 0) result += "& "+round(relErr,3, true)+" & "+numb((int) N);
			if(cols == 1) result += "& "+floatRep(Zvalue*Math.sqrt(estVar)/mean,3, true)+" & "+numb((int) N)+" & "+numb((int) M);
//		/if(cols == 1) result += "& "+numb((int) N)+" & "+numb((int) M)+" & "+numb((int) (N * qdom)); 
		}
		System.out.println(mean+", "+estVar+", "+result);
		return result;
	}
	
	public String getCIString(int typeCI, int cols) {
		return getCIString(mean, var/N, typeCI, cols);
	}
	
	public String getCIStringPlus(int typeCI, int cols) {
		System.out.println("pdom: "+pdom+", qdom: "+qdom);
		double newMean = sumXnotdom/N + pdom;
		double newVar = (sumX2notdom/N-sumXnotdom/N*sumXnotdom/N);
		return getCIString(newMean, newVar/N, typeCI, cols);
	}
	
	public String getCIStringPlusPlus(int typeCI, int cols) {
		double newMean = sumXnotdom/M*(1-qdom)+pdom;
		if(M == 0) return floatRep(pdom, 4)+" & $\\pm$ & --- ";
		double newVar = (sumX2notdom/M-sumXnotdom/M*sumXnotdom/M)*(1-qdom)*(1-qdom);
		System.out.println("aaa: "+newMean+", "+newVar);
		return getCIString(newMean, newVar/M, typeCI, cols);
	}
	
//	public String getCIStringForTable() {
//		if(mean == 0) return "---";
//		return shiftedCI(mean, 1.96*Math.sqrt(var/N),3,2);
//		//return ShiftedCI(mean,(1.96*Math.sqrt(var/N)))+" & "+round(relErr,3)+" & "+numb((int) N);
//	}
//	
//	public String getHalfWidth() {
//		if(mean == 0) return "---";
//		return floatRep(relHalfWidth,2);
//		//return ShiftedCI(mean,(1.96*Math.sqrt(var/N)))+" & "+round(relErr,3)+" & "+numb((int) N);
//	}
	
	/*-----------------------*/
	/*-------- paper --------*/
	/*-----------------------*/
	
	public String getCI() {
		//System.out.println(sumX+", "+sumX2+", "+sumXnotdom+", "+sumX2notdom+" - "+mean+"," +var);
		if(mean == 0) return "--- & --- ";
		return relativelyShiftedSplitCI(mean,Zvalue*Math.sqrt(var/N),3);
		//return ""+floatRep(mean,5)+" & \\pm "+floatRep(1.96*Math.sqrt(var/N),5);
	}
	
	public String getCIPlus(double pdom) {
		if(mean == 0) return "--- & ---";
		double newMean = sumXnotdom/N + pdom;
		double newVar = (sumX2notdom/N-sumXnotdom/N*sumXnotdom/N);
		System.out.println(sumX+", "+sumX2+", "+sumXnotdom+", "+sumX2notdom+" - "+newMean+"," +newVar);
		return relativelyShiftedSplitCI(newMean,Zvalue*Math.sqrt(newVar/N),3);
		//return ""+floatRep(newMean,5)+" & \\pm "+floatRep(1.96*Math.sqrt(newVar/N),5);
		//return ShiftedCI(newMean,(1.96*Math.sqrt(newVar/N)));
	}
	
	public String getCIPlusPlus(double pdom, double qdom) {
		System.out.println("---");
		//if(mean == 0) return "--- ";
		double newMean = sumXnotdom/M*(1-qdom)+pdom;
		double newVar = (sumX2notdom/M-sumXnotdom/M*sumXnotdom/M)*(1-qdom)*(1-qdom);
		if(M == 0) return relativelyShiftedSplitCI(pdom,0,3); 
		return relativelyShiftedSplitCI(newMean,Zvalue*Math.sqrt(newVar/M),3);
		//return ""+floatRep(newMean,5)+" & \\pm "+floatRep(1.96*Math.sqrt(newVar/M),5);
		//return ShiftedCI(newMean,(1.96*Math.sqrt(newVar/M)));
	}
	
	/*----------------------*/
	
	public String getExplicitCI() {
		return Math.fma(Zvalue, -Math.sqrt(var/N), mean)
			+", "+
			Math.fma(Zvalue, Math.sqrt(var/N), mean);
	}
	
	public String getExplicitCIPlus(double pdom) {
		double newMean = sumXnotdom/N + pdom;
		double newVar = (sumX2notdom/N-sumXnotdom/N*sumXnotdom/N);
		return (newMean-Zvalue*Math.sqrt(newVar/N))+", "+(newMean+Zvalue*Math.sqrt(newVar/N));
	}
	
	public String getExplicitCIPlusPlus(double pdom, double qdom) {
		double newMean = sumXnotdom/M*(1-qdom)+pdom;
		double newVar = (sumX2notdom/M-sumXnotdom/M*sumXnotdom/M)*(1-qdom)*(1-qdom);
		System.out.println(var+" --- "+newVar);
		return (newMean-Zvalue*Math.sqrt(newVar/M))+", "+(newMean+Zvalue*Math.sqrt(newVar/M));
	}
	
	public String toString() {
		return ("mean: "+mean+",\nvar: "+var+",\nCI: ["+getExplicitCI()+"]  = "+mean+" +/- "+relHalfWidth*mean+"\nw: "+relHalfWidth+",\nrel err: "+relErr+",\nlogratio: "+logRatio);
	}
	
}
