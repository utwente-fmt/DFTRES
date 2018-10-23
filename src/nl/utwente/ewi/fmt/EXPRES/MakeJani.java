package nl.utwente.ewi.fmt.EXPRES;

import java.io.IOException;
import java.io.PrintStream;

public class MakeJani {
	public static void makeJani(LTS l, String filename) throws IOException
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
	}
}
