package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.Set;

public class MakeJani {
	public static TreeMap<String, Object> getFileVars()
	{
		String vars = System.getenv("FILE_VARS");
		TreeMap<String, Object> ret = new TreeMap<>();
		if (vars == null)
			return ret;
		String[] pairs = vars.split(";|,");
		for (String pair : pairs) {
			String kv[] = pair.split("=");
			Object val;
			if (kv.length != 2)
				throw new IllegalArgumentException("Invalid variable specification: " + pair);
			if ("true".equalsIgnoreCase(kv[1])) {
				val = Boolean.TRUE;
			} else if ("false".equalsIgnoreCase(kv[1])) {
				val = Boolean.FALSE;
			} else {
				try {
					val = Long.parseLong(kv[1]);
				} catch (NumberFormatException e) {
					try {
						val = Double.parseDouble(kv[1]);
					} catch (NumberFormatException e2) {
						val = '"' + kv[1] + '"';
					}
				}
			}
			ret.put(kv[0], val);
		}
		return ret;
	}

	/* JSON is output iff orig != null. */
	public static void makeJani(LTS l, String filename, String orig, String[] cmdline, Set<Property> properties)
			throws IOException
	{
		int[] state;

		if (!(l instanceof Composition)) {
			throw new UnsupportedOperationException("Only compositions are supported for JANI conversion at this time.");
		}
		Composition c = (Composition)l;
		String name = filename;
		int lastSlash = name.lastIndexOf('/');
		if (lastSlash != -1)
			name = name.substring(lastSlash + 1, name.length());
		try (PrintStream out = new PrintStream(filename)) {
			c.printJani(name, out, properties);
		}
		if (orig == null)
			return;
		System.out.println("\t\t{");
		System.out.println("\t\t\t\"file\": \"" + name + "\",");
		lastSlash = orig.lastIndexOf('/');
		if (lastSlash != -1)
			orig = orig.substring(lastSlash + 1, orig.length());
		System.out.println("\t\t\t\"original-file\": \"" + orig + "\",");
		System.out.println("\t\t\t\"conversion\": {");
		System.out.println("\t\t\t\t\"tool\": \"DFTRES\",");
		System.out.println("\t\t\t\t\"version\": \"" + Version.version + "\",");
		System.out.println("\t\t\t\t\"url\": \"https://github.com/utwente-fmt/DFTRES\",");
		System.out.println("\t\t\t\t\"command\": \"java -jar DFTRES.jar " + String.join(" ", Arrays.asList(cmdline)) + "\"");
		System.out.println("\t\t\t},");
		System.out.println("\t\t\t\"open-parameter-values\": [],");
		System.out.print("\t\t\t\"file-parameter-values\": [");
		TreeMap<String, Object> params = getFileVars();
		if (!params.isEmpty()) {
			System.out.println();
			int i = 0;
			for (String vname : params.keySet()) {
				System.out.print("\t\t\t\t{ \"name\": \"" + vname + "\", \"value\": " + params.get(vname) + " }");
				if (++i != params.size())
					System.out.print(",");
				System.out.println();
			}
			System.out.print("\t\t\t");
		}
		System.out.println("]");
		System.out.print("\t\t}");
	}
}
