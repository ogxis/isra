package gca;

import stm.DBCN;

/**
 * GCA types, used to identify how many type of GCA exist.
 * If in future add in more GCA type (unlikely), add those entry to here as well to allow Crawler operations to find it.
 */
public class GCA {
	public static final String[] GENERAL = {
			DBCN.V.general.GCAMain.cn,
			DBCN.V.general.GCAMain.rawData.cn,
			DBCN.V.general.GCAMain.rawDataICL.cn,
			DBCN.V.general.GCAMain.POFeedbackGCA.cn
	};
}