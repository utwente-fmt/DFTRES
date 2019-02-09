package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

import algorithms.Simulator;

public class MakeTraLab {
	private final ArrayList<TreeMap<Integer, String>> transitions = new ArrayList<>();
	private final ArrayList<String> markings = new ArrayList<>();
	private final LTS l;
	private BitSet notErgodic;
	private int transitionCount = 0;

	public MakeTraLab(LTS lts)
	{
		if (lts instanceof MarkovReducedLTS)
			l = (MarkovReducedLTS)lts;
		else
			l = new MarkovReducedLTS(lts);
	}

	public MakeTraLab(LTS lts, boolean unsafe)
	{
		if (lts instanceof MarkovReducedLTS)
			l = (MarkovReducedLTS)lts;
		else if (unsafe && (lts instanceof Composition))
			l = new MarkovianComposition((Composition)lts);
		else
			l = new MarkovReducedLTS(lts);
	}

	private class Explorer extends Thread {
		public final LinkedBlockingDeque<int[]> toExplore;
		public final ConcurrentHashMap<int[], Object[]> explored;
		/* Explored:
		 * [length-1] - state label,
		 * [length-2] - marking,
		 * rest       - Set<LTS.Transition>
		 */

		public Explorer(LinkedBlockingDeque<int[]> toExplore,
		                ConcurrentHashMap<int[], Object[]> explored)
		{
			this.toExplore = toExplore;
			this.explored = explored;
		}

		public void run () {
			int[] state;
			while (true) try {
				state = toExplore.takeLast();
				break;
			} catch (InterruptedException e) {
			}
			while (state.length > 0) {
				if (explored.containsKey(state))
					continue;
				String sState = Composition.stateString(state);
				Map<?, Integer> vals = l.getVarValues(state);
				String marking = "";
				for (Map.Entry<?, Integer> v : vals.entrySet()){
					if (v.getValue() != 0)
						marking += " " + v.getKey();
				}
				if (marking.length() == 0)
					marking = null;
				vals = null;
				Set<LTS.Transition> s = l.getTransitions(state);
				Object[] ret = new Object[s.size() + 2];
				s.toArray(ret);
				s = null;
				ret[ret.length - 1] = sState;
				ret[ret.length - 2] = marking;
				explored.put(state, ret);
				ret = null;
				while (true) try {
					state = toExplore.takeLast();
					break;
				} catch (InterruptedException e) {
				}
			}
			while (true) {
				try {
					toExplore.putLast(state);
					break;
				} catch (InterruptedException e) {
				}
			}
		}
	}

	public void convert(String out) throws IOException
	{
		int numStates;
		
		FileOutputStream traFile, labFile;
		PrintWriter traWriter, labWriter;
		exploreStates();
		numStates = markings.size();
		System.err.format("%d states before removing duplicates\n", numStates);
		while (removeDuplicateStates())
			;

		numStates = markings.size();
		System.err.format("%d states left after removing duplicates\n", numStates);

		traFile = new FileOutputStream(out + ".tra");
		traWriter = new PrintWriter(traFile);
		labFile = new FileOutputStream(out + ".lab");
		labWriter = new PrintWriter(labFile);
		traWriter.format("STATES %d\nTRANSITIONS %s\n",
		                 numStates,
		                 transitionCount);
		Map<String, Integer> initialValues = l.getVarValues(l.getInitialState());
		labWriter.println("#DECLARATION");
		for (String v : initialValues.keySet())
			labWriter.println(v);
		labWriter.println("#END");
		for (int i = 0; i < numStates; i++)
			transitions.add(null);
		printStates(traWriter, labWriter);
		labWriter.close();
		traWriter.close();
		notErgodic = new BitSet(numStates);
		notErgodic.set(1, numStates);
		checkErgodic();
		if (!notErgodic.isEmpty()) {
			int s = notErgodic.nextSetBit(0);
			while (s != -1) {
				System.err.format("%d Cannot return to initial state.\n", s);
				s = notErgodic.nextSetBit(s + 1);
			}
		}
	}

