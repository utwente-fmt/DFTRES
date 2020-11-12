package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.util.*;
import nl.ennoruijters.util.JSONParser;
import nl.utwente.ewi.fmt.EXPRES.LTS;
import nl.utwente.ewi.fmt.EXPRES.expression.ConstantExpression;
import nl.utwente.ewi.fmt.EXPRES.expression.Expression;

public class JaniModel
{
	public enum JaniBaseType {BOOLEAN, INTEGER, REAL}
	public static class JaniType {
		public final JaniBaseType base;
		public final Number minimum;
		public final Number maximum;

		public JaniType(JaniBaseType base, Number min,
		                Number max)
		{
			this.base = base;
			this.minimum = min;
			this.maximum = max;
		}
	}
	public static class JaniVariable {
		public final JaniType type;
		public final String name;
		public final Number initial;

		public JaniVariable(JaniType type, String name, Number initial)
		{
			this.type = type;
			this.name = name;
			this.initial = initial;
		}
	}

	private final int[][] vectorAutomata;
	private final String[][] vectorLabels;
	private final String[] synchronizedLabels;
	private final SymbolicAutomaton[] automata;

	private final Map<String, JaniVariable> globalVars;
	private final Map<String, Expression> transientGlobals; /* Maps the variable name to its initial value. */

	private final HashSet<Property> properties;
	private static final boolean VERBOSE = false;

