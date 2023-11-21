package models;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import nl.utwente.ewi.fmt.EXPRES.Composition;
import nl.utwente.ewi.fmt.EXPRES.LTS;
import nl.utwente.ewi.fmt.EXPRES.NondeterminismException;
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

	public Neighbours findNeighbours(State s)
	{
		int[] state = s.state;

		Composition.statesExplored = 0;
		//System.err.format("Neighbours from state %d (%s)\n", s, java.util.Arrays.toString(state));
		Set<LTS.Transition> transitions;
		try {
			transitions = comp.getTransitions(state);
		} catch (NondeterminismException e) {
			throw new UnsupportedOperationException(e);
		}
		State[] neighbours = new State[transitions.size()];
		short[] orders = new short[transitions.size()];
		double[] probs = new double[transitions.size()];
		boolean probabilistic = false;

		int i = 0;
		for (LTS.Transition t : transitions) {
			String rlabel = t.label.substring(1);
			double rateOrProb = Double.parseDouble(rlabel);
			if (t.label.charAt(0) == 'p')
				probabilistic = true;
			else if (t.label.charAt(0) != 'r')
				throw new IllegalArgumentException("Non-Markovian transition encountered: " + t.label);
			int order = (int)Math.ceil(Math.log(rateOrProb) / logEpsilon);
			if (order < 0)
				order = 0;
			if (order > Short.MAX_VALUE)
				throw new IllegalArgumentException("Order does not fit in 16 bits.");
			State z = findOrCreate(t.target.clone());
			if (z.equals(s))
				continue;
			neighbours[i] = z;
			orders[i] = (short)order;
			probs[i] = rateOrProb;
			i++;
		}

		if (i != probs.length) {
			neighbours = Arrays.copyOf(neighbours, i);
			probs = Arrays.copyOf(probs, i);
			orders = Arrays.copyOf(orders, i);
		}

		double totProb = 0;
		int n = i;

		for(i = 0; i < n; i++)
			totProb += probs[i];
		for(i = 0; i < n; i++)
			probs[i] /= totProb;

		if (probabilistic)
			totProb = Double.POSITIVE_INFINITY;
		return explored(s, neighbours, orders, probs, totProb);
	}

	public Number getVarValue(String variable, State state)
	{
		return comp.getVarValue(variable, state.state);
	}
}
