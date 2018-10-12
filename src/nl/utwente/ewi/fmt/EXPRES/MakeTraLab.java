package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class MakeTraLab {
	static TreeMap<String, Integer> stateNums = new TreeMap<>();
	static ArrayList<TreeMap<Integer, String>> transitions = new ArrayList<>();
	static ArrayList<String> markings = new ArrayList<>();
	static TreeSet<Integer> ergodic = new TreeSet<>();
	static int transitionCount = 0;

	public static void main(String[] args) throws IOException
	{
		int[] state;
		int numStates;
		
		MarkableLTS c;
		if (args[0].endsWith(".aut") || args[0].endsWith(".bcg")) {
			Automaton a = new Automaton(args[0],
			                      args[0].substring(args[0].length() - 3));
			c = new MarkedAutomaton(a);
			c.markStatesAfter("FAIL", 1);
			c.markStatesAfter("REPAIR", 0);
			c.markStatesAfter("ONLINE", 0);
		} else if (args[0].endsWith(".exp")) {
			c = new Composition(args[0], "exp");
			c.markStatesAfter("FAIL", 1);
			c.markStatesAfter("REPAIR", 0);
			c.markStatesAfter("ONLINE", 0);
		} else if (args[0].endsWith(".jani")) {
			c = new Composition(args[0], "jani");
		} else {
			throw new IllegalArgumentException("Type of file " + args[0] + " unknown");
		}
		FileOutputStream traFile, labFile;
		PrintWriter traWriter, labWriter;
		MarkovReducedLTS l = new MarkovReducedLTS(c);
		state = l.getInitialState();
		exploreStates(l, state);
		stateNums = null;
		numStates = markings.size();
		System.out.format("%d states before removing duplicates\n", numStates);
		while (removeDuplicateStates()) {
			numStates = markings.size();
			System.out.format("%d states left\n", numStates);
		}

		traFile = new FileOutputStream(args[1] + ".tra");
		traWriter = new PrintWriter(traFile);
		labFile = new FileOutputStream(args[1] + ".lab");
		labWriter = new PrintWriter(labFile);
		traWriter.format("STATES %d\nTRANSITIONS %s\n",
		                 numStates,
		                 transitionCount);
		Map<String, Integer> initialValues = l.getVarValues(state);
		labWriter.println("#DECLARATION");
		for (String v : initialValues.keySet())
			labWriter.println(v);
		labWriter.println("#END");
		for (int i = 0; i < numStates; i++)
			transitions.add(null);
		printStates(traWriter, labWriter);
		labWriter.close();
		traWriter.close();
		ergodic.add(0);
		for (int i = 1; i < numStates; i++) {
			if (ergodic.contains(i))
				continue;
			System.err.format("\rChecking ergodicity for %d", i);
			System.err.flush();
			TreeSet<Integer> ergodics = checkErgodic(i);
			if (ergodics == null)
				System.err.format(" -- CANNOT RETURN TO INITIAL STATE\n");
			else
				ergodic.addAll(ergodics);
		}
	}

	public static Integer exploreStates(LTS l, int[] state)
	{
		String sState = Composition.stateString(state);
		Integer stateNum = stateNums.get(sState);
		if (stateNum != null)
			return stateNum;
		if ((stateNums.size() & 32767) == 0) {
			System.err.format("Processed %d states\n",
					stateNums.size());
		}
		stateNum = stateNums.size();
		stateNums.put(sState, stateNum);
		if (state[state.length - 1] != 0) {
			System.err.format("Marked state: %d\n",
					  stateNums.size() - 1);
		}

		Map<String, Integer> vals = l.getVarValues(state);
		String marking = "";
		for (Map.Entry<String, Integer> val : vals.entrySet()) {
			if (val.getValue() != 0)
				marking += " " + val.getKey();
		}
		if (marking.length() > 0)
			markings.add(marking);
		else
			markings.add(null);

		Set<LTS.Transition> s = l.getTransitions(state);
		TreeMap<Integer, String> ts = new TreeMap<>();
		TreeSet<Integer> lTargets = new TreeSet<>();
		transitions.add(ts);
		transitionCount += s.size();
		for (LTS.Transition t : s) {
			Integer target = exploreStates(l, t.target);
			ts.put(target, t.label);
			lTargets.add(target);
		}
		return stateNum;
	}

	public static void printStates(PrintWriter traWriter,
	                               PrintWriter labWriter)
	{
		int maxState = markings.size();
		for (int i = 0; i < maxState; i++) {
			TreeMap<Integer, String> ts = transitions.get(i);
			for (Integer target : ts.keySet()) {
				String label = ts.get(target);
				if (label.startsWith("rate "))
					label = label.substring(5);
				traWriter.format("%d %d %s\n",
				                 i + 1, target + 1, label);
			}
			String marking = markings.get(i);
			if (marking != null)
				labWriter.format("%d %s\n", i + 1, marking);
		}
	}

	private static String addLabels(String l1, String l2)
	{
		if (!l1.startsWith("rate "))
			throw new UnsupportedOperationException("Tried to merge non-rate transitions.");
		if (!l2.startsWith("rate "))
			throw new UnsupportedOperationException("Tried to merge non-rate transitions.");
		BigDecimal r1 = new BigDecimal(l1.substring(5));
		BigDecimal r2 = new BigDecimal(l2.substring(5));
		BigDecimal rret = r1.add(r2);
		return "rate " + rret.toString();
	}

	private static boolean removeDuplicateStates()
	{
		HashMap<HashSet<Object>, Integer> states = new HashMap<>();
		HashMap<Integer, Integer> dups = new HashMap<>();

		int maxState = markings.size();
		for (int i = 0; i < maxState; i++) {
			HashSet<Object> stateInfo = new HashSet<>();
			TreeMap<Integer, String> ts = transitions.get(i);
			String marking = markings.get(i);
			stateInfo.add(ts);
			stateInfo.add(marking);
			Integer existing = states.get(stateInfo);
			if (existing == null)
				states.put(stateInfo, i);
			else {
				dups.put(i, existing);
				System.err.format("Equal: %d for %d\n", i, existing);
			}
		}
		if (dups.isEmpty())
			return false;

		TreeSet<Integer> removed = new TreeSet<>();
		for (Map.Entry<Integer, Integer> pair : dups.entrySet()) {
			Integer merged = pair.getValue();
			Integer dup = pair.getKey();
			Integer origDup = dup;
			for (int i : removed) {
				if (merged > i)
					merged--;
				if (dup > i)
					dup--;
			}
			removed.add(origDup);
			if (1==1) {
				HashSet<Object> dupInfo = new HashSet<>();
				TreeMap<Integer, String> ts = transitions.get(dup);
				String marking = markings.get(dup);
				dupInfo.add(ts);
				dupInfo.add(marking);

				HashSet<Object> mergedInfo = new HashSet<>();
				ts = transitions.get(merged);
				marking = markings.get(merged);
				mergedInfo.add(ts);
				mergedInfo.add(marking);

				if (!mergedInfo.equals(dupInfo)) {
					//throw new IllegalArgumentException("Not merging " + merged + " and " + dup);
					continue;
				}

			}
			System.err.format("Removing %d for %d\n", dup, merged);
			for (int i = markings.size() - 1; i >= 0; i--) {
				TreeMap<Integer, String> n = new TreeMap<>();
				TreeMap<Integer, String> t = transitions.get(i);
				for (Integer target : t.keySet()) {
					String label = t.get(target);
					if (target.equals(dup))
						target = merged;
					if (target > dup)
						target--;
					if (n.containsKey(target))
						label = addLabels(n.get(target), label);
					n.put(target, label);
				}
				transitions.set(i, n);
			}
			transitions.remove((int)dup);
			markings.remove((int)dup);
		}
		return true;
	}

	public static TreeSet<Integer> checkErgodic(int state)
	{
		ArrayList<Integer> path = new ArrayList<>();
		TreeSet<Integer> inPath = new TreeSet<>();
		int idx = 1;

		path.add(state);
		path.add(-1);
		inPath.add(path.get(0));
		while (idx != 0) {
			//System.err.format("\nPath: %s (%d)", path, idx);
			//System.err.flush();
			Integer last = path.remove(idx--);
			inPath.remove(last);
			if (ergodic.contains(last)) {
				System.err.println(path);
				return inPath;
			}
			int cur = path.get(idx);
			Set<Integer> outs = transitions.get(cur).keySet();
			TreeSet<Integer> outgoing = new TreeSet<>(outs);
			Integer next = last;
			do {
				next = outgoing.higher(next);
			} while (next != null && inPath.contains(next));
			if (next != null) {
				path.add(next);
				idx++;
				inPath.add(next);
				path.add(-1);
				idx++;
			}
		}
		return null;
	}
}
