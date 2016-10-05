package crawler;

/**
 * Similar to DEFINE file in C++, extract them out for easier modification.
 * To enable task forward, add entry at both CRAWLER_TASK_ASSIGNMENT.java and CRAWLERTASK.java then recompile.
 */
public class CRAWLERTASK {
	public static final String rawDataDistCacl = "rawDataDistCacl";
	public static final String rawDataICL = "rawDataICL";
	public static final String DM_STISS = "DM_STISS";
	public static final String DM_RSG = "DM_RSG";
	public static final String DM_SCCRS = "DM_SCCRS";
	public static final String DM_ACTGDR = "DM_ACTGDR";
	public static final String DM_RSGFSB = "DM_RSGFSB";
	public static final String DM_RERAUP = "DM_RERAUP";

	public static final String[] arrayForm = {
			rawDataDistCacl,
			rawDataICL,
			DM_STISS,
			DM_RSG,
			DM_SCCRS,
			DM_ACTGDR,
			DM_RSGFSB,
			DM_RERAUP
	};
}