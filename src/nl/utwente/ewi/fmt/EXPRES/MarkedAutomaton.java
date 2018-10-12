package nl.utwente.ewi.fmt.EXPRES;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class MarkedAutomaton implements MarkableLTS
{
	private final Automaton aut;
	private final TreeMap<String, Integer> markLabels;

	public MarkedAutomaton(Automaton aut)
	{
		this.aut = aut;
		markLabels = new TreeMap<String, Integer>();
	}

	public int stateSize()
	{
		return 2;
	}

	public int[] getInitialState()
	{
		return new int[] {0, 0};
	}

	public Map<String, Integer> getVarValues(int[] state)
	{
		return Collections.singletonMap("marked", state[state.length - 1]);
	}

	public Set<LTS.Transition> getTransitions(int[] from)
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		int i = 0, target, src = from[0];
		while ((target = aut.getTransitionTarget(src, i)) >= 0) {
			String label = aut.getTransitionLabel(src, i);
			int[] t = new int[2];
			t[0] = target;
			t[1] = from[1];
			Integer mark = markLabels.get(label);
			if (mark != null)
				t[1] = mark;
			ret.add(new LTS.Transition(label, t));
			i++;
		}
		return ret;
	}

	public void markStatesAfter(String label, int val)
	{
		markLabels.put(label, val);
	}
}