	private void exploreStates()
	{
		TreeMap<String, Integer> stateNums = new TreeMap<>();
		LinkedBlockingDeque<int[]> toExplore;
		ConcurrentHashMap<int[], Object[]> explored;
		toExplore = new LinkedBlockingDeque<>();
		explored = new ConcurrentHashMap<>();
		Explorer[] threads = new Explorer[Simulator.coresToUse];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Explorer(toExplore, explored);
			threads[i].start();
		}
		int state[] = l.getInitialState();
		while (true) {
			try {
				threads[0].toExplore.putLast(state);
				break;
			} catch (InterruptedException e) {
			}
		}
		exploreStates(stateNums, state, threads[0]);
		while (true) {
			try {
				threads[0].toExplore.putLast(new int[0]);
				break;
			} catch (InterruptedException e) {
			}
		}
	}

	private Integer exploreStates(Map<String, Integer> stateNums,
	                              int[] state, Explorer explorer)
	{
		Object[] stateInfo;
		while ((stateInfo = explorer.explored.get(state)) == null)
			Thread.yield();
		Object tmp = stateInfo[stateInfo.length - 1];
		assert(tmp instanceof String);
		String sState = (String)tmp;
		Integer stateNum = stateNums.get(sState);
		if (stateNum != null)
			return stateNum;
		if (stateNums.size() > 0 && (stateNums.size() & 32767) == 0) {
			System.err.format("Processed %d states\n",
					stateNums.size());
		}
		stateNum = stateNums.size();
		stateNums.put(sState, stateNum);

		tmp = stateInfo[stateInfo.length - 2];
		if (tmp != null) {
			assert(tmp instanceof String);
			markings.add((String)tmp);
		} else {
			markings.add(null);
		}

		for (int i = stateInfo.length - 3; i >= 0; i--) {
			tmp = stateInfo[i];
			assert(tmp instanceof LTS.Transition);
			LTS.Transition t = (LTS.Transition)tmp;
			while (true)  {
				try {
					explorer.toExplore.put(t.target);
					break;
				} catch (InterruptedException e) {
				}
			}
		}
		TreeMap<Integer, String> ts = new TreeMap<>();
		TreeSet<Integer> lTargets = new TreeSet<>();
		transitions.add(ts);
		transitionCount += stateInfo.length - 1;
		for (int i = stateInfo.length - 3; i >= 0; i--) {
			tmp = stateInfo[i];
			assert(tmp instanceof LTS.Transition);
			LTS.Transition t = (LTS.Transition)tmp;
			Integer target = exploreStates(stateNums, t.target, explorer);
			ts.put(target, t.label);
			lTargets.add(target);
		}
		return stateNum;
	}

	private void printStates(PrintWriter traWriter,
	                               PrintWriter labWriter)
	{
		int maxState = markings.size();
		for (int i = 0; i < maxState; i++) {
			TreeMap<Integer, String> ts = transitions.get(i);
			for (Integer target : ts.keySet()) {
				String label = ts.get(target);
				if (label.charAt(0) == 'r')
					label = label.substring(1);
				traWriter.format("%d %d %s\n",
				                 i + 1, target + 1, label);
			}
			String marking = markings.get(i);
			if (marking != null)
				labWriter.format("%d%s\n", i + 1, marking);
		}
	}

	private static String addLabels(String l1, String l2)
	{
		if (l1 == null)
			return l2;
		if (l2 == null)
			return l1;
		if (l1.charAt(0) != 'r')
			throw new UnsupportedOperationException("Tried to merge non-rate transitions.");
		if (l2.charAt(0) != 'r')
			throw new UnsupportedOperationException("Tried to merge non-rate transitions.");
		BigDecimal r1 = new BigDecimal(l1.substring(1));
		BigDecimal r2 = new BigDecimal(l2.substring(1));
		BigDecimal rret = r1.add(r2);
		return "r" + rret.toString();
	}

	private boolean removeDuplicateStates()
	{
		HashMap<Object, Integer> states = new HashMap<>();
		List<Set<Integer>> predecessors = new ArrayList<>(markings.size());
		List<int[]> dups = new ArrayList<>();

		int maxState = markings.size();
		for (int i = 0; i < maxState; i++)
			predecessors.add(new TreeSet<Integer>());
		for (int i = 0; i < maxState; i++) {
			Integer s = i;
			Object stateInfo;
			TreeMap<Integer, String> ts = transitions.get(i);
			for (Integer t : ts.keySet())
				predecessors.get(t).add(s);
			String marking = markings.get(i);
			if (marking == null)
				stateInfo = ts;
			else
				stateInfo = List.of(ts, marking);
			Integer existing = states.get(stateInfo);
			if (existing == null)
				states.put(stateInfo, i);
			else
				dups.add(new int[]{i, existing});
		}
		if (dups.isEmpty())
			return false;
		states = null;

		dups.forEach((data) -> {
			int dup = data[0], merged = data[1];
			int repl = transitions.size() - 1;
			Integer iRepl = repl, iDup = dup, iMerged = merged;

			if (dup >= transitions.size())
				return;
			TreeMap<Integer, String> ts = transitions.get(dup);
			String marking = markings.get(dup);
			Object dupInfo;
			if (marking == null)
				dupInfo = ts;
			else
				dupInfo = List.of(ts, marking);

			ts = transitions.get(merged);
			marking = markings.get(merged);
			Object mergedInfo;
			if (marking == null)
				mergedInfo = ts;
			else
				mergedInfo = List.of(ts, marking);

			if (!mergedInfo.equals(dupInfo)) {
				System.err.println("Different: " + mergedInfo + " and " + dupInfo);
				return;
			}
			Set<Integer> mPreds = predecessors.get(merged);
			for (Integer s : predecessors.get(dup)) {
				TreeMap<Integer, String> t = transitions.get(s);
				String dLabel = t.remove(iDup);
				String mLabel = t.get(iMerged);
				String nLabel = addLabels(mLabel, dLabel);
				if (nLabel == null)
					throw new AssertionError("Dup error " + dLabel + " -- " + mLabel + " @ " + s + " for " + merged + "<-" + dup + "<-" + repl);
				t.put(iMerged, nLabel);
				mPreds.add(s);
			}
			for (Integer s : predecessors.get(repl)) {
				TreeMap<Integer, String> t = transitions.get(s);
				String rLabel = t.remove(iRepl);
				if (rLabel == null)
					continue;
				t.put(iDup, rLabel);
			}
			/* Dup is no longer a predecessor of anything.
			 * We don't need to add a new predecessor, since
			 * by definition 'merged' is already a
			 * predecessor (otherwise we couldn't have
			 * merged them).
			 */
			for (Integer s : transitions.get(dup).keySet()) {
				Set<Integer> preds = predecessors.get((int)s);
				preds.remove(iDup);
			}
			for (Integer s : transitions.get(repl).keySet()) {
				Set<Integer> preds = predecessors.get((int)s);
				preds.remove(iRepl);
				if (dup != repl)
					preds.add(iDup);
			}

			predecessors.set(dup, predecessors.get(repl));
			predecessors.remove(repl);
			transitions.set(dup, transitions.get(repl));
			transitions.remove(repl);
			markings.set(dup, markings.get(repl));
			markings.remove(repl);
		});
		return true;
	}

	private void checkErgodic()
	{
		boolean changed;
		do {
			changed = false;
			int state = notErgodic.nextSetBit(0);
			while (state != -1) {
				for (int tgt : transitions.get(state).keySet())
				{
					if (!notErgodic.get(tgt)) {
						notErgodic.clear(state);
						changed = true;
						break;
					}
				}
				state = notErgodic.nextSetBit(state + 1);
			}
		} while (changed);
	}
}