	/** Read a JANI model from a file.
	 * @param filename The file to read.
	 * @param overrideConstants The values of externally specified constants
	 * @param additionalProperties Additional properties to add to
	 * the model
	 */
	public JaniModel(
			String filename,
			Map<String, Number> overrideConstants,
			Set<Property> additionalProperties
	)
			throws IOException
	{
		globalVars = new TreeMap<>();
		transientGlobals = new TreeMap<>();
		Object jani = JSONParser.readJsonFromFile(filename);
		if (!(jani instanceof Map))
			throw new IllegalArgumentException("JANI file root is not an object.");
		Map<?, ?> root = (Map<?, ?>) jani;
		Object janiVers = root.get("jani-version");
		if (!Long.valueOf(1).equals(janiVers))
			System.err.println("Jani version != 1 may not be supported (file is version " + janiVers + ").");
		Object type = root.get("type");
		if (!"ma".equals(type) && !"ctmc".equals(type) && !"dtmc".equals(type))
			throw new IllegalArgumentException("Only Markov Automata are currently supported.");
		TreeMap<String, Number> constants = new TreeMap<>(overrideConstants);
		Object constsO = root.get("constants");
		if (constsO != null) {
			if (!(constsO instanceof Object[]))
				throw new IllegalArgumentException("Constants should be an array.");
			Object[] consts = (Object[])constsO;
			for (Object cO : consts) {
				if (!(cO instanceof Map))
					throw new IllegalArgumentException("Constant found that is not an object.");
				Map<?, ?> c = (Map<?, ?>)cO;
				Object nO = c.get("name");
				if (nO == null)
					throw new IllegalArgumentException("Constant declaration with name.");
				if (!(nO instanceof String))
					throw new IllegalArgumentException("Constant declaration non-string name.");
				String name = (String) nO;
				if (overrideConstants.containsKey(name))
					continue;
				Object vO = c.get("value");
				if (vO == null)
					throw new IllegalArgumentException("Model parameter '" + name + "' not specified.");
				if (!(vO instanceof Number || vO instanceof Boolean))
					vO = JaniUtils.getConstantDouble(vO, constants);
				if (vO instanceof Number)
					constants.put(name, (Number)vO);
				else if (vO.equals(Boolean.TRUE))
					constants.put(name, 1);
				else if (vO.equals(Boolean.FALSE))
					constants.put(name, 0);
				else
					throw new AssertionError("Number or Boolean constant value is neither Long, nor true or false.");
			}
		}
		Object autos = root.get("automata");
		if (!(autos instanceof Object[])) {
			throw new IllegalArgumentException("Unexpected JSON type of 'automata': Expected array, found " + autos);
		}
		Object[] auts = (Object[])autos;
		Map<String, SymbolicAutomaton> declaredAuts = new TreeMap<>();
		for (Object aut : auts) {
			if (!(aut instanceof Map))
				throw new IllegalArgumentException("Automaton should be an object, not: " + aut);
			Map<?, ?> autm = (Map<?, ?>)aut;
			Object n = autm.get("name");
			if (n != null) {
				SymbolicAutomaton a;
				a = SymbolicAutomaton.fromJani(autm, constants);
				declaredAuts.put(n.toString(), a);
			}
		}
		properties = new HashSet<>();
		Object propO = root.get("properties");
		if (propO != null) {
			if (!(propO instanceof Object[]))
				throw new IllegalArgumentException("Properties should be array, not: " + propO);
			Object[] props = (Object[])propO;
			for (Object propO2 : props) {
				if (!(propO2 instanceof Map))
					throw new IllegalArgumentException("Property should be object, not: " + propO2);
				Map<?, ?> prop = (Map<?, ?>)propO2;
				try {
					Property p = parseJaniProperty(prop, constants);
					properties.add(p);
				} catch (UnsupportedOperationException e) {
					System.err.println(e.getMessage());
				}
			}
		}
		properties.addAll(additionalProperties);
		Object variables = root.get("variables");
		if (variables != null) {
			if (!(variables instanceof Object[]))
				throw new IllegalArgumentException("Unexpected argument type of global variables list: Expected array, found " + variables.getClass());
			Map<String, List<Object>> varUsers;
			varUsers = new TreeMap<>();
			for (String name : declaredAuts.keySet()) {
				SymbolicAutomaton a = declaredAuts.get(name);
				List<Object> users;
				for (String vName : a.getExternVariables()) {
					users = varUsers.get(vName);
					if (users == null) {
						users = new ArrayList<>();
						varUsers.put(vName, users);
					}
					users.add(name);
				}
			}
			for (Property p : properties) {
				List<Object> users;
				for (String name : p.getReferencedVariables()) {
					users = varUsers.get(name);
					if (users == null) {
						users = new ArrayList<>();
						varUsers.put(name, users);
					}
					users.add(p);
				}
			}
			Object[] vars = (Object[]) variables;
			for (Object vo : vars) {
				if (vo == null)
					throw new IllegalArgumentException("Unexpected null in variable list.");
				if (!(vo instanceof Map)) {
					throw new IllegalArgumentException("Unexpected global variable entry: Expected object, found " + vo);
				}
				Map<?, ?> vm = (Map<?, ?>)vo;
				JaniVariable var = parseVariable(vm, constants);
				List<Object> users = varUsers.get(var.name);
				String name = var.name;
				if (users == null) {
					if (VERBOSE)
						System.err.println("Ignoring unused variable: " + name);
					continue;
				}
				if (vm.get("transient") == Boolean.TRUE) {
					transientGlobals.put(name, new ConstantExpression(var.initial));
					globalVars.put(name, var);
				} else if (users.size() == 1) {
					if (VERBOSE)
						System.err.println("Interning variable: " + name);
					Object user = users.get(0);
					int init = JaniUtils.safeToInteger(var.initial);
					if (user instanceof String) {
						if (var.type.base == JaniBaseType.REAL)
							throw new UnsupportedOperationException("Real variables are currently unsupported");
						String aName = (String)user;
						SymbolicAutomaton a;
						a = declaredAuts.get(aName);
						declaredAuts.remove(aName);
						a = a.addVariable(name, init);
						declaredAuts.put(aName, a);
					} else if (user instanceof Property) {
						Property p = (Property)user;
						Map<String, Number> values;
						values = Map.of(name, init);
						properties.remove(p);
						p = p.simplify(values);
						properties.add(p);
						users.set(0, p);
					} else {
						throw new AssertionError("Unknown type of variable user: " + user);
					}
				} else {
					globalVars.put(name, var);
				}
			}
		}
		if (root.get("restrict-initial") != null) {
			Object iO = root.get("restrict-initial");
			if (!(iO instanceof Map))
				throw new IllegalArgumentException("Unexpected JSON type of 'restrict-initial': Expected Object, found " + iO);
			Map<?, ?> restrict = (Map<?, ?>)iO;
			iO = restrict.get("exp");
			if (iO != Boolean.TRUE)
				throw new IllegalArgumentException("Explicit initial states currently not supported.");
		}
		Object syso = root.get("system");
		if (syso == null)
			throw new IllegalArgumentException("No system definition.");
		if (!(syso instanceof Map))
			throw new IllegalArgumentException("System declaration should be a composition, not: " + syso);
		Map<?, ?> sysComp = (Map<?, ?>)syso;
		Object elso = sysComp.get("elements");
		if (!(elso instanceof Object[]))
			throw new IllegalArgumentException("Elements of system composition should be an array.");
		Object[] elements = (Object[])elso;
		automata = new SymbolicAutomaton[elements.length];
		for (int i = 0; i < elements.length; i++) {
			Object element = elements[i];
			if (!(element instanceof Map))
				throw new IllegalArgumentException("Element of composition is not a JSON Object: " + element);
			Map<?, ?> emap = (Map<?, ?>)element;
			Object aut = emap.get("automaton");
			if (!(aut instanceof String))
				throw new IllegalArgumentException("Composition elements should be identifiers (of automata), not: " + aut);
			String autName = (String)aut;
			automata[i] = declaredAuts.get(autName);
			if (automata[i] == null)
				throw new IllegalArgumentException("Element declaration contains undefined automaton: " + autName);
		}
		ArrayList<Integer> extraSyncs = new ArrayList<>();
		for (int i = 0; i < automata.length; i++) {
			if (automata[i].hasInternalTransitions())
				extraSyncs.add(i);
		}
		int nSync;
		Object synco = sysComp.get("syncs");
		if (synco == null) {
			vectorAutomata = new int[extraSyncs.size()][];
			vectorLabels = new String[extraSyncs.size()][];
			synchronizedLabels = new String[extraSyncs.size()];
			nSync = 0;
		} else {
			if (!(synco instanceof Object[]))
				throw new IllegalArgumentException("Synchronization specification should be array, not: " + synco);
			Object[] syncs = (Object[]) synco;
			int count = syncs.length + extraSyncs.size();
			nSync = syncs.length;
			vectorAutomata = new int[count][];
			vectorLabels = new String[count][];
			synchronizedLabels = new String[count];
			for (int i = 0; i < count; i++) {
				Object syncItem = syncs[i];
				if (!(syncItem instanceof Map))
					throw new IllegalArgumentException("Synchronization item should be object, not: " + syncItem);
				Map<?, ?> syncMap = (Map<?, ?>)syncItem;
				Object syncLineO = syncMap.get("synchronise");
				if (syncLineO == null || !(syncLineO instanceof Object[]))
					throw new IllegalArgumentException("Synchronize line should be array of actions.");
				Object[] syncLine = (Object[]) syncLineO;
				int numAutomata = 0, j = 0;
				for (Object o : syncLine) {
					if (o != null)
						numAutomata++;
				}
				vectorAutomata[i] = new int[numAutomata];
				vectorLabels[i] = new String[numAutomata];
				numAutomata = 0;
				for (Object o : syncLine) {
					if (o != null) {
						vectorAutomata[i][numAutomata] = j;
						vectorLabels[i][numAutomata] = 'i' + o.toString();
						numAutomata++;
					}
					j++;
				}
				Object resultAction = syncMap.get("result");
				if (resultAction != null)
					synchronizedLabels[i] = "i" + resultAction.toString();
			}
		}
		for (Integer i : extraSyncs) {
			vectorAutomata[nSync] = new int[] {i};
			vectorLabels[nSync] = new String[] {"i"};
			synchronizedLabels[nSync] = "i";
			nSync++;
		}
	}

