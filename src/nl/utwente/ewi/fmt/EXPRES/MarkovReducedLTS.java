package nl.utwente.ewi.fmt.EXPRES;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class MarkovReducedLTS implements LTS
{
	private final LTS original;
	private final int[] initialState;
	
	public MarkovReducedLTS(LTS original)
	{
		this.original = original;
		this.initialState = original.getInitialState();
	}

	public Map<String, Integer> getVarValues(int[] state)
	{
		return original.getVarValues(state);
	}

	public int getVarValue(String var, int[] state)
	{
		return original.getVarValue(var, state);
	}

	public int[] getInitialState()
	{
		return markovTerminal(initialState);
	}

	/* data[0] = index, data[1] = lowlink, data[2] = onStack */
	private void strongConnect(LTS.StateWrapper node,
			ArrayList<LTS.StateWrapper> stack,
			HashMap<LTS.StateWrapper, int[]> bookkeeping,
			int index, HashSet<LTS.StateWrapper> BSCC_Nodes)
	{
		int[] data = bookkeeping.get(node);
		data[0] = data[1] = index++;
		stack.add(node);
		data[2] = 1;
		Set<LTS.Transition> outgoing = null;
		if (outgoing == null)
			outgoing = original.getTransitions(node.state);
		for (LTS.Transition t : outgoing) {
			if (t.label.charAt(0) == 'r')
				continue;
			if (t.guard.evaluate(getVarValues(node.state)).doubleValue() == 0)
				continue;
			if (!t.assignments.isEmpty())
				throw new UnsupportedOperationException("Assignments remain in Markov reduction step.");
			LTS.StateWrapper targ = new LTS.StateWrapper(t.target);
			int[] nData = bookkeeping.get(targ);
			if (nData == null) {
				nData = new int[3];
				bookkeeping.put(targ, nData);
				strongConnect(targ, stack, bookkeeping, index,
				              BSCC_Nodes);
				if (data[1] < nData[1])
					nData[1] = data[1];
			} else if (data[2] > 0) {
				if (data[0] < nData[1])
					nData[1] = data[0];
			}
		}

		if (data[0] == data[1]) {
			HashSet<LTS.StateWrapper> newSCC = new HashSet<>();
			LTS.StateWrapper next;
			do {
				next = stack.remove(stack.size() - 1);
				bookkeeping.get(next)[2] = 0;
				newSCC.add(next);
			} while (!next.equals(node));
			int[] tmp = node.state;
			Set<LTS.Transition> out = original.getTransitions(tmp);
			LTS.StateWrapper tb = null;
			for (LTS.Transition t : out) {
				if (t.label.charAt(0) == 'r')
					continue;
				tb = new LTS.StateWrapper(t.target);
				if (!newSCC.contains(tb)) {
					newSCC = null;
					break;
				}
			}
			if (newSCC != null)
				BSCC_Nodes.addAll(newSCC);
		}
	}

	private int[] markovTerminal(int from[])
	{
		LTS.StateWrapper node = new LTS.StateWrapper(from);
		ArrayList<LTS.StateWrapper> stack = new ArrayList<>();
		HashMap<LTS.StateWrapper, int[]> book = new HashMap<>();
		HashSet<LTS.StateWrapper> BSCC_Nodes = new HashSet<>();
		book.put(node, new int[3]);
		strongConnect(node, stack, book, 0, BSCC_Nodes);
		Set<LTS.Transition> finalTransitions = null;
		for (LTS.StateWrapper n : BSCC_Nodes) {
			int[] tmp = n.state;
			Set<LTS.Transition> out = original.getTransitions(tmp);
			HashSet<LTS.Transition> markovian = new HashSet<>();
			for (LTS.Transition t : out)
				if (t.label.charAt(0) == 'r')
					markovian.add(t);
			if (finalTransitions == null)
				finalTransitions = markovian;
			else
				if (!finalTransitions.equals(markovian))
					return null;
		}
		return BSCC_Nodes.iterator().next().state;
	}

	public TreeSet<LTS.Transition> getTransitions(int[] from)
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		Set<LTS.Transition> outgoing = original.getTransitions(from);

		for (LTS.Transition t : outgoing) {
			if (t.label.charAt(0) != 'r')
				continue;
			int[] endState = markovTerminal(t.target);
			if (endState != null)
				ret.add(new LTS.Transition(t.label, endState, null, null));
			else
				throw new UnsupportedOperationException("Model has not-obviously-spurious nondeterminism.");
		}
		return ret;
	}

	public int stateSize()
	{
		return initialState.length;
	}
}
