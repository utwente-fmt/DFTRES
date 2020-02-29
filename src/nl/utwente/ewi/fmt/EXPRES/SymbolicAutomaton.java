package nl.utwente.ewi.fmt.EXPRES;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import nl.utwente.ewi.fmt.EXPRES.JaniModel.JaniType;
import nl.utwente.ewi.fmt.EXPRES.JaniModel.JaniBaseType;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;

public class SymbolicAutomaton implements LTS {
	private final int[] initialState;
	private final String[] variables; /* First entry is null to
	                                   * indicate the state number. */

	/* targets[i][j] denotes the target state of the j'th transition
	 * from state i. labels[i][j] denotes the label of that
	 * transition. */
	private final int targets[][];
	private final String labels[][];
	private final Expression guards[][];
	private final Expression probs[][];
	private final HashMap<String, Expression> assignments[][];
	private final boolean numberedVariables;

	private SymbolicAutomaton(SymbolicAutomaton other, String addedVar,
	                          int addedVal)
	{
		this.targets = other.targets;
		this.labels = other.labels;
		this.guards = other.guards;
		this.probs = other.probs;
		this.assignments = other.assignments;
		int n = other.variables.length;
		variables = Arrays.copyOf(other.variables, n + 1);
		initialState = Arrays.copyOf(other.initialState, n + 1);
		variables[n] = addedVar;
		initialState[n] = addedVal;
		numberedVariables = tryToNumberVariables();
	}

	private SymbolicAutomaton(SymbolicAutomaton other, String toRemove)
	{
		this.targets = other.targets;
		this.labels = other.labels;
		this.guards = other.guards;
		this.probs = other.probs;
		this.variables = other.variables;
		this.initialState = other.initialState;
		this.assignments = other.assignments.clone();
		for (HashMap<String, Expression>[] as : assignments) {
			if (as == null)
				continue;
			for (int i = 0; i < as.length; i++) {
				HashMap<String, Expression> map = as[i];
				if (map.containsKey(toRemove)) {
					map = new HashMap<>(map);
					map.remove(toRemove);
					as[i] = map;
				}
			}
		}
		numberedVariables = tryToNumberVariables();
	}