	private static JaniVariable parseVariable(Map<?, ?> obj, Map<String, Number> constants)
	{
		Object no = obj.get("name");
		if (!(no instanceof String))
			throw new IllegalArgumentException("Unexpected type of variable name: Expected string, found " + no.toString());
		String name = (String)no;
		Object to = obj.get("type");
		JaniType type = JaniUtils.parseType(to, constants);
		Object io = obj.get("initial-value");
		Number init = JaniUtils.getConstantNum(io, constants);
		return new JaniVariable(type, name, init);
	}

	private static Property parseJaniProperty(Map<?, ?> prop, Map<String, Number> constants)
	{
		Object nameO = prop.get("name");
		if (!(nameO instanceof String))
			throw new IllegalArgumentException("Property name should be string, not: " + nameO);
		String name = (String) nameO;
		Object expO = prop.get("expression");
		if (!(expO instanceof Map))
			throw new IllegalArgumentException("Property expression should be object, not: " + expO);
		Map<?, ?> expr = (Map<?, ?>)expO;
		if (!"filter".equals(expr.get("op")))
			throw new UnsupportedOperationException("I don't know what to do property operation '" + expr.get("op") + "'");
		Object fun = expr.get("fun");
		if (!("max".equals(fun) || "min".equals(fun) || "avg".equals(fun) || "values".equals(fun)))
			throw new UnsupportedOperationException("Unsupported property function: " + fun);
		if (!Collections.singletonMap("op", "initial").equals(expr.get("states")))
			throw new UnsupportedOperationException("Only properties over initial states currently supported.");
		Object valO = expr.get("values");
		if (!(valO instanceof Map))
			throw new IllegalArgumentException("Property values should be object, not: " + valO);
		Map<?, ?> values = (Map<?, ?>)valO;
		Object op = values.get("op");
		Property.Type propType = null;
		if ("Smax".equals(op) || "Smin".equals(op))
			propType = Property.Type.STEADY_STATE;
		else if ("Pmax".equals(op) || "Pmin".equals(op))
			propType = Property.Type.REACHABILITY;
		else if ("Emax".equals(op) || "Emin".equals(op))
			propType = Property.Type.EXPECTED_VALUE;
		else
			throw new UnsupportedOperationException("Unsupported property operation: " + op);
		double timeBound = 0;
		Expression reachTarget = null;
		if (propType == Property.Type.STEADY_STATE
		    || propType == Property.Type.REACHABILITY)
		{
			expO = values.get("exp");
		} else {
			expO = values.get("reach");
		}
		if (expO instanceof String) {
			reachTarget = Expression.fromJani(expO);
		} else if (expO instanceof Map) {
			expr = (Map<?, ?>)expO;
			if ("U".equals(expr.get("op"))) {
				if (!Boolean.TRUE.equals(expr.get("left")))
					throw new UnsupportedOperationException("Until formulae currently only supported with 'true' left operand.");
				expO = expr.get("right");
				reachTarget = Expression.fromJani(expO);
			} else if ("F".equals(expr.get("op"))) {
				expO = expr.get("exp");
				reachTarget = Expression.fromJani(expO);
			} else {
				try {
					reachTarget = Expression.fromJani(expO);
				} catch (UnsupportedOperationException e) {
					throw new UnsupportedOperationException("The only currently supported formulae are variables and formulae 'F variable' or 'true U variable' (with time bound); Ignoring property '" + name + "'");
				}
			}
			Object boundO = expr.get("time-bounds");
			if (boundO == null) {
				timeBound = Double.POSITIVE_INFINITY;
			} else if (boundO instanceof Map) {
				Map<?, ?> bound = (Map<?, ?>)boundO;
				for (Object o : bound.keySet()) {
					if ("upper".equals(o)) {
						o = bound.get("upper");
						timeBound = JaniUtils.getConstantDouble(o, constants).doubleValue();
					} else if (!"upper-exclusive".equals(o)) {
						throw new UnsupportedOperationException("Only constant-valued upper bounds currently supported.");
					}
				}
			}
		} else if (propType == Property.Type.EXPECTED_VALUE
		           && expO == null)
		{
			reachTarget = null;
		} else {
			throw new IllegalArgumentException("Property expression should be identifier or expression.");
		}
		if (reachTarget != null)
			reachTarget = reachTarget.simplify(constants);
		if (propType == Property.Type.EXPECTED_VALUE) {
			timeBound = Double.POSITIVE_INFINITY;
			if (values.containsKey("step-instant"))
				throw new UnsupportedOperationException("Step-bounded properties currently not supported.");
			if (values.containsKey("reward-instants"))
				throw new UnsupportedOperationException("Reward-instant queries currently not supported.");
			expO = values.get("exp");
			if (expO == null)
				throw new IllegalArgumentException("Expected-reward query without reward expression.");
			Expression transientRew = Expression.fromJani(expO);
			Expression cumulativeRew = null;
			expO = values.get("accumulate");
			if (expO != null) {
				if (!(expO instanceof Object[]))
					throw new IllegalArgumentException("Reward accumulation should be an array.");
				Object[] accs = (Object[])expO;
				if (accs.length > 1)
					throw new UnsupportedOperationException("Only time-accumulating or time-instant reward queries currently supported.");
				if (accs.length == 1) {
					if (!"time".equals(accs[0]))
						throw new UnsupportedOperationException("Only time-accumulating or time-instant reward queries currently supported.");
				}
				cumulativeRew = transientRew;
				transientRew = null;
			}
			if (values.containsKey("time-instant")) {
				Expression instant = Expression.fromJani(values.get("time-instant"));
				instant = instant.simplify(constants);
				if (instant.getReferencedVariables().size() > 0) {
					throw new UnsupportedOperationException("Time-instant expression involves variables.");
				}
				timeBound = instant.evaluate(constants).doubleValue();
			}
			return new Property(propType, timeBound, reachTarget, name, cumulativeRew, transientRew);
		}
		return new Property(propType, timeBound, reachTarget, name);
	}

	private Composition getComposition()
	{
		return new Composition(automata, vectorAutomata, vectorLabels,
		                       synchronizedLabels, globalVars,
		                       transientGlobals);
	}

	public LTS getLTS(Set<String> keepProperties) {
		if (automata.length != 1)
			return getComposition();
		SymbolicAutomaton ret = automata[0];
		for (String label : ret.getAllTransitionLabels()) {
			boolean found = false;
			for (String[] sync : vectorLabels) {
				for (String s : sync) {
					if (s.equals(label)) {
						found = true;
						break;
					}
				}
			}
			if (!found) {
				System.err.println("Warning: Transition label '" + label + "' will never be taken as it is not in any synchronization element");
				return getComposition();
			}
		}
		Set<String> propertyVars = new TreeSet<>();
		HashMap<String, Expression> transients = new HashMap<>();
		Iterator<Property> propertyIt = properties.iterator();
		while (propertyIt.hasNext()) {
			Property p = propertyIt.next();
			if (!keepProperties.contains(p.name))
				propertyIt.remove();
			else
				propertyVars.addAll(p.getReferencedVariables());
		}
		for (String name : transientGlobals.keySet()) {
			if (propertyVars.contains(name)) {
				ret = ret.addTransientVariable(name,
						transientGlobals.get(name));
			} else {
				ret = ret.removeVariable(name);
				if (ret == null)
					return getComposition();
			}
			globalVars.remove(name);
		}
		for (var entry : globalVars.entrySet()) {
			String name = entry.getKey();
			JaniVariable info = entry.getValue();
			if (info.type.base == JaniBaseType.REAL)
				throw new UnsupportedOperationException("Real-values variables not supported");
			int init = JaniUtils.safeToInteger(info.initial);
			ret = ret.addVariable(name, init);
			System.err.println("Internalized " + name);
		}
		return ret;
	}

	public Set<Property> getProperties() {
		return Collections.unmodifiableSet(properties);
	}
}
