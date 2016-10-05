package stm;

import java.util.ArrayList;
import java.util.NoSuchElementException;

import crawler.CRAWLERTASK;
import crawler.Crawler;
import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import startup.StartupSoft;
import utilities.Util;
import wm.WMRequestListener;
import wm.WorkingMemory;
import ymlDefine.YmlDefine.WorkerConfig;

/**
 * STM client for internal crawler usage only. Used for communication with STM servers to exchange jobs, spawn and manage crawler threads.
 * Note: locally generated tasks are not visible in global tasks queue unless pushed outward by STMServer.
 */
public abstract class STMClient {
	/**
	 * Convert task name to worker registration list path. This is to keep worker addition compatible with other worker addtiion's
	 * 'preference'(task type) format.
	 * @param taskName Any CRAWLERTASK element.
	 * @return The worker list path of that work.
	 */
	private static String taskNameToWorkerStorageCrawler(String taskName) {
		if (taskName.equals(CRAWLERTASK.rawDataDistCacl))
			return DBCN.V.jobCenter.crawler.rawDataDistCacl.worker.cn;
		else if (taskName.equals(CRAWLERTASK.rawDataICL))
			return DBCN.V.jobCenter.crawler.rawDataICL.worker.cn;
		else if (taskName.equals(CRAWLERTASK.DM_STISS))
			return DBCN.V.jobCenter.crawler.STISS.worker.cn;
		else if (taskName.equals(CRAWLERTASK.DM_RSG))
			return DBCN.V.jobCenter.crawler.RSG.worker.cn;
		else if (taskName.equals(CRAWLERTASK.DM_SCCRS))
			return DBCN.V.jobCenter.crawler.SCCRS.worker.cn;
		else if (taskName.equals(CRAWLERTASK.DM_ACTGDR))
			return DBCN.V.jobCenter.crawler.ACTGDR.worker.cn;
		else if (taskName.equals(CRAWLERTASK.DM_RSGFSB))
			return DBCN.V.jobCenter.crawler.RSGFSB.worker.cn;
		else if (taskName.equals(CRAWLERTASK.DM_RERAUP))
			return DBCN.V.jobCenter.crawler.RERAUP.worker.cn;
		else
			throw new IllegalArgumentException("Unknown or unsuported crawler task type:" + taskName);
	}

	/*
	 * For worker storage registration, the rationale of leaving it out to the main node (StartupSoft) is because only like so, then he can know
	 * your storage id, else he cannot know, in case of crash, usually sub worker crash first, the main worker is real stable and almost never crash.
	 * Therefore he should be responsible to unregister you, and to do so, he has to have the storageId.
	 */
	/**
	 * Each crawler upon starting his service must register at STM, so STM will create a class of his name and store the assigned job there.
	 * And STM will also tailor his job assignment according to your preference.
	 * @param workerConfig A configuration class that contains all the data required to create all relevant entries.
	 * @return A running thread based on STM worker services.
	 */
	public static Thread crawlerRegister(WorkerConfig workerConfig, Graph txGraph) {
		//TODO: This is not premable when you reach millions thread, where addition and deletion will be frequent.
		//Subscribe to job center.
		for (int i=0; i<workerConfig.preference.size(); i++) {
			String workerRegistrationPath = taskNameToWorkerStorageCrawler(workerConfig.preference.get(i));
			Vertex job = txGraph.addVertex(workerRegistrationPath, workerRegistrationPath);
			job.setProperty(LP.data, workerConfig.storageId);
			job.setProperty(LP.nodeUID, workerConfig.uid);
		}

		//Start a dedicated thread, featuring crawler execution.
		Thread thread = new Thread(new Crawler(workerConfig));
		thread.start();
		return thread;
	}

