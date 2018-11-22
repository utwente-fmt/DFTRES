package nl.utwente.ewi.fmt.EXPRES.expression;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class ConstantExpression extends Expression
{
	public final Number value;

	public ConstantExpression(Number val) {
		value = val;
	}

	public Number evaluate(Map<String, ? extends Number> valuation) {
		return value;
	}

	public void writeJani(PrintStream out, int indent) {
		out.print(value);
	}
}
