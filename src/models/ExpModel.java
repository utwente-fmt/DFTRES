package models;
import java.io.IOException;
import algorithms.ModelGenerator;
import nl.utwente.ewi.fmt.EXPRES.Composition;
import nl.utwente.ewi.fmt.EXPRES.LTS;
import nl.utwente.ewi.fmt.EXPRES.MarkovReducedLTS;

public class ExpModel extends ModelGenerator
{
	private final LTS comp;
	private final int initialState[];
	private final boolean noBlueStates;
	private final static boolean VERBOSE = false;
	private final double logEpsilon;

	public ExpModel(ExpModel other)
	{
		super(other);
		comp = other.comp;
		initialState = other.initialState;
		noBlueStates = other.noBlueStates;
		logEpsilon = other.logEpsilon;
	}

	public Object clone()
	{
		return new ExpModel(this);
	}

	public ExpModel (double epsilon, String filename, boolean noBlueStates)
			throws IOException
	{
		this.epsilon = epsilon;
		logEpsilon = Math.log(epsilon);
		this.noBlueStates = noBlueStates;

		int[] state;
		Composition c;
		if (filename.endsWith(".exp")) {
			c = new Composition(filename, "exp");
			c.markStatesAfter("FAIL", 1);
			c.markStatesAfter("REPAIR", 0);
			c.markStatesAfter("ONLINE", 0);
		} else {
			c = new Composition(filename, "jani");
		}
		LTS model = new MarkovReducedLTS(c);
		initialState = model.getInitialState();
		if (VERBOSE)
			System.err.format("Initial state: %s\n", java.util.Arrays.toString(initialState));
		comp = model;
	}


	public int getDimension()
	{
		return initialState.length;
	}

	public boolean isRed(int s)
       	{
		return X.states.get(s)[initialState.length - 1] == 1;
	}
	
	public boolean isBlue(int s)
	{
		if (noBlueStates)
			return false;
		int[] state = X.states.get(s);
		for (int i = 0; i < initialState.length; i++)
			if (state[i] != initialState[i])
				return false;
		return true;
	}

	public void findNeighbours(int s)
	{
		int[] state = X.states.get(s);		
		initNeighbours();
		int[] temp = new int[initialState.length];

		Composition.statesExplored = 0;
		if (state[0] == -1)
			state = initialState.clone();
		//System.err.format("Neighbours from state %d (%s)\n", s, java.util.Arrays.toString(state));
		for (LTS.Transition t : comp.getTransitions(state)) {
			String rlabel = t.label.substring(5);
			double rate = Double.parseDouble(rlabel);
			int order = (int)Math.ceil(Math.log(rate) / logEpsilon);
			if (order < 0)
				order = 0;
			newSuccessor(t.target, order, rate);
		}

		processNeighbours(s);
	}
}