	/**
	 * Migrate all the task assigned to you and delete that class but save a preference record so when you come back next time, you will get
	 * your preference job back perhaps.
	 * NOTE: MUST wrap it inside TRANSACTION!
	 * @param toBeHaltedWorker A configuration class that contains all the data required to create all relevant entries.
	 */
	public static void crawlerUnregister(WorkerConfig toBeHaltedWorker, Graph txGraph) {
		//Unsubscribe from job center.
		for (int i=0; i<toBeHaltedWorker.preference.size(); i++) {
			String workerRegistrationPath = taskNameToWorkerStorageCrawler(toBeHaltedWorker.preference.get(i));
			ArrayList<Vertex> all = txGraph.getVerticesOfClass(workerRegistrationPath);
			for (Vertex v : all) {
				if (v.getProperty(LP.data).equals(toBeHaltedWorker.storageId)) {
					v.remove();;
					break;
				}
			}
		}
	}

	/**
	 * Register new STM worker and assign him to the job according to his preferences, and start the service loop directly.
	 * @param A configuration class that contains all the data required to create all relevant entries.
	 * @return A running thread based on STM worker services.
	 */
	public static Thread STMWorkerRegister(WorkerConfig workerConfig, Graph txGraph) {
		//STMWorker doesn't need to subscribe to job center as all of his task is self sufficient thus require no external input.
		//Start a dedicated thread, featuring STMServer execution.
		Thread thread = new Thread(new STMServer(workerConfig));
		thread.start();
		return thread;
	}

	/**
	 * Migrate all the task assigned to you and delete that class but save a preference record so when you come back next time, you will get
	 * your preference job back perhaps.
	 * @param toBeHaltedWorker A configuration class that contains all the data required to create all relevant entries.
	 */
	public static void STMWorkerUnregister(WorkerConfig toBeHaltedWorker, Graph txGraph) {
		//NOTE: STM worker doesn't need to unsubscribe from job center as he never subscribed to it. It uses the absolute task name
		//to work and doesn't require external input.
	}

	/**
	 * Each WMWorker upon starting his service must register at STM, so STM will create a class of his name and store the assigned job there.
	 * And STM will also tailor his job assignment according to your preference.
	 * @param workerConfig A configuration class that contains all the data required to create all relevant entries.
	 * @return A running thread based on STM worker services.
	 */
	public static Thread WMWorkerRegister(WorkerConfig workerConfig, Graph txGraph) {
		//WMWorker doesn't need to subscribe to job center as all of his task is self sufficient thus require no external input.
		//Start a dedicated thread, featuring WM execution.
		Thread thread = new Thread(new WorkingMemory(workerConfig));
		thread.start();
		return thread;
	}

	/**
	 * Migrate all the task assigned to you and delete that class but save a preference record so when you come back next time, you will get
	 * your preference job back perhaps.
	 * NOTE: MUST wrap it inside TRANSACTION!
	 * @param toBeHaltedWorker A configuration class that contains all the data required to create all relevant entries.
	 */
	public static void WMWorkerUnregister(WorkerConfig toBeHaltedWorker, Graph txGraph) {
		//NOTE: WM worker doesn't need to unsubscribe from job center as he never subscribed to it. It uses the absolute task name
		//to work and doesn't require external input.
	}

