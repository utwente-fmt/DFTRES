package nl.utwente.ewi.fmt.EXPRES;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import nl.ennoruijters.util.UTF8InputStream;

public class ExpReader {
	private String lastToken = null;
	private boolean repeatToken = false;
	private final UTF8InputStream input;
	private final StringBuilder sb = new StringBuilder();

	/* Read until a "*)" sequence has been read. */
	private void skipMultilineComment() throws IOException
	{
		int prev = 0, c = 0;
		while (!(prev == '*' && c == ')') && !(c == -1)) {
			prev = c;
			c = input.readCodePoint();
		}
		c = input.readCodePoint();
		while (c > -1 && Character.isWhitespace(c)) {
			c = input.readCodePoint();
		}
		if (c > -1)
			input.unget(c);
	}

	/** @return the next non-comment space-separated token,
	 * or null if the end of the file is reached before any token is read.
	 */
	public String nextToken() throws IOException
	{
		if (repeatToken && lastToken != null) {
			String ret = lastToken;
			lastToken = null;
			repeatToken = false;
			return ret;
		}
		sb.setLength(0);
		int prev = ' ';
		int c = ' ';
		boolean qtd = false;
		while (c > -1 && Character.isWhitespace(c)) {
			c = input.readCodePoint();
		}
		qtd = false;
		if (c == -1)
			return null;
		while (c > 0 && !(qtd ? c == '"' : Character.isWhitespace(c))) {
			if (!qtd && c == ',') {
				if (sb.length() == 0) {
					return ",";
				} else {
					input.unget(',');
					c = 'U';
					break;
				}
			}
			if (!qtd && (c == '*' && prev == '(')) {
				skipMultilineComment();
				sb.setLength(sb.length() - 1);
				if (sb.length() > 0) {
					lastToken = sb.toString();
					return lastToken;
				}
			} else if (!qtd && (c == '-' && prev == '-')) {
				input.readLine();
				sb.setLength(sb.length() - 1);
				if (sb.length() > 0) {
					lastToken = sb.toString();
					return lastToken;
				}
			} else {
				prev = c;
				if (sb.length() == 0 && c == '"') {
					qtd = true;
					c = input.readCodePoint();
				}
				sb.appendCodePoint(c);
			}
			c = input.readCodePoint();
		}
		lastToken = sb.toString();
		return lastToken;
	}

	public String[] readCompositionLine() throws IOException
	{
		class InvalidCompositionLine extends IOException {}
		ArrayList<String> ret = new ArrayList<String>();
		boolean done = false;
		while (!done) {
			String label = nextToken();
			String sep = nextToken();
			if (sep.equals("->")) {
				ret.add(label);
				label = nextToken();
				ret.add(label);
				done = true;
			} else if (sep.equals("*") || sep.equals("||")) {
				ret.add(label);
			} else {
				throw new InvalidCompositionLine();
			}
		}
		return ret.toArray(new String[ret.size()]);
	}

	public void repeatToken()
	{
		this.repeatToken = true;
	}

	public void close() throws IOException
	{
		input.close();
		repeatToken = false;
	}

	public ExpReader(InputStream in)
	{
		input = new UTF8InputStream(in, false);
	}
}
