package nl.ennoruijters.util;

import java.io.InputStream;
import java.io.IOException;

/**
 * InputStream with the ability to read Unicode codepoints (from UTF-8
 * input streams) in addition to just bytes. This class attempts to avoid
 * the deficiencies in java's own InputStreamReader (and similar Reader)
 * classes, namely:
 * 
 * - Reader returns UTF-16 code units, which are utterly useless for anything
 *   except output (and creating Strings, which are braindead for the same
 *   reason).
 * - InputStreamReader advertises that it may read more bytes from the
 *   underlying InputStream than are needed to decode a character, which
 *   means you can't use the underlying InputStream anymore, so you can't
 *   mix UTF-8 and arbitrary binary data.
 */
public class UTF8InputStream extends InputStream
{
public static class InvalidCodepointException extends IOException {}

private final InputStream parent;
private int unget = -1;
private boolean eof = false;

public boolean allowInvalidCodepoints = true;

public UTF8InputStream(InputStream in)
{
	super();
	parent = in;
}

public UTF8InputStream(InputStream in, boolean allowInvalidCodepoints)
{
	this(in);
	this.allowInvalidCodepoints = allowInvalidCodepoints;
}

@Override
public int available() throws IOException
{
	return parent.available();
}

@Override
public void close() throws IOException
{
	parent.close();
}

@Override
public boolean markSupported()
{
	return false;
}

@Override
public int read() throws IOException
{
	if (unget != -1) {
		int ret = unget;
		unget = -1;
		return ret;
	}
	int ret = parent.read();
	if (ret == -1)
		eof = true;
	return ret;
}

@Override
public int read(byte[] b, int off, int len) throws IOException
{
	int ret;
	if (unget != -1 && len >= 1) {
		b[0] = (byte)unget;
		unget = -1;
		ret = parent.read(b, off + 1, len - 1);
		if (ret != len - 1)
			eof = true;
		if (ret == -1)
			return 1;
	}
	ret = parent.read(b, off, len);
	if (ret == -1)
		eof = true;
	return ret;
}

/* Has eof been reached */
public boolean feof()
{
	return eof;
}

/** Read the next code point.
 * @return the next code point, -1 if the end of file is reached before
 * any bytes could be read, or -2 if the sequence of bytes does not
 * represent a valid UTF-8 encoding.
 */
public int readCodePoint() throws IOException
{
	int ret, remaining;
	ret = read();
	if (ret < 0x80)
		return ret;
	
	if ((ret & 0xE0) == 0xC0) {
		ret &= 0x1F;
		remaining = 1;
	} else if ((ret & 0xF0) == 0xE0) {
		ret &= 0x0F;
		remaining = 2;
	} else if ((ret & 0xF8) == 0xF0) {
		ret &= 0x07;
		remaining = 3;
	} else {
		if (allowInvalidCodepoints)
			return -2;
		else
			throw new InvalidCodepointException();
	}

	while (remaining-- > 0) {
		int tmp = read();
		if (tmp < 0 || ((tmp & 0xC0) != 0x80)) {
			if (allowInvalidCodepoints)
				return -2;
			else
				throw new InvalidCodepointException();
		}
		ret = (ret << 6) | tmp;
	}
	return ret;
}

/** Read the next word as a string.
 * Read the next sequence of codepoints up to a whitespace character.
 * If the stream begins with whitespace, the empty string is returned.
 * Any invalid byte sequences are skipped.
 * @return The first word in the stream.
 */
public String readWord() throws IOException
{
	StringBuilder builder = new StringBuilder();
	int codepoint;

	while (!Character.isWhitespace(codepoint = readCodePoint())) {
		if (codepoint == -1)
			return builder.toString();
		if (codepoint == -2)
			continue;
		builder.appendCodePoint(codepoint);
	}
	return builder.toString();
}

/** Read the next line as a string.
 * Read the next sequence of codepoints up to a line separator or paragraph
 * separator.
 * Any invalid byte sequences are skipped. If the line ends with CRLF, both
 * characters are read and discarded.
 * @return The first word in the stream.
 */
public String readLine() throws IOException
{
	StringBuilder builder = new StringBuilder();
	int codepoint = 0;

	while(true) {
		switch(codepoint = readCodePoint()) {
		case '\r':
			int c = read();
			if (c != '\n')
				unget = c;
		case '\n':
		case 0x2028: /* LINE SEPARATOR */
		case 0x2029: /* PARAGRAPH SEPARATOR */
		case -1:
			return builder.toString();
		case -2:
			continue;
		default:
			builder.appendCodePoint(codepoint);
		}
	}
}

public void unget(int c) throws IOException
{
	class AttemptedMultipleUngetException extends IOException {}
	if (this.unget != -1)
		throw new AttemptedMultipleUngetException();
	this.unget = c;
}
}
