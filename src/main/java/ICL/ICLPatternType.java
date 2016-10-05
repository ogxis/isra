package ICL;

import java.util.ArrayList;

/**
 * For ICL Pattern feedback use. Feedback the related ICL pattern to the actual ICL function when needed by crawler to recreate the situation.
 * It will be posted to Util to fetch its related patterns, then extract them and fill in these rid data. After that when time arrive, he will
 * be feed into the ICL function to process.
 */
public class ICLPatternType {
	public ArrayList<String> visualRid;
	public ArrayList<String> audioRid;
	public ArrayList<String> movementRid;
	public ArrayList<String> generalRid;

	public ICLPatternType() {
		visualRid = new ArrayList<String>();
		audioRid = new ArrayList<String>();
		movementRid = new ArrayList<String>();
		generalRid = new ArrayList<String>();
	}
}