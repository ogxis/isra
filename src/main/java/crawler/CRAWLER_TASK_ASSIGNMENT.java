package crawler;

import stm.DBCN;

/**
 * Store all the crawler task, add it here, then it will be automatically assign and forward task to relevant crawler by STMServer.
 * To enable task forward, add entry at both CRAWLER_TASK_ASSIGNMENT.java and CRAWLERTASK.java then recompile.
 */
public abstract class CRAWLER_TASK_ASSIGNMENT {
	//TO ADD new task assignment, code them in with exact order, exact hierarchy in all of these 2 array. They must have these data.
	public static final String[] taskList = {
			DBCN.V.jobCenter.crawler.rawDataDistCacl.task.cn,
			DBCN.V.jobCenter.crawler.rawDataICL.task.cn,
			DBCN.V.jobCenter.crawler.STISS.task.cn,
			DBCN.V.jobCenter.crawler.RSG.task.cn,
			DBCN.V.jobCenter.crawler.SCCRS.task.cn,
			DBCN.V.jobCenter.crawler.ACTGDR.task.cn,
			DBCN.V.jobCenter.crawler.RSGFSB.task.cn,
			DBCN.V.jobCenter.crawler.RERAUP.task.cn
	};
	public static final String[] workerList = {
			DBCN.V.jobCenter.crawler.rawDataDistCacl.worker.cn,
			DBCN.V.jobCenter.crawler.rawDataICL.worker.cn,
			DBCN.V.jobCenter.crawler.STISS.worker.cn,
			DBCN.V.jobCenter.crawler.RSG.worker.cn,
			DBCN.V.jobCenter.crawler.SCCRS.worker.cn,
			DBCN.V.jobCenter.crawler.ACTGDR.worker.cn,
			DBCN.V.jobCenter.crawler.RSGFSB.worker.cn,
			DBCN.V.jobCenter.crawler.RERAUP.worker.cn
	};
}
