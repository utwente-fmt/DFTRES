package models;
import java.io.IOException;
import algorithms.ModelGenerator;
import nl.utwente.ewi.fmt.EXPRES.Composition;
import nl.utwente.ewi.fmt.EXPRES.LTS;
import nl.utwente.ewi.fmt.EXPRES.MarkovReducedLTS;
import nl.utwente.ewi.fmt.EXPRES.Property;

public class ExpModel extends ModelGenerator
{
	private final LTS comp;
	private final int initialState[];
	private final static boolean VERBOSE = false;
	private final double logEpsilon;

	private Property prop;

	public ExpModel(ExpModel other, Property newProp)
	{
		super(other);
		comp = other.comp;
		initialState = other.initialState;
		prop = newProp;
		logEpsilon = other.logEpsilon;
	}

	public Object clone()
	{
		return new ExpModel(this, prop);
	}

	public ExpModel (double epsilon, LTS model)
			throws IOException
	{
		super();
		this.epsilon = epsilon;
		logEpsilon = Math.log(epsilon);

		comp = new MarkovReducedLTS(model);
		initialState = comp.getInitialState();
		if (VERBOSE)
			System.err.format("Initial state: %s\n", java.util.Arrays.toString(initialState));
		prop = null;
	}

	public ExpModel (double epsilon, LTS model, Property prop)
			throws IOException
	{
		this(epsilon, model);
		this.prop = prop;
	}

	public int getDimension()
	{
		return initialState.length;
	}

	public void initialise() {
		super.initialise();
		X.init(initialState);
	}

	public boolean isRed(int s)
       	{
		int[] state = X.states.get(s);
		if (prop == null || prop.variable == null)
			return state[initialState.length - 1] == 1;
		else
			return comp.getVarValue(prop.variable, state) != 0;
	}
	
	public boolean isBlue(int s)
	{
		if (prop.type == Property.Type.REACHABILITY)
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
