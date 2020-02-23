package nl.utwente.ewi.fmt.EXPRES;

import java.math.BigDecimal;
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
		try {
			return markovTerminal(initialState, new BigDecimal(-1));
		} catch (NondeterminismException e) {
			throw new RuntimeException(e);
		}
	}

	/* data[0] = index, data[1] = lowlink, data[2] = onStack.
	 * If time < 0, we just performed a Markovian transition and
	 * cannot immediately perform timed transitions.
	 * Otherwise, we are at the specified time (so we treat timed
	 * transitions that are compatible with that time as
	 * interactive).
	 * */
	private void strongConnect(LTS.StateWrapper node,
			ArrayList<LTS.StateWrapper> stack,
			HashMap<LTS.StateWrapperLike, byte[]> bookkeeping,
			int index, HashSet<LTS.StateWrapperLike> BSCC_Nodes,
			BigDecimal time)
		throws NondeterminismException
	{
		byte[] data = bookkeeping.get(node);
		data[0] = data[1] = (byte)index++;
		if (index > 255)
			throw new UnsupportedOperationException("Recursion too deep.");
		stack.add(node);
		data[2] = 1;
		Set<LTS.Transition> outgoing = null;
		if (outgoing == null)
			outgoing = original.getTransitions(node.state);
		for (LTS.Transition t : outgoing) {
			if (t.label.charAt(0) == 'r')
				continue;
			if (t.label.charAt(0) == 'p')
				continue;
			if (t.label.charAt(0) == 't') {
				if (time.signum() < 0)
					continue;
				BigDecimal td = new BigDecimal(t.label.substring(1));
				if (td.compareTo(time) != 0)
					continue;
			}
			if (t.guard.evaluate(getVarValues(node.state)).doubleValue() == 0)
				continue;
			if (!t.assignments.isEmpty())
				throw new UnsupportedOperationException("Assignments remain in Markov reduction step.");
			LTS.StateWrapper targ = new LTS.StateWrapper(t.target);
			byte[] nData = bookkeeping.get(targ);
			if (nData == null) {
				nData = new byte[3];
				bookkeeping.put(targ.tryReduce(), nData);
				strongConnect(targ, stack, bookkeeping, index,
				              BSCC_Nodes, time);
				int nd1 = ((int)nData[1]) & 0xff;
				int d1 = ((int)data[1]) & 0xff;
				if (d1 < nd1)
					nData[1] = data[1];
			} else if (data[2] > 0) {
				int nd1 = ((int)nData[1]) & 0xff;
				int d0 = ((int)data[0]) & 0xff;
				if (d0 < nd1)
					nData[1] = data[0];
			}
		}

		if (data[0] == data[1]) {
			HashSet<LTS.StateWrapperLike> newSCC = new HashSet<>();
			LTS.StateWrapper next;
			do {
				next = stack.remove(stack.size() - 1);
				LTS.StateWrapperLike red = next.tryReduce();
				byte[] bk = bookkeeping.get(red);
				bookkeeping.remove(red);
				bk[2] = 0;
				bookkeeping.put(red, bk);
				newSCC.add(red);
			} while (!next.equals(node));
			int[] tmp = node.state;
			Set<LTS.Transition> out = original.getTransitions(tmp);
			tmp = null;
			int[][] targets = new int[out.size()][];
			String[] labels = new String[out.size()];
			int i = 0;
			for (LTS.Transition t : out) {
				labels[i] = t.label;
				targets[i++] = t.target;
			}
			out = null;

			LTS.StateWrapperLike tb = null;
			for (i = labels.length - 1; i >= 0; i--) {
				if (labels[i].charAt(0) == 'r')
					continue;
				if (labels[i].charAt(0) == 'p')
					continue;
				if (labels[i].charAt(0) == 't')
					continue;
				tb = LTS.wrapUncomparable(targets[i]);
				targets[i] = null;
				if (!newSCC.contains(tb)) {
					newSCC = null;
					break;
				}
			}
			if (newSCC != null)
				BSCC_Nodes.addAll(newSCC);
		}
	}

	private int[] markovTerminal(int from[], BigDecimal time)
			throws NondeterminismException
	{
		int[] ret = null;
		LTS.StateWrapper node = new LTS.StateWrapper(from);
		ArrayList<LTS.StateWrapper> stack = new ArrayList<>();
		HashMap<LTS.StateWrapperLike, byte[]> book = new HashMap<>();
		HashSet<LTS.StateWrapperLike> BSCC_Nodes = new HashSet<>();
		book.put(node, new byte[3]);
		strongConnect(node, stack, book, 0, BSCC_Nodes, time);
		Set<LTS.Transition> finalTransitions = null;
		for (LTS.StateWrapperLike n : BSCC_Nodes) {
			int[] tmp = n.getState();
			Set<LTS.Transition> out = original.getTransitions(tmp);
			HashSet<LTS.Transition> markovian = new HashSet<>();
			for (LTS.Transition t : out) {
				if (t.label.charAt(0) == 'r')
					markovian.add(t);
				if (t.label.charAt(0) == 'p')
					markovian.add(t);
				if (t.label.charAt(0) == 't')
					markovian.add(t);
			}
			if (finalTransitions == null) {
				finalTransitions = markovian;
				ret = tmp;
			} else if (!finalTransitions.equals(markovian)) {
				System.err.println("From state: " + Arrays.toString(from) + " @ " + time);
				System.err.println("Can reach (" + Arrays.toString(ret) + "):");
				System.err.println(markovian);
				System.err.println("and (" + Arrays.toString(tmp) + "):");
				System.err.println(finalTransitions);
				return null;
			}
		}
		return ret;
	}

	public static String addLabels(String l1, String l2)
	{
		if (l1 == null)
			return l2;
		if (l2 == null)
			return l1;
		if (l1.charAt(0) == 't' && l2.charAt(0) == 't')
			return l1;
		if (l1.charAt(0) == 'r' && l2.charAt(0) == 'r') {
			BigDecimal r1 = new BigDecimal(l1.substring(1));
			BigDecimal r2 = new BigDecimal(l2.substring(1));
			BigDecimal rret = r1.add(r2);
			return "r" + rret.stripTrailingZeros().toString();
		} else if (l1.charAt(0) == 'p' && l2.charAt(0) == 'p') {
			BigDecimal r1 = new BigDecimal(l1.substring(1));
			BigDecimal r2 = new BigDecimal(l2.substring(1));
			BigDecimal rret = r1.add(r2);
			if (rret.doubleValue() > 1) {
				try {
					throw new UnsupportedOperationException("Probability greater than one");
				} catch (RuntimeException e) {
					e.printStackTrace();
					throw e;
				}
			}
			return "p" + rret.stripTrailingZeros().toString();
		}
		throw new UnsupportedOperationException("Tried to merge non-mergeable transitions: '" + l1 + "' and '" + l2 + "'");
	}


	public TreeSet<LTS.Transition> getTransitions(int[] from)
		throws NondeterminismException
	{
		TreeSet<LTS.Transition> ret = new TreeSet<LTS.Transition>();
		Set<LTS.Transition> outgoing = original.getTransitions(from);

		for (LTS.Transition t : outgoing) {
			BigDecimal time;
			switch (t.label.charAt(0)) {
			case 'r':
			case 'p':
				time = new BigDecimal(-1);
				break;
			case 't':
				time = new BigDecimal(t.label.substring(1));
				break;
			default:
				continue;
			}
			int[] endState = markovTerminal(t.target, time);
			if (endState != null) {
				LTS.Transition nt = new LTS.Transition(t.label, endState, null, null);
				while (ret.contains(nt)) {
					/* Two transitions to the same
					 * state, i.e., should double
					 * the rate.
					 */
					String label = addLabels(nt.label, nt.label);
					ret.remove(nt);
					nt = new LTS.Transition(label, endState, null, null);
				}
				ret.add(nt);
			} else
				throw new NondeterminismException("Model has not-obviously-spurious nondeterminism.");
		}
		return ret;
	}

	public int stateSize()
	{
		return initialState.length;
	}
}
