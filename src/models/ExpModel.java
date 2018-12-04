package models;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import nl.utwente.ewi.fmt.EXPRES.Composition;
import nl.utwente.ewi.fmt.EXPRES.LTS;
import nl.utwente.ewi.fmt.EXPRES.Property;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public class ExpModel extends StateSpace
{
	private final LTS comp;
	private final int initialState[];
	private final static boolean VERBOSE = false;
	private final double logEpsilon;
	private final Property prop;

	public ExpModel(ExpModel other, Property newProp)
	{
		this(other.epsilon, other.comp, newProp);
	}

	private ExpModel(ExpModel other)
	{
		super(other);
		this.logEpsilon = other.logEpsilon;
		this.initialState = other.initialState;
		this.comp = other.comp;
		this.prop = other.prop;
	}

	public ExpModel snapshot()
	{
		return new ExpModel(this);
	}

	public ExpModel (double epsilon, LTS model) throws IOException
	{
		this(epsilon, model, null);
	}

	public ExpModel (double epsilon, LTS model, Property prop)
	{
		super(epsilon, model.getInitialState());
		logEpsilon = Math.log(epsilon);

		comp = model;
		initialState = comp.getInitialState();
		if (VERBOSE)
			System.err.format("Initial state: %s\n", java.util.Arrays.toString(initialState));
		this.prop = prop;
	}

	public int getDimension()
	{
		return initialState.length;
	}

	public ExploredState findNeighbours(State s)
	{
		if (s instanceof ExploredState)
			return (ExploredState)s;
		int[] state = s.state;

		Composition.statesExplored = 0;
		//System.err.format("Neighbours from state %d (%s)\n", s, java.util.Arrays.toString(state));
		Set<LTS.Transition> transitions = comp.getTransitions(state);
		int[] neighbours = new int[transitions.size()];
		short[] orders = new short[transitions.size()];
		double[] probs = new double[transitions.size()];

		int i = 0;
		for (LTS.Transition t : comp.getTransitions(state)) {
			String rlabel = t.label.substring(5);
			double rate = Double.parseDouble(rlabel);
			int order = (int)Math.ceil(Math.log(rate) / logEpsilon);
			if (order < 0)
				order = 0;
			if (order > Short.MAX_VALUE)
				throw new IllegalArgumentException("Order does not fit in 16 bits.");
			State z = findOrCreate(t.target.clone());
			neighbours[i] = z.number;
			orders[i] = (short)order;
			probs[i] = rate;
			i++;
		}

		double totProb = 0;
		int n = probs.length;

		for(i = 0; i < n; i++)
			totProb += probs[i];
		for(i = 0; i < n; i++)
			probs[i] /= totProb;

		ExploredState ret = explored(s, neighbours, orders, probs,
		                             totProb);
		return ret;
	}

	public Number getVarValue(String variable, State state)
	{
		return comp.getVarValue(variable, state.state);
	}
}
