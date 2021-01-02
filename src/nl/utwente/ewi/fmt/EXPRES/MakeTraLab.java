package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

import algorithms.Simulator;

public class MakeTraLab {
	private final static Comparator<BigDecimal> bdComparator
		= new Comparator<BigDecimal>() {
			public int compare(BigDecimal d1, BigDecimal d2) {
				return d1.compareTo(d2);
			}
		};
	private final ArrayList<Map<Integer, Set<String>>> transitions = new ArrayList<>();
	private final TreeMap<BigDecimal, Set<BigDecimal>> transitionTimes
			= new TreeMap<>(bdComparator);
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

	private class StateToExplore {
		public final int[] state;
		public final String label;
		public Set<LTS.Transition> transitions;
		public String stateString;
		public String marking;
		public NondeterminismException explorationError;

		StateToExplore(int[] s, String l) {
			state = s;
			label = l;
		}
	}

	private class Explorer extends Thread {
		public final LinkedBlockingDeque<StateToExplore> toExplore;

		public Explorer(LinkedBlockingDeque<StateToExplore> toExplore)
		{
			this.toExplore = toExplore;
		}

		public void run () {
			StateToExplore s;
			while (true) try {
				s = toExplore.takeLast();
				break;
			} catch (InterruptedException e) {
			}
			while (s.state != null) {
				int[] state = s.state;
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
				try {
					Set<LTS.Transition> t = l.getTransitions(state);
					for (LTS.Transition tr : t) {
						Number g = tr.guard.evaluate(Map.of());
						if (!(g instanceof Integer))
							throw new UnsupportedOperationException("Model has remaining non-true guards.");
						if ((int)g != 1)
							throw new UnsupportedOperationException("Model has remaining non-true guards.");
						/* Don't bother checking for
						 * assignments: Even if they
						 * exist, the cannot have any
						 * effect (since no guard can
						 * depend on them */
					}
					synchronized(s) {
						s.transitions = t;
						s.stateString = sState;
						s.marking = marking;
						s.notifyAll();
					}
				} catch (NondeterminismException e) {
					synchronized(s) {
						s.explorationError = e;
						s.notifyAll();
					}
				}
				while (true) try {
					s = toExplore.takeLast();
					break;
				} catch (InterruptedException e) {
				}
			}
			while (true) {
				try {
					toExplore.putLast(s);
					break;
				} catch (InterruptedException e) {
				}
			}
		}
	}

	private void setTimes() {
		TreeSet<BigDecimal> tTimes = new TreeSet<>(bdComparator);
		for (Map<Integer, Set<String>> ts : transitions) {
			for (Set<String> ls : ts.values()) {
				for (String l : ls) {
					if (l.charAt(0) == 't') {
						l = l.substring(1);
						BigDecimal time;
						time = new BigDecimal(l);
						tTimes.add(time);
					}
				}
			}
		}
		if (tTimes.isEmpty())
			return;
		BigDecimal times[] = tTimes.toArray(new BigDecimal[0]);
		long n[] = new long[times.length];
		BigDecimal time = BigDecimal.ZERO;
		boolean done = false;
		while (!done) {
			Set<BigDecimal> thisTime = new TreeSet<>(bdComparator);
			done = true;
			time = times[0].multiply(new BigDecimal(n[0] + 1));
			thisTime.add(times[0]);
			for (int i = times.length - 1; i > 0; i--) {
				BigDecimal t;
				t = times[i].multiply(new BigDecimal(n[i] + 1));
				int c = t.compareTo(time);
				if (c < 0) { /* t < time */
					done = false;
					thisTime = new TreeSet<>(bdComparator);
					time = t;
					thisTime.add(times[i]);
				} else if (t == time) {
					thisTime.add(times[i]);
				} else {
					done = false;
				}
			}
			System.err.format("Found time: %s\n", time);
			for (int i = 0; i < times.length; i++) {
				BigDecimal t = times[i];
				t = t.multiply(new BigDecimal(n[i] + 1));
				if (t.compareTo(time) == 0)
					n[i]++;
			}
			transitionTimes.put(time, thisTime);
		}
	}