	/**
	 * Get any next task vertex stored in this worker's task directory.
	 * @param storageId All worker regardless of their origin, all uses the same work storage semantic to get their work.
	 * @return The first actual task vertex in the worker's task directory. Null if it is empty.
	 */
	public static Vertex getNextCrawlerTask(String storageId, Graph txGraph) {
		//Go to the task pool and see if there is any task inside. We only take 1 task at a time, so whoever in the list will be fetched in
		//non specific order unless DB do a good job on returning them in order.
		//Get the actual task vertex created to tell you you are assigned to this job.
		try {
			Vertex task = txGraph.getFirstVertexOfClass(storageId);
			if (task != null) {
				return task;
			}
		}
		//If the vertex class doesn't exist or we have no vertex left.
		catch (IllegalArgumentException e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Get any next task vertex stored in this worker's task directory.
	 * @param storageId All worker regardless of their origin, all uses the same work storage semantic to get their work.
	 * @return The first actual task vertex in the worker's task directory. Null if it is empty.
	 */
	public static Vertex getNextWMTask(String storageId, Graph txGraph) {
		//Temporary shutdown this task passing layer as we intend to make it direct pass through and no scaling is done yet for WM.
		//WM now only has 1 instance. Reinvoke this with the STMServer's WMTaskAssignment code to reenable WM task forwarding.
//		//Go to the task pool and see if there is any task inside. We only take 1 task at a time, so whoever in the list will be fetched in
//		//non specific order unless DB do a good job on returning them in order.
//		//TODO: if possible, try to get only one vertex to save time.
//		//Get the actual task vertex created to tell you you are assigned to this job.
//		try {
//			ArrayList<Vertex> tasks = txGraph.getVerticesOfClass(storageId, false);
//			if (!tasks.isEmpty()) {
//				return tasks.get(0);
//			}
//		}
//		//If the vertex class doesn't exist or we have no vertex left.
//		catch (IllegalArgumentException e) {
//		}
//		return null;

		//Monolith 1 instance only WM code for direct forwarding below.
		//Direct connection from RERAUP to here then forward to WM.
		try {
			Vertex data = txGraph.getFirstVertexOfClass(DBCN.V.WM.timeline.addPrediction.cn);
			if (data != null)
				return data;
		}
		catch (IllegalArgumentException e) {
		}
		return null;
	}

	/**
	 * Fetch from STM for real time update, and returns the result.
	 * @return Latest global distribution value.
	 */
	public static double getGlobalDist(Graph txGraph) {
		double result = 0;
		boolean success = false;
		int retry = 0;
		//This sometimes fail due to consistency issue, maybe too much task switching, only happens if you assign too much work to a node.
		//Let it retry for some time, if it still fails, then die.
		while (!success) {
			try {
				Vertex globalDistVertex = txGraph.getFirstVertexOfClass(DBCN.V.globalDist.out.cn);
				if (globalDistVertex != null) {
					result = globalDistVertex.getProperty(LP.data);
					success = true;
				}
			}
			catch (NoSuchElementException e) {
				if (retry < StartupSoft.dbErrMaxRetryCount) {
					System.out.println("At STMClient getGlobalDist, DB inconsistency error. Cannot find the globalDist output vertex. "
							+ "Remaining retry:" + (StartupSoft.dbErrMaxRetryCount - retry) );
					retry++;
					try {
						Thread.sleep(StartupSoft.dbErrRetrySleepTime);
					} catch (InterruptedException e1) {
						e1.printStackTrace();
					}
				}
				else
					throw new IllegalStateException("getGlobalDist error recurred too many time, treat as exceptional. "
							+ "Original message:" + e);
			}
		}
		return result;
	}

	/**
	 * Add new distribution value to STM, so he can update its globalDist accordingly. className are in case you wanted variable
	 * weight according to each type of data.
	 * @param dist Distribution value calculated by crawler.
	 * @param className The datatype of the distribution value, for statistical purpose only.
	 */
	public static void addDist(double dist, String className, Graph txGraph) {
		//Create a new vertex storing our data in the database specified by the global STM management authority.
		Vertex newDist = txGraph.addVertex(DBCN.V.globalDist.in.cn, DBCN.V.globalDist.in.cn);
		newDist.setProperty(LP.data, dist);
	}

	/**
	 * Global variance calculated by global STM process, to help to modify allowance of all operation in real time. Sample allowance are
	 * how many percentage is treated as pass.
	 * @return Global variance/allowance calculated by STM.
	 */
	public static double getGlobalVariance(Graph txGraph) {
		//TODO: Should return the actual value calculated by STM, and those values should within 0~100f unsigned. Now return dummy value.
		return 10d;
	}

	/**
	 * Get new, refined, composite pattern generated by Global Changes Association and DWDM engine for raw data identification process.
	 * This function simplifies calls required to STM to request for this data.
	 * NOTE: STMClient's GC is responsible to clean this up.
	 * @return Data vertex that stores the actual pattern at its property field.
	 */
	public static ArrayList<Vertex> getLatestRawDataPatternByGCA (String rawDataType, Graph txGraph) {
		//Note: the solution list when you fetch it is a list of task vertexes, which contains 'source' edge to the actual ICLPattern's general vertex,
		//then another 'data' edge to the actual ICLPattern data vertex.
		ArrayList<Vertex> solutionList = null;
		if (rawDataType.equals("Visual"))
			solutionList = txGraph.getVerticesOfClass(DBCN.V.temp.ICLPatternFeedback.rawData.visual.cn);

		else if (rawDataType.equals("Audio"))
			solutionList = txGraph.getVerticesOfClass(DBCN.V.temp.ICLPatternFeedback.rawData.audio.cn);

		ArrayList<Vertex> convertedSolutionList = new ArrayList<Vertex>();
		for (Vertex solution : solutionList) {
			Vertex actualICLPatternGeneralVertex = Util.traverseOnce(solution, Direction.OUT, DBCN.E.source);
			Vertex actualICLPatternDataVertex = Util.traverseOnce(actualICLPatternGeneralVertex, Direction.IN, DBCN.E.data, LTM.ICL);
			convertedSolutionList.add(actualICLPatternDataVertex);
		}
		return convertedSolutionList;
	}

	/**
	 * Send raw data general vertex into the raw data GCA queue to group them together into a grander view.
	 * @param rawDataGeneralVertex
	 */
	public static void rawDataAddToGCAQueue (Vertex rawDataGeneralVertex, Graph txGraph) {
		Vertex rawDataAddToGCAVertex = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.rawData.task.cn
				, DBCN.V.jobCenter.STM.GCAMain.rawData.task.cn);
		rawDataAddToGCAVertex.addEdge(DBCN.E.source, rawDataGeneralVertex);
	}

	/**
	 * After raw data being ICL-ed once, the output will be sent to the GCA waiting queue via this function.
	 * Then all the vertex over there will be grouped together to form a general changes grand picture.
	 * @param patternDataVertex The GENERAL vertex of the actual data vertex that contains the newly identified pattern.
	 */
	public static void rawDataICLAddToGCAQueue (Vertex patternGeneralVertex, Graph txGraph) {
		Vertex registerVertex = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.rawDataICL.task.cn, DBCN.V.jobCenter.STM.GCAMain.rawDataICL.task.cn);
		registerVertex.addEdge(DBCN.E.source, patternGeneralVertex);
	}

	/**
	 * Send the newly created exp to the GCA for grouping purposes.
	 * @param expMainGeneralVertex The expMainGeneral vertex of the exp chain, which is the head of the exp chain.
	 */
	public static void expAddToGCAQueue (Vertex expMainGeneralVertex, Graph txGraph) {
		assert expMainGeneralVertex.getCName().equals(DBCN.V.general.exp.cn) : expMainGeneralVertex;
		Vertex registerVertex = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.exp.task.cn, DBCN.V.jobCenter.STM.GCAMain.exp.task.cn);
		registerVertex.addEdge(DBCN.E.source, expMainGeneralVertex);
	}

	/**
	 * Check whether rid exist in WM, scheduled to exist or already existed.
	 * @param vertexOfInterest The rid of this vertex will be computed against WM.
	 * @return The vertex of the matching variable, or null if not available.
	 */
	public static Vertex checkRidExistInWorkingMemory(Vertex vertexOfInterest, Graph txGraph) {
		String rid = vertexOfInterest.getRid();
		String result = WMRequestListener.addRequest(rid, txGraph);

		if (result == "")
			return null;
		else
			return Util.ridToVertex(result, txGraph);
	}
}
