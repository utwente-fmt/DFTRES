package nl.utwente.ewi.fmt.EXPRES;

public interface MarkableLTS extends LTS
{
	/** Add the given label to the marking.
	 * All states reached by following a transition with this label
	 * will have the mark set to the given value.
	 * @param label The label of the transitions that change the
	 * mark
	 * @param val The value of the mark after the transition
	 */
	public void markStatesAfter(String label, int val);
}
