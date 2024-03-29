package nl.ennoruijters.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JSONParser {
	private static String unicodeDecode(byte[] buffer)
			throws UnsupportedEncodingException {
		if (buffer.length < 2 || (buffer[0] != 0 && buffer[1] != 0))
			return new String(buffer, "UTF-8");
		if (buffer[0] == 0 && buffer[1] == 0) {
			StringBuilder ret = new StringBuilder(buffer.length / 4);
			ByteBuffer b = ByteBuffer.wrap(buffer);
			b.order(java.nio.ByteOrder.BIG_ENDIAN);
			IntBuffer i = b.asIntBuffer();
			for (int j = i.remaining() - 1; j >= 0; j--)
				ret.appendCodePoint(i.get());
			return ret.toString();
		}
		if (buffer[0] == 0 && buffer[1] != 0)
			return new String(buffer, "UTF-16BE");
		if (buffer.length == 2 || buffer[2] != 0)
			return new String(buffer, "UTF-16LE");

		StringBuilder ret = new StringBuilder(buffer.length / 4);
		ByteBuffer b = ByteBuffer.wrap(buffer);
		b.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		IntBuffer i = b.asIntBuffer();
		for (int j = i.remaining() - 1; j >= 0; j--)
			ret.appendCodePoint(i.get());
		return ret.toString();
	}

	private static boolean isWhiteSpace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r';
	}

	private static TreeMap<String, Object> parseObject(String bigData, int[] pos) {
		TreeMap<String, Object> ret = new TreeMap<String, Object>();
		pos[0]++; /* Skip the opening brace */
		while (isWhiteSpace(bigData.charAt(pos[0])))
			pos[0]++;
		while (true) {
			String key = parseString(bigData, pos);
			if (bigData.charAt(pos[0]++) != ':')
				throw new IllegalArgumentException("Not JSON : " + bigData);
			while (isWhiteSpace(bigData.charAt(pos[0])))
				pos[0]++;
			Object value = parseValue(bigData, pos);
			ret.put(key, value);
			while (isWhiteSpace(bigData.charAt(pos[0])))
				pos[0]++;
			if (bigData.charAt(pos[0]) == '}') {
				pos[0]++;
				return ret;
			} else if (bigData.charAt(pos[0]++) != ',') {
				System.err.println("JSON error: "
						+ bigData.substring(pos[0] - 1));
				throw new IllegalArgumentException("Not JSON: " + bigData);
			}
			while (isWhiteSpace(bigData.charAt(pos[0])))
				pos[0]++;
		}
	}

	private static Object[] parseArray(String bigData, int[] pos) {
		pos[0]++; /* Skip the opening brace */
		while (isWhiteSpace(bigData.charAt(pos[0])))
			pos[0]++;
		if (bigData.charAt(pos[0]) == ']') {
			pos[0]++;
			return new Object[0];
		}
		ArrayList<Object> ret = new ArrayList<Object>();
		while (true) {
			Object value = parseValue(bigData, pos);
			ret.add(value);
			while (isWhiteSpace(bigData.charAt(pos[0])))
				pos[0]++;
			if (bigData.charAt(pos[0]) == ']') {
				pos[0]++;
				return ret.toArray();
			} else if (bigData.charAt(pos[0]) != ',') {
				System.err.println("Error in JSON after :");
				System.err.println(bigData.substring(pos[0]));
				throw new IllegalArgumentException("Not JSON");
			}
			pos[0]++;
			while (isWhiteSpace(bigData.charAt(pos[0])))
				pos[0]++;
		}
	}

	private static String parseString(String bigData, int[] pos) {
		StringBuilder ret = new StringBuilder();
		pos[0]++; /* Skip the opening quotation mark */
		while (bigData.charAt(pos[0]) != '"') {
			int cp = bigData.codePointAt(pos[0]++);
			if (Character.isSupplementaryCodePoint(cp))
				pos[0]++;
			else if (Character.isHighSurrogate((char) cp))
				throw new IllegalArgumentException("Broken UTF-16: " + bigData);
			if (cp == '\\') {
				char escaped = bigData.charAt(pos[0]++);
				switch (escaped) {
				case '\\':
					cp = '\\';
					break;
				case '"':
					cp = '"';
					break;
				case '/':
					cp = '/';
					break;
				case 'b':
					cp = '\b';
					break;
				case 'f':
					cp = '\f';
					break;
				case 'n':
					cp = '\n';
					break;
				case 'r':
					cp = '\r';
					break;
				case 't':
					cp = '\t';
					break;
				case 'u':
					String code = bigData.substring(pos[0], pos[0] += 4);
					if (code.charAt(0) < '0' || code.charAt(0) > 'f')
						throw new IndexOutOfBoundsException();
					try {
						cp = Integer.parseInt(code, 16);
					} catch (NumberFormatException _e) {
						throw new IndexOutOfBoundsException();
					}
					if (Character.isHighSurrogate((char) cp)) {
						int cp2;
						code = bigData.substring(pos[0], pos[0] += 2);
						if (!code.equals("\\u"))
							throw new IndexOutOfBoundsException();
						code = bigData.substring(pos[0], pos[0] += 4);
						if (code.charAt(0) == '-')
							throw new IndexOutOfBoundsException();
						try {
							cp2 = Integer.parseInt(code, 16);
						} catch (NumberFormatException _e) {
							throw new IndexOutOfBoundsException();
						}
						if (!Character.isLowSurrogate((char) cp2))
							throw new IndexOutOfBoundsException();
						cp = Character.toCodePoint((char) cp, (char) cp2);
					}
					break;
				default:
					throw new IndexOutOfBoundsException();
				}
			}
			ret.appendCodePoint(cp);
		}
		pos[0]++; /* Skip trailing quotation mark */
		while (isWhiteSpace(bigData.charAt(pos[0])))
			pos[0]++;
		return ret.toString();
	}

	private static final Pattern intRegex = Pattern
			.compile("-?(0|[123456789]\\d*)");
	private static final Pattern numberRegex = Pattern
			.compile("-?(0|[123456789]\\d*)(\\.\\d+)?([eE][+-]\\d+)?");
	private static Number parseNumber(String bigData, int[] pos) {
		Matcher m = numberRegex.matcher(bigData);
		if (!m.find(pos[0]) || m.start() != pos[0])
			throw new IllegalArgumentException("Not JSON (Number, " + m.find(pos[0]) + "): " + bigData.substring(pos[0]));
		String nr = m.group();
		pos[0] = m.end();

		/* See if it's a simple integer */
		m = intRegex.matcher(nr);
		if (m.matches()) {
			try {
				return Long.valueOf(nr);
			} catch (NumberFormatException _e) {
				try {
					return new BigInteger(nr);
				} catch (NumberFormatException _e2) {
					/* Can't happen */
				}
			}
		} else {
			try {
				BigDecimal v = new BigDecimal(nr).stripTrailingZeros();
				Double d = Double.valueOf(nr);
				BigDecimal dv = new BigDecimal(d);
				if (dv.compareTo(v) == 0)
					return d;
				return v;
			} catch (NumberFormatException _e) {
				/* Can't happen */
			}
		}
		return null;
	}

	private static Object parseValue(String bigData, int[] pos) {
		String data;
		if (bigData.length() - pos[0] > 5)
			data = bigData.substring(pos[0], pos[0] + 5);
		else
			data = bigData.substring(pos[0]);
		if (data.startsWith("false")) {
			pos[0] += 5;
			return Boolean.valueOf(false);
		} else if (data.startsWith("true")) {
			pos[0] += 4;
			return Boolean.valueOf(true);
		} else if (data.startsWith("null")) {
			pos[0] += 4;
			return null;
		} else if (data.startsWith("{")) {
			data = null;
			return parseObject(bigData, pos);
		} else if (data.startsWith("[")) {
			data = null;
			return parseArray(bigData, pos);
		} else if (data.startsWith("\"")) {
			data = null;
			return parseString(bigData, pos);
		} else {
			data = null;
			return parseNumber(bigData, pos);
		}
	}

	public static Object parse(byte[] toParse) throws IllegalArgumentException {
		String data;
		try {
			data = unicodeDecode(toParse);
		} catch (UnsupportedEncodingException _e) {
			data = null;
			/* Forbidden by java spec */
		}
		int[] pos = new int[] { 0 };
		if (data.charAt(0) == 0xFEFF)
			pos[0] = 1; /* Unicode byte-order mark */
		try {
			while (isWhiteSpace(data.charAt(pos[0])))
				pos[0]++;
		} catch (StringIndexOutOfBoundsException _e) {
			throw new IllegalArgumentException("Not JSON: " + data);
		}
		if (data.charAt(pos[0]) != '{' && data.charAt(pos[0]) != '[')
			throw new IllegalArgumentException("Not JSON: " + data);
		try {
			return parseValue(data, pos);
		} catch (IndexOutOfBoundsException _e) {
			throw new IllegalArgumentException("Not JSON: " + data);
		}
	}

	private static String readAll(Reader rd) throws IOException {
		StringBuilder sb = new StringBuilder();
		int cp;
		while ((cp = rd.read()) != -1) {
			sb.append((char) cp);
		}
		return sb.toString();
	}

	public static Object readJsonFromFile(String file) throws IOException {
		byte data[];
		try (FileInputStream inStr = new FileInputStream(file);
			ByteArrayOutputStream str = new ByteArrayOutputStream())
		{
			byte buf[] = new byte[4096];
			int bytesRead;
			while ((bytesRead = inStr.read(buf)) != -1)
				str.write(buf, 0, bytesRead);
			data = str.toByteArray();
		}
		return JSONParser.parse(data);
	}

	public static Object readJsonFromUri(URI uri) throws IOException {
		byte data[];
		try (InputStream inStr = uri.toURL().openStream();
			ByteArrayOutputStream str = new ByteArrayOutputStream())
		{
			byte buf[] = new byte[4096];
			int bytesRead;
			while ((bytesRead = inStr.read(buf)) != -1)
				str.write(buf, 0, bytesRead);
			data = str.toByteArray();
		}
		return JSONParser.parse(data);
	}
}
