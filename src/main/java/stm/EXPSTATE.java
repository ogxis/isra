package stm;

/**
 * A Define class to store experience states, which outline their current behavior and inclination toward what tasks.
 */
public class EXPSTATE {
	//Before finalization.
	public static final int FIRSTTIME = -1;
	public static final int NULL = -2;
	//LTM Second Half special operation.
	public static final int LTMSH = -3;

	//After finalization.
	public static final int IDLE = 0;
	public static final int ADD = 1;
	public static final int REDUCE = 2;
	public static final int REPLACE = 3;
}