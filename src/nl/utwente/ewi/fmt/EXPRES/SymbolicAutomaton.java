package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import nl.utwente.ewi.fmt.EXPRES.expression.Expression;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;

public class SymbolicAutomaton implements LTS {
	private final int[] initialState;
	private final String[] variables; /* First entry is null to
	                                   * indicate the state number. */

	/* targets[i][j] denotes the target state of the j'th transition
	 * from state i. labels[i][j] denotes the label of that
	 * transition. */
	private int targets[][];
	private String labels[][];
	private Expression guards[][];
	private HashMap<String, Expression> assignments[][];

	/* Private since 'Map' may in the future be suitable for
	 * multiple types.
	 */
	private SymbolicAutomaton(Map janiData, Map<String, Number> constants)
	{
		HashMap<Integer, HashMap<String, Expression>> transients;
		transients = new HashMap<>();
		Object variables = janiData.get("variables");
		HashMap<String, Integer> initValues = new HashMap<>();
		if (variables != null) {
			if (!(variables instanceof Object[]))
				throw new IllegalArgumentException("Unexpected argument type of local variables list: Expected array, found " + variables.getClass());
			Object[] vars = (Object[]) variables;
			for (Object vo : vars) {
				if (vo == null)
					throw new IllegalArgumentException("Unexpected null in variable list.");
				if (!(vo instanceof Map)) {
					throw new IllegalArgumentException("Unexpected local variable entry: Expected object, found " + vo);
				}
				Map vm = (Map)vo;
				Object no = vm.get("name");
				if (!(no instanceof String))
					throw new IllegalArgumentException("Unexpected type of variable name: Expected string, found " + vo.toString());
				String name = (String)no;
				Object to = vm.get("type");
				/* We don't actually care about the
				 * states lower bound, we just want to
				 * validate the type.
				 */
				JaniUtils.typeBounds(to);
				Object io = vm.get("initial-value");
				long initial = 0;
				initial = JaniUtils.getConstantLong(io, constants);
				if (initial < Integer.MIN_VALUE || initial > Integer.MAX_VALUE)
					throw new IllegalArgumentException("Initial value of variable '" + name + "' exceeds 32 bits.");
				initValues.put(name, (int)initial);
			}
		}
		if (janiData.containsKey("restrict-initial"))
			throw new IllegalArgumentException("Local initial state restrictions not yet supported.");
		HashMap<String, Integer> locations = new HashMap<>();
		Object loco = janiData.get("locations");
		if (!(loco instanceof Object[]))
			throw new IllegalArgumentException("Locations should be array, not: " + loco);
		Object[] locs = (Object[]) loco;
		for (Object loc : locs) {
			if (!(loc instanceof Map))
				throw new IllegalArgumentException("Location should be JSON object, not: " + loc);
			Map ldata = (Map)loc;
			if (!ldata.containsKey("name"))
				throw new IllegalArgumentException("Unnamed location");
			String name = ldata.get("name").toString();
			if (ldata.containsKey("time-progress"))
				throw new UnsupportedOperationException("Locations with time progress conditions are not supported.");
			Object tO = ldata.get("transient-values");
			HashMap<String, Expression> transs = null;
			if (tO != null) {
				if (!(tO instanceof Object[]))
					throw new IllegalArgumentException("Transient-values should be an array, not: " + tO);
				Object[] tvs = (Object[]) tO;
				if (tvs.length > 0)
					transs = new HashMap<>();
				for (Object tvO : tvs) {
					if (!(tvO instanceof Map))
						throw new IllegalArgumentException("Transient-value should be an Object, not: " + tvO);
					Map tv = (Map)tvO;
					Object rO = tv.get("ref");
					if (!(rO instanceof String))
						throw new IllegalArgumentException("Non-identifier transient value ref: " + rO);
					String ref = (String)rO;
					Expression value = Expression.fromJani(tv.get("value"));
					transs.put(ref, value);
				}
			}
			transients.put(locations.size(), transs);
			locations.put(name, locations.size());
		}
		labels = new String[locations.size()][0];
		targets = new int[locations.size()][0];
		guards = new Expression[locations.size()][0];
		assignments = createAssignmentArray(locations.size());
		Object inito = janiData.get("initial-locations");
		if (!(inito instanceof Object[]))
			throw new IllegalArgumentException("Initial locations should be array of size 1, not: " + inito);
		Object[] inits = (Object[]) inito;
		if (inits.length != 1)
			throw new IllegalArgumentException("Automata must currently have exactly one initial location.");
		Integer initialLocation = locations.get(inits[0].toString());
		if (initialLocation == null)
			throw new IllegalArgumentException("Unknown initial location: " + inits[0]);
		initialState = new int[initValues.size() + 1];
		this.variables = new String[initValues.size() + 1];
		initialState[0] = initialLocation;
		int i = 1;
		for (String variable : initValues.keySet()) {
			this.variables[i] = variable;
			initialState[i] = initValues.get(variable);
			i++;
		}
		Object edgeo = janiData.get("edges");
		if (edgeo == null)
			throw new IllegalArgumentException("Automata without edges not supported.");
		if (!(edgeo instanceof Object[]))
			throw new IllegalArgumentException("Edges in automata should be an array, not " + edgeo);
		Object[] edges = (Object[]) edgeo;
		for (Object eo : edges) {
			if (!(eo instanceof Map))
				throw new IllegalArgumentException("Each edge should be a JSON object, not: " + eo);
			Map edge = (Map)eo;
			Object src = edge.get("location");
			if (src == null)
				throw new IllegalArgumentException("Edge without source specified.");
			Integer srci = locations.get(src.toString());
			if (srci == null)
				throw new IllegalArgumentException("Unknown location as source of edge: " + src);
			String action = null;
			Object ao = edge.get("action");
			if (ao != null)
				action = 'i' + ao.toString();
			Object ro = edge.get("rate");
			if (ro == null && ao == null)
				action = "i";
			if (ro != null && ao != null)
				throw new IllegalArgumentException("An edge cannot have both an action and a rate.");
			if (ro != null) {
				if (!(ro instanceof Map))
					throw new IllegalArgumentException("Edge rates must be JSON objects, not: " + ro);
				Map rateMap = (Map)ro;
				double rate = JaniUtils.getConstantDouble(rateMap.get("exp"), constants);
				action = "r" + rate;
			}
			labels[srci] = Arrays.copyOf(labels[srci], labels[srci].length + 1);
			labels[srci][labels[srci].length - 1] = action;
			Expression guard = ConstantExpression.TRUE;
			if (edge.containsKey("guard")) {
				Object gO = edge.get("guard");
				if (!(gO instanceof Map))
					throw new IllegalArgumentException("Guard should be {\"exp\": ...}, not: " + gO);
				Map g = (Map)gO;
				guard = Expression.fromJani(g.get("exp"));
			}
			guards[srci] = Arrays.copyOf(guards[srci], guards[srci].length + 1);
			guards[srci][guards[srci].length - 1] = guard;
			Object destO = edge.get("destinations");
			if (!(destO instanceof Object[]))
				throw new IllegalArgumentException("Destinations of edges must be arrays (currently of size 1).");
			Object[] dests = (Object[])destO;
			if (dests.length != 1)
				throw new IllegalArgumentException("In-edge nondeterminism currently not supported, each edge must have one destination.");
			destO = dests[0];
			if (!(destO instanceof Map))
				throw new IllegalArgumentException("Each destination should be a JSON object, not: " + destO);
			Map dest = (Map)destO;
			destO = dest.get("location");
			Integer target = null;
			if (destO != null)
				target = locations.get(destO.toString());
			if (target == null)
				throw new IllegalArgumentException("Unknown target location: " + destO);
			/* Deliberatly ignoring probability, since we
			 * only support non-probabilistic transitions */
			targets[srci] = Arrays.copyOf(targets[srci], targets[srci].length + 1);
			targets[srci][targets[srci].length - 1] = target;
			Object assignO = dest.get("assignments");
			if (assignO == null)
				assignO = new Object[0];
			if (!(assignO instanceof Object[]))
				throw new IllegalArgumentException("Assignments shoud be array, not: " + assignO);
			Object[] assignments = (Object[])assignO;
			HashMap<String, Expression> ts = transients.get(target);
			HashMap<String, Expression> assignMap;
			if (ts == null)
				assignMap = new HashMap<>();
			else
				assignMap = new HashMap<>(ts);
			for (Object assO : assignments) {
				if (!(assO instanceof Map))
					throw new IllegalArgumentException("Assignment should be JSON object, not: " + assO);
				Map assignment = (Map)assO;
				Object refO = assignment.get("ref");
				if (!(refO instanceof String))
					throw new IllegalArgumentException("Assignment only supported to identifiers.");
				String ref = (String)refO;
				Object valO = assignment.get("value");
				Expression val = Expression.fromJani(valO);
				assignMap.put(ref, val);
			}
			this.assignments[srci] = Arrays.copyOf(this.assignments[srci], labels[srci].length);
			this.assignments[srci][labels[srci].length - 1] = assignMap;
		}
	}

