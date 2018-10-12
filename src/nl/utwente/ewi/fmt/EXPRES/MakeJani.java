package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class MakeJani {
	static TreeMap<String, Integer> stateNums = new TreeMap<>();
	static ArrayList<TreeSet<Integer>> transitions = new ArrayList<>();
	static TreeSet<Integer> ergodic = new TreeSet<>();
	static int transitionCount = 0;

	public static void main(String[] args) throws IOException
	{
		int[] state;
		
		Composition c = null;
		if (args[0].endsWith(".aut") || args[0].endsWith(".bcg")) {
			System.out.println("Only compositions are supported for JANI conversion at this time.");
		} else {
			c = new Composition(args[0], "exp");
		}
		c.markStatesAfter("FAIL", 1);
		c.markStatesAfter("REPAIR", 0);
		c.markStatesAfter("ONLINE", 0);
		c.hideLabel("FAIL");
		c.hideLabel("ONLINE");
		c.hideLabel("REPAIR");
		c.printJani(args[0]);
	}
}
