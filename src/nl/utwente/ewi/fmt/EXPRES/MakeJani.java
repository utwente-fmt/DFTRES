package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

public class MakeJani {
	/* JSON is output iff orig != null. */
	public static void makeJani(LTS l, String filename, String orig, String[] cmdline)
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
			c.printJani(name, out);
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
		System.out.println("\t\t\t\"file-parameter-values\": []");
		System.out.println("\t\t},");
	}
}