	/* Private since 'Map' may in the future be suitable for
	 * multiple types.
	 */
	private SymbolicAutomaton(Map<?, ?> janiData, Map<String, Number> constants)
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
				Map<?, ?> vm = (Map<?, ?>)vo;
				Object no = vm.get("name");
				if (!(no instanceof String))
					throw new IllegalArgumentException("Unexpected type of variable name: Expected string, found " + vo.toString());
				String name = (String)no;
				Object to = vm.get("type");
				JaniBaseType type;
				type = JaniUtils.parseType(to, constants).base;
				if (type != JaniBaseType.BOOLEAN
				    && type != JaniBaseType.INTEGER) {
					throw new UnsupportedOperationException("Unsupported type: " + type);
				}
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
			Map<?, ?> ldata = (Map<?, ?>)loc;
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
					Map<?, ?> tv = (Map<?, ?>)tvO;
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
		probs = new Expression[locations.size()][0];
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
			boolean hasProbabilisticEdge = false;
			if (!(eo instanceof Map))
				throw new IllegalArgumentException("Each edge should be a JSON object, not: " + eo);
			Map<?, ?> edge = (Map<?, ?>)eo;
			Object src = edge.get("location");
			if (src == null)
				throw new IllegalArgumentException("Edge without source specified.");
			Integer srci = locations.get(src.toString());
			if (srci == null)
				throw new IllegalArgumentException("Unknown location as source of edge: " + src);
			for (String l : labels[srci]) {
				if (l.charAt(0) == 'p') {
					hasProbabilisticEdge = true;
					break;
				}
			}
			String action = null;
			Object ao = edge.get("action");
			Object ro = edge.get("rate");
			if (ro == null && ao == null) {
				action = "i";
			} else if (ao != null && ro == null) {
				action = 'i' + ao.toString();
			} else if (ao == null && ro != null) {
				if (!(ro instanceof Map))
					throw new IllegalArgumentException("Edge rates must be JSON objects, not: " + ro);
				Map<?, ?> rateMap = (Map<?, ?>)ro;
				Number rate = JaniUtils.getConstantDouble(rateMap.get("exp"), constants);
				action = "r" + rate;
			} else { /* both action and rate specified */
				if (!(ro instanceof Map))
					throw new IllegalArgumentException("Edge rates must be JSON objects, not: " + ro);
				Map<?, ?> rateMap = (Map<?, ?>)ro;
				Number rate = JaniUtils.getConstantDouble(rateMap.get("exp"), constants);
				action = "c" + rate + ";" + ao.toString();
			}
			Expression guard = ConstantExpression.TRUE;
			if (edge.containsKey("guard")) {
				Object gO = edge.get("guard");
				if (!(gO instanceof Map))
					throw new IllegalArgumentException("Guard should be {\"exp\": ...}, not: " + gO);
				Map<?, ?> g = (Map<?, ?>)gO;
				guard = Expression.fromJani(g.get("exp"));
				Number cGuard = guard.evaluate(constants);
				if (cGuard != null) {
					if (cGuard.doubleValue() == 0)
						guard = ConstantExpression.FALSE;
					else
						guard = ConstantExpression.TRUE;
				} else {
					guard = guard.simplify(constants);
				}
			}
			if (guard == ConstantExpression.FALSE)
				continue;
			Object destsO = edge.get("destinations");
			if (!(destsO instanceof Object[]))
				throw new IllegalArgumentException("Destinations of edges must be arrays (currently of size 1).");
			Object[] dests = (Object[])destsO;
			for (Object destO : dests) {
				if (!(destO instanceof Map))
					throw new IllegalArgumentException("Each destination should be a JSON object, not: " + destO);
				Map<?, ?> dest = (Map<?, ?>)destO;
				destO = dest.get("location");
				Integer target = null;
				if (destO != null)
					target = locations.get(destO.toString());
				if (target == null)
					throw new IllegalArgumentException("Unknown target location: " + destO);
				Object probO = dest.get("probability");
				if (probO == null && dests.length > 1)
					throw new UnsupportedOperationException("Edges with multiple destinations must have probabilities");
				Expression prob = null;
				if (probO != null) {
					if (!(probO instanceof Map))
						throw new IllegalArgumentException("Probability should be JANI expression, not: " + probO);
					Map<?, ?> probMap = (Map<?, ?>)probO;
					prob = Expression.fromJani(probMap.get("exp"));
					Expression orig = prob;
					prob = prob.simplify(constants);
					prob = prob.simplify(guard.booleanExpression(), 1);
					Number constant = prob.evaluate(constants);
					if (constant != null) {
						if (constant.doubleValue() == 0)
							continue;
						else if (constant.doubleValue() == 1)
							prob = null;
					}
				}
				if (prob != null && !action.equals("i"))
					throw new UnsupportedOperationException("Probabilistic transitions must not have labels");
				if (prob != null && hasProbabilisticEdge)
					throw new UnsupportedOperationException("Nondeterminism in selection of probabilistic transitions from " + src);
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
					Map<?, ?> assignment = (Map<?, ?>)assO;
					Object refO = assignment.get("ref");
					if (!(refO instanceof String))
						throw new IllegalArgumentException("Assignment only supported to identifiers.");
					String ref = (String)refO;
					Object valO = assignment.get("value");
					Expression val = Expression.fromJani(valO);
					assignMap.put(ref, val.simplify(constants));
				}
				labels[srci] = Arrays.copyOf(labels[srci], labels[srci].length + 1);
				labels[srci][labels[srci].length - 1] = action;
				guards[srci] = Arrays.copyOf(guards[srci], guards[srci].length + 1);
				guards[srci][guards[srci].length - 1] = guard;
				targets[srci] = Arrays.copyOf(targets[srci], targets[srci].length + 1);
				targets[srci][targets[srci].length - 1] = target;
				this.assignments[srci] = Arrays.copyOf(this.assignments[srci], labels[srci].length);
				this.assignments[srci][labels[srci].length - 1] = assignMap;
				probs[srci] = Arrays.copyOf(probs[srci], probs[srci].length + 1);
				probs[srci][probs[srci].length - 1] = prob;
			}
		}
		numberedVariables = tryToNumberVariables();
	}

	public static SymbolicAutomaton fromJani(Map<?, ?> janiData,
	                                         Map<String, Number> constants)
	{
		return new SymbolicAutomaton(janiData, constants);
	}	

	@SuppressWarnings({"unchecked", "rawtypes"})
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

	private boolean tryToNumberVariables() {
		if (!getExternVariables().isEmpty())
			return false;
		if (variables.length > Character.MAX_VALUE)
			return false;
		HashMap<String, String> renames = new HashMap<>();
		for (char i = 1; i < variables.length; i++) {
			char[] str = new char[]{i};
			renames.put(variables[i], new String(str));
		}
		for (Expression[] gs : guards) {
			if (gs != null) {
				for (int i = gs.length - 1; i >= 0; i--) {
					gs[i] = gs[i].renameVars(renames);
					if (gs[i] == null)
						continue;
				}
			}
		}
		for (Expression[] ps : probs) {
			if (ps != null) {
				for (int i = ps.length - 1; i >= 0; i--) {
					if (ps[i] == null)
						continue;
					ps[i] = ps[i].renameVars(renames);
				}
			}
		}
		for (HashMap<String, Expression>[] as : assignments) {
			if (as == null)
				continue;
			for (int i = as.length - 1; i >= 0; i--) {
				HashMap<String, Expression> map = as[i];
				HashMap<String, Expression> newMap = new HashMap<>();
				for (var entry : map.entrySet()) {
					String k = renames.get(entry.getKey());
					Expression e = entry.getValue();
					e = e.renameVars(renames);
					newMap.put(k, e);
				}
				as[i] = newMap;
			}
		}
		return true;
	}

	public Set<String> getExternVariables() {
		if (numberedVariables)
			return Set.of();
		TreeSet<String> ret = new TreeSet<>();
		for (Expression[] gs : guards) {
			if (gs != null) {
				for (Expression g : gs)
					ret.addAll(g.getReferencedVariables());
			}
		}
		for (Expression[] ps : probs) {
			if (ps != null) {
				for (Expression p : ps) {
					if (p == null)
						continue;
					ret.addAll(p.getReferencedVariables());
				}
			}
		}
		for (HashMap<String, Expression>[] as : assignments) {
			if (as == null)
				continue;
			for (HashMap<String, Expression> map : as) {
				for (var entry : map.entrySet()) {
					ret.add(entry.getKey());
					Expression e = entry.getValue();
					ret.addAll(e.getReferencedVariables());
				}
			}
		}
		for (int i = variables.length - 1; i > 0; i--)
			ret.remove(variables[i]);
		return ret;
	}

	/** Add the specified variable.
	 */
	public SymbolicAutomaton addVariable(String name, int initialValue) {
		for (int i = variables.length - 1; i > 0; i--) {
			if (variables[i].equals(name)) {
				if (initialValue != initialState[i])
					throw new IllegalArgumentException("Trying to add variable '" + name + "' with intial value " + initialValue + ", but it already exists with initial value "+ initialState[i]);
				return this;
			}
		}
		return new SymbolicAutomaton(this, name, initialValue);
	}

	public SymbolicAutomaton removeVariable(String name) {
		for (int i = variables.length - 1; i > 0; i--) {
			if (variables[i].equals(name))
				throw new UnsupportedOperationException("Attempt to remove local variable");
		}
		TreeSet<String> ret = new TreeSet<>();
		for (Expression[] gs : guards) {
			if (gs == null)
				continue;
			for (Expression g : gs) {
				if (g.getReferencedVariables().contains(name))
					return null;
			}
		}
		for (Expression[] ps : probs) {
			if (ps == null)
				continue;
			for (Expression p : ps) {
				if (p == null)
					continue;
				if (p.getReferencedVariables().contains(name))
					return null;
			}
		}
		for (HashMap<String, Expression>[] as : assignments) {
			if (as == null)
				continue;
			for (HashMap<String, Expression> map : as) {
				for (var entry : map.entrySet()) {
					if (entry.getKey().equals(name))
						continue;
					Expression e = entry.getValue();
					if (e.getReferencedVariables().contains(name))
						return null;
				}
			}
		}
		return new SymbolicAutomaton(this, name);
	}

	private void clearMarkovian(TreeSet<LTS.Transition> transitions) {
		Iterator<LTS.Transition> iter = transitions.iterator();
		while (iter.hasNext()) {
			String label = iter.next().label;
			if (label.charAt(0) == 'r')
				iter.remove();
		}
	}

	private class NumberMap extends AbstractMap<String, Integer> {
		private final int[] state;

		public NumberMap(int[] state) {
			this.state = state;
		}

		@Override
		public Integer get(Object var) {
			if (!(var instanceof String))
				return null;
			char b = ((String)var).charAt(0);
			return state[b];
		}

		@Override
		public boolean containsKey(Object var) {
			if (!(var instanceof String))
				return false;
			char b = ((String)var).charAt(0);
			return b > 0 && b < state.length;
		}

		@Override
		public Set<Map.Entry<String, Integer>> entrySet() {
			throw new UnsupportedOperationException();
		}
	}

	public TreeSet<LTS.Transition> getTransitions(int[] from)
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		Map<String, Integer> values;
		if (!numberedVariables)
			values = getVarValues(from);
		else
			values = new NumberMap(from);
		int src = from[0];
		boolean isProbabilistic = false, isInteractive = false;
		for (int i = 0; i < labels[src].length; i++) {
			String label = labels[src][i];
			if (isProbabilistic) {
				if (label.charAt(0) == 'r')
					continue;
			}
			Expression guard = guards[src][i];
			Number gVal = guard.evaluate(values);
			if (gVal != null) {
				if (gVal.doubleValue() == 0)
					continue;
				guard = ConstantExpression.TRUE;
			} else {
				guard = guard.simplify(values);
			}
			if (probs[src][i] != null) {
				Number p = probs[src][i].evaluate(values);
				if (p == null)
					throw new UnsupportedOperationException("Probability depends on non-local variable: " + probs[src][i]);
				if (p.longValue() == 0)
					continue;
				if (!label.equals("i"))
					throw new UnsupportedOperationException("Probabilistic transition with named or non-interactive label: " + label);
				label = "p" + p;
				if (!isInteractive && !isProbabilistic)
					ret.clear();
				else if (!isProbabilistic)
					clearMarkovian(ret);
				isProbabilistic = true;
			}
			int[] target = from.clone();
			target[0] = targets[src][i];
			Map<String, Expression> assigns = assignments[src][i];
			Map<String, Expression> nonLocals;
			if (!numberedVariables) {
				nonLocals = new HashMap<>(assigns);
				for (int j = 1; j < variables.length; j++) {
					Expression newVal = assigns.get(variables[j]);
					if (newVal == null)
						continue;
					nonLocals.remove(variables[j]);
					Number v = newVal.evaluate(values);
					if (v == null)
						throw new UnsupportedOperationException("Local variable assignment that depends on non-local variable.");
					target[j] = v.intValue();
				}
			} else {
				nonLocals = Collections.emptyMap();
				for (Map.Entry<String, Expression> assign : assigns.entrySet()) {
					char pos = assign.getKey().charAt(0);
					Expression e = assign.getValue();
					Number v = e.evaluate(values);
					if (v == null)
						throw new UnsupportedOperationException("Local variable assignment that depends on non-local variable.");
					target[pos] = v.intValue();

				}
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
				ret.append(String.format("if %s then %5d ---> %5d (%s)",
						guards[i][j],
						i,
						targets[i][j],
						labels[i][j]));
				if (probs[i][j] != null)
					ret.append(String.format(" with probability " + probs[i][j]));
				ret.append('\n');
			}
		}
		return ret.toString();
	}
}