	public void convert(String out) throws IOException, NondeterminismException
	{
		int numStates;
		long genTime = System.nanoTime(), redTime;
		FileOutputStream traFile, labFile;
		PrintWriter traWriter, labWriter;
		exploreStates();
		genTime = System.nanoTime() - genTime;
		System.err.format("Computing full state space took %f seconds\n", genTime / 1.0e9);
		redTime = System.nanoTime();
		numStates = markings.size();
		System.err.format("%d states before bisimulation reduction\n", numStates);
		bisimulationReduction();
		redTime = System.nanoTime() - redTime;
		System.err.format("Reducing state space took %f seconds\n", redTime / 1.0e9);
		System.err.format("Total time: %f seconds\n", (genTime + redTime) / 1.0e9);
		setTimes();
		numStates = markings.size();
		System.err.format("%d states left after removing duplicates\n", numStates);

		traFile = new FileOutputStream(out + ".tra");
		traWriter = new PrintWriter(traFile);
		labFile = new FileOutputStream(out + ".lab");
		labWriter = new PrintWriter(labFile);
		if (!transitionTimes.isEmpty()) {
			String modeLine = "MODES";
			String timesLine = "TIMES";
			BigDecimal prevTime = BigDecimal.ZERO;
			for (BigDecimal t : transitionTimes.keySet()) {
				modeLine += " ctmc dtmc";
				timesLine += " " + t.subtract(prevTime) + " 1";
				prevTime = t;
			}
			traWriter.format("%s\n%s\n", modeLine, timesLine);
			traWriter.format("STATES %d\n", numStates);
		} else {
			traWriter.format("STATES %d\nTRANSITIONS %s\n",
					numStates,
					transitionCount);
		}
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
		//checkErgodic();
	}

