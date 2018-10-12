package nl.utwente.ewi.fmt.EXPRES;

public class Convert
{
	public static void main(String[] args) throws Exception
	{
		String[] remainder = new String[args.length - 1];
		System.arraycopy(args, 1, remainder, 0, args.length - 1);
		switch (args[0]) {
			case "--jani":
				MakeJani.main(remainder);
				break;
			case "--tralab":
				MakeTraLab.main(remainder);
				break;
			default:
				System.err.println("Unknown conversion: " + args[0]);
		}
	}
}