	public static SymbolicAutomaton fromJani(Map janiData,
	                                         Map<String, Number> constants)
	{
		return new SymbolicAutomaton(janiData, constants);
	}	

	@SuppressWarnings("unchecked")
	private HashMap<String, Expression>[][] createAssignmentArray(int len)
	{
		return (HashMap<String, Expression>[][]) new HashMap[len][0];
	}

	@Override
	public Map<String, Integer> getVarValues(int[] state)
	{
		HashMap<String, Integer> ret = new HashMap<>();
		/* Skip first variable because it is the location number. */
		for (int i = 1; i < variables.length; i++)
			ret.put(variables[i], state[i]);
		return ret;
	}

	public int getVarValue(String var, int[] state)
	{
		for (int i = 1; i < variables.length; i++)
			if (var.equals(variables[i]))
				return state[i];
		throw new IllegalArgumentException("Requested value of variable '" + var + "' but that is not a variable of this automaton");
	}

	/** @return The number of states in this automaton */
	public int getNumStates()
	{
		return labels.length;
	}

	public int[] getInitialState()
	{
		return initialState.clone();
	}

	public TreeSet<LTS.Transition> getTransitions(int[] from)
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		Map<String, Integer> values = getVarValues(from);
		int src = from[0];
		for (int i = 0; i < labels[src].length; i++) {
			Expression guard = guards[src][i];
			guard = guard.simplify(values);
			if (guard.getReferencedVariables().isEmpty()) {
				Number v = guard.evaluate(values);
				if (v.longValue() == 0)
					continue;
			}
			String label = labels[src][i];
			int[] target = from.clone();
			target[0] = targets[src][i];
			Map<String, Expression> assigns = assignments[src][i];
			Map<String, Expression> nonLocals = new HashMap<>(assigns);
			for (int j = 1; j < variables.length; j++) {
				Expression newVal = assigns.get(variables[j]);
				if (newVal == null)
					continue;
				nonLocals.remove(variables[j]);
				Number v = newVal.evaluate(values);
				if (v == null)
					throw new UnsupportedOperationException("Local variable assignment that depends on non-local variable.");
				target[i] = v.intValue();
			}
			ret.add(new LTS.Transition(label, target, guard, nonLocals));
		}
		return ret;
	}

	public int stateSize()
	{
		return variables.length;
	}

	public String toString()
	{
		StringBuilder ret = new StringBuilder();
		ret.append(String.format("Number of states: %d\n", getNumStates()));
		ret.append(String.format("Initial state: %d\n", Arrays.toString(initialState)));
		for (int i = 0; i < getNumStates(); i++) {
			for (int j = 0; j < labels[i].length; j++) {
				ret.append(String.format("if %s then %5d ---> %5d (%s)\n",
						guards[i][j],
						i,
						targets[i][j],
						labels[i][j]));
			}
		}
		return ret.toString();
	}
}
