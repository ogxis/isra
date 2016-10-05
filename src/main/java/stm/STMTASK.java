package stm;

/**
 * Defines all the possible work for STM workers.
 */
public abstract class STMTASK {
	public static final String globalDistUpdate = "globalDistUpdate";
	public static final String crawlerTaskAssign = "crawlerTaskAssign";

	public static final String rawDataFetchFromDevice = "rawDataFetchFromDevice";
	public static final String WMTaskAssign = "WMTaskAssign";

	/*
	 * Global Changes Association. Associate recent data together to form a bigger image of what is happening at the moment.
	 * GCA are differentiated into types which groups data relevant to their type only, then types are grouped into a big Main GCA.
	 */
	public static final String rawDataGCA = "rawDataGCA";
	public static final String rawDataICLGCA = "rawDataICLGCA";
	public static final String expGCA = "expGCA";
	public static final String GCAMain = "GCAMain";

	//Those operations in this list are the currently supported operations.
	public static final String[] arrayForm = {
			globalDistUpdate,
			crawlerTaskAssign,
			rawDataFetchFromDevice,
			WMTaskAssign,
			rawDataGCA,
			rawDataICLGCA,
			expGCA,
			GCAMain
	};
}
