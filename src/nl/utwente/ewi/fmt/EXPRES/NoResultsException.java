package nl.utwente.ewi.fmt.EXPRES;

public class NoResultsException extends Exception {
    private static final long serialVersionUID = 1;
    public NoResultsException(String msg) { super(msg); }
    public NoResultsException() { super("No simulation results available"); }
}