	private void exploreStates() throws NondeterminismException
	{
		TreeMap<String, Integer> stateNums = new TreeMap<>();
		LinkedBlockingDeque<StateToExplore> toExplore;
		toExplore = new LinkedBlockingDeque<>();
		Explorer[] threads = new Explorer[Simulator.coresToUse];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Explorer(toExplore);
			threads[i].start();
		}
		int state[] = l.getInitialState();
		StateToExplore init = new StateToExplore(state, null);
		while (true) {
			try {
				threads[0].toExplore.putLast(init);
				break;
			} catch (InterruptedException e) {
			}
		}
		try {
			exploreStates(stateNums, init, threads[0]);
		} finally {
			StateToExplore term = new StateToExplore(null, null);
			while (true) {
				try {
					threads[0].toExplore.putLast(term);
					break;
				} catch (InterruptedException e) {
				}
			}
		}
	}

	private Integer exploreStates(Map<String, Integer> stateNums,
	                              StateToExplore state, Explorer explorer)
		throws NondeterminismException

	{
		synchronized(state) {
			while (state.transitions == null
			       && state.explorationError == null)
			{
				try {
					state.wait();
				} catch (InterruptedException e) {
				}
			}
		}

		if (state.explorationError != null)
			throw state.explorationError;
		String sState = state.stateString;
		Integer stateNum = stateNums.get(sState);
		if (stateNum != null)
			return stateNum;
		if (stateNums.size() > 0 && (stateNums.size() & 32767) == 0) {
			System.err.format("Processed %d states\n",
					stateNums.size());
		}
		stateNum = stateNums.size();
		stateNums.put(sState, stateNum);
		markings.add(state.marking);

		StateToExplore next[];
		next = new StateToExplore[state.transitions.size()];
		int i = 0;
		for (LTS.Transition t : state.transitions) {
			next[i] = new StateToExplore(t.target, t.label);
			while (true)  {
				try {
					explorer.toExplore.put(next[i]);
					break;
				} catch (InterruptedException e) {
				}
			}
			i++;
		}
		state = null;
		TreeMap<Integer, Set<String>> ts = new TreeMap<>();
		TreeSet<Integer> lTargets = new TreeSet<>();
		transitions.add(ts);
		transitionCount += next.length;
		for (StateToExplore n : next) {
			Integer target = exploreStates(stateNums, n, explorer);
			String label = n.label;
			Set<String> existing = ts.get(target);
			if (existing == null) {
				existing = new TreeSet<>();
				ts.put(target, existing);
			}
			if (label.charAt(0) == 'r') {
				for (String other : existing) {
					if (other.charAt(0) == 'r') {
						existing.remove(other);
						label = MarkovReducedLTS.addLabels(other, label);
						break;
					}
				}
			}
			existing.add(label);
			lTargets.add(target);
		}
		return stateNum;
	}

	private int getFinalTarget(int from, BigDecimal[] times)
		throws NondeterminismException
	{
		Map<Integer, Set<String>> ts = transitions.get(from);
		int ret = -1;
		for (int i = times.length - 1; i >= 0; i--) {
			BigDecimal time = times[i];
			int next = -1;
			if (time == null)
				continue;
			for (Integer target : ts.keySet()) {
				Set<String> labels = ts.get(target);
				for (String l : labels) {
					if (l.charAt(0) != 't')
						continue;
					l = l.substring(1);
					BigDecimal t = new BigDecimal(l);
					if (t.compareTo(time) == 0) {
						if (next != -1)
							throw new NondeterminismException("Nondeterministic choice at time " + t + " in state " + from);
						next = target;
						break;
					}
				}
			}
			if (next == -1)
				next = from;
			times[i] = null;
			next = getFinalTarget(next, times);
			times[i] = time;
			if (ret != -1 && ret != next) {
				throw new NondeterminismException("Nondeterministic choice at time " + time + " starting in state " + from);
			}
			ret = next;
		}
		if (ret == -1)
			ret = from;
		return ret;
	}

	private int[] getTimedTargets(int state) throws NondeterminismException
	{
		int[] ret = new int[transitionTimes.size()];
		int i = 0;
		for (BigDecimal time : transitionTimes.keySet()) {
			Set<BigDecimal> allTimes = transitionTimes.get(time);
			BigDecimal[] times = new BigDecimal[allTimes.size()];
			times = allTimes.toArray(times);
			int target = getFinalTarget(state, times);
			ret[i++] = target;
		}
		return ret;
	}

	private String formatRates(int target, Map<Integer, Set<String>> transitions, int timed[])
		throws NondeterminismException
	{
		String rate = "0";
		Set<String> labels = transitions.get(target);
		if (labels != null) {
			for (String l : labels) {
				if (l.charAt(0) == 'r') {
					rate = l.substring(1);
					break;
				}
			}
		}
		if (timed == null || timed.length == 0)
			return rate;
		String ret = "";
		for (int t : timed) {
			if (t == target)
				ret += rate + " 1 ";
			else
				ret += rate + " 0 ";
		}
		return ret;
	}


	private void printStates(PrintWriter traWriter, PrintWriter labWriter)
		throws NondeterminismException
	{
		int maxState = markings.size();
		for (int i = 0; i < maxState; i++) {
			Map<Integer, Set<String>> ts = transitions.get(i);
			int[] timed = getTimedTargets(i);
			Set<Integer> targets = new TreeSet<Integer>();
			targets.addAll(ts.keySet());
			for (int t : timed)
				targets.add(t);
			for (Integer target : targets) {
				String rates = formatRates(target, ts, timed);
				traWriter.format("%d %d %s\n",
				                 i + 1, target + 1, rates);
			}
			String marking = markings.get(i);
			if (marking != null)
				labWriter.format("%d%s\n", i + 1, marking);
		}
	}

	private void checkErgodic()
	{
		BitSet notErgodic = new BitSet(markings.size());
		notErgodic.set(1, markings.size());
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
		if (!notErgodic.isEmpty()) {
			int s = notErgodic.nextSetBit(0);
			while (s != -1) {
				System.err.format("%d Cannot return to initial state.\n", s);
				s = notErgodic.nextSetBit(s + 1);
			}
		}
	}

	private Set<Integer> splitRate(Set<Integer> part, Set<Integer> splitter)
	{
		if (part == splitter)
			return null;
		Set<Integer> newPart = null;
		BigDecimal rate = null;
		for (Integer s : part) {
			BigDecimal myRate = BigDecimal.ZERO;
			Map<Integer, Set<String>> ts = transitions.get(s);
			for (Integer t : splitter) {
				BigDecimal newRate;
				Set<String> labels = ts.get(t);
				if (labels == null)
					continue;
				for (String label : labels) {
					if (label.charAt(0) != 'r')
						continue;
					label = label.substring(1);
					newRate = new BigDecimal(label);
					myRate = myRate.add(newRate);
				}
			}
			if (rate == null) {
				rate = myRate;
				continue;
			}
			if (rate.compareTo(myRate) != 0) {
				if (newPart == null)
					newPart = new TreeSet<>();
				newPart.add(s);
			}
		}
		if (newPart != null) {
			part.removeAll(newPart);
			if (newPart.size() == 1)
				return Set.of(newPart.iterator().next());
		}
		return newPart;
	}

	private Set<Integer> splitOn(Set<Integer> part, Set<Integer> splitter,
	                             String label)
	{
		Set<Integer> cannotReach = null;
		for (Integer s : part) {
			boolean canReach = false;
			Map<Integer, Set<String>> ts = transitions.get(s);
			for (Integer target : ts.keySet()) {
				for (String l : ts.get(target)) {
					if (!l.equals(label))
						continue;
					if (splitter.contains(target))
						canReach = true;
				}
			}
			if (!canReach) {
				if (cannotReach == null)
					cannotReach = new TreeSet<>();
				cannotReach.add(s);
			}
		}
		if (cannotReach != null)
			part.removeAll(cannotReach);
		return cannotReach;
	}

	private Set<Integer> split(Set<Integer> part, Set<Integer> splitter)
	{
		Set<Integer> ret = splitRate(part, splitter);
		if (ret != null)
			return ret;
		for (Integer s : part) {
			Map<Integer, Set<String>> ts = transitions.get(s);
			for (Integer target : ts.keySet()) {
				for (String label : ts.get(target)) {
					if (label.charAt(0) == 'r')
						continue;
					if (!splitter.contains(target))
						continue;
					ret = splitOn(part, splitter, label);
					if (ret != null)
						return ret;
				}
			}
		}
		return ret;
	}

	private boolean bisimulationReduction() {
		HashSet<Set<Integer>> partitions = new HashSet<>();
		HashMap<String, Set<Integer>> ip = new HashMap<>();
		int i = 0;
		for (String marking : markings) {
			Set<Integer> states = ip.get(marking);
			if (states == null) {
				states = new TreeSet<>();
				ip.put(marking, states);
				partitions.add(states);
			}
			states.add(i++);
		}
		boolean stable = false;
		while (!stable) {
			stable = true;
			Iterator<Set<Integer>> it = partitions.iterator();
			while (it.hasNext()) {
				Set<Integer> splitter = it.next();
				Set<Set<Integer>> newParts = new HashSet<>();
				for (Set<Integer> part : partitions) {
					Set<Integer> newPart;
					newPart = split(part, splitter);
					if (newPart != null)
						newParts.add(newPart);
				}
				partitions.addAll(newParts);
				if (!newParts.isEmpty()) {
					stable = false;
					break;
				}
				if (splitter.size() == 1)
					it.remove();
			}
		}

		HashMap<Integer, Integer> renames = new HashMap<>();
		for (Set<Integer> partition : partitions) {
			Integer first = null;
			if (partition.size() > 1) {
				if (partition.contains(0))
					first = 0;
				for (Integer s : partition) {
					if (first == null)
						first = s;
					else if (first != s)
						renames.put(s, first);
				}
			}
		}
		if (renames.isEmpty())
			return false;
		int moves[] = new int[transitions.size()];
		int j = 0;
		for (i = 0; i < transitions.size(); i++) {
			if (!renames.containsKey(i))
				moves[i] = j++;
			else
				moves[i] = -1;
		}
		int statesLeft = j;
		renames.replaceAll((from, to) -> moves[to]);
		for (i = 0; i < transitions.size(); i++) {
			j = moves[i];
			if (j == -1)
				continue;
			Map<Integer, Set<String>> ts = transitions.get(i);
			boolean needsChange = false;
			for (Integer from : ts.keySet()) {
				if (renames.containsKey(from)) {
					needsChange = true;
					break;
				}
				if (moves[from] != from) {
					needsChange = true;
					break;
				}
			}
			if (!needsChange) {
				transitions.set(j, ts);
				markings.set(j, markings.get(i));
				continue;
			}
			Map<Integer, Set<String>> newTs = new HashMap<>();
			for (Integer from : ts.keySet()) {
				Integer to = renames.get(from);
				if (to == null)
					to = moves[from];
				if (to == j)
					continue;
				Set<String> newLabels = newTs.get(to);
				if (newLabels == null) {
					newLabels = ts.get(from);
					newTs.put(to, newLabels);
					continue;
				}
				Set<String> oldLabels = ts.get(from);
				String rate = null;
				for (String l : newLabels) {
					if (l.charAt(0) == 'r') {
						rate = l;
						break;
					}
				}
				for (String label : oldLabels) {
					if (label.charAt(0) == 'r') {
						label = MarkovReducedLTS.addLabels(rate, label);
						if (rate != null)
							newLabels.remove(rate);
					}
					newLabels.add(label);
				}
			}
			markings.set(j, markings.get(i));
			transitions.set(j, newTs);
		}
		while (transitions.size() > statesLeft) {
			transitions.remove(transitions.size() - 1);
			markings.remove(markings.size() - 1);
		}
		return true;
	}
}
