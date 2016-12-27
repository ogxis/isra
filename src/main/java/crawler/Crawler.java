package crawler;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import crawler.ACTGDR.ACTGDRMultiReturn;
import crawler.RSG.RSGMultiReturn;
import crawler.RawDataDistCacl.DistCaclResult;
import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;
import startup.StartupSoft;
import stm.DBCN;
import stm.STMClient;
import utilities.Util;
import ymlDefine.YmlDefine.TaskDetail;
import ymlDefine.YmlDefine.WorkerConfig;

/**
 * Main worker type of the system.
 * Receive and execute multiple types of work.
 * Can have infinite amount of crawler per instance to share tasks.
 */
public class Crawler implements Runnable {
	private WorkerConfig config;

	private Credential logCredential;

	private static final long noWorkSleepMilli = 10;

	/**
	 * Call this to initialize crawler manager, then call run() will start the service loop (receive commands, auto push tasks to worker nodes)
	 * @param workerConfig Configuration files for this worker, including its scope of work and identity.
	 * @param txGraph The accessor that we will use to communicate with the main DB.
	 */
	public Crawler(WorkerConfig workerConfig) {
		this.config = workerConfig;
	}

	/**
	 * Check whether external management system has set us to halt.
	 * Each of them (Crawler) has their own flags, so they can be turned off independently.
	 * @return Halt or not status.
	 */
	private boolean isHalt() {
		//currently halt index is maintained by startupsoft, it may change in the future.
		return StartupSoft.halt.get(config.haltIndex).get();
	}

	/*
	 * vReload() only needed for vertexes that are used across transaction, if used once and no other preceding operation uses it, no reload
	 * required. If it traverse to get a new copy within transaction, then no reload is required as well.
	 */
	//The main thread of crawler. Will keep on getting tasks to execute.
	//-TODO: For req/res commits, break down or merge them to certain threshold before transaction for performance.
	//-TODO: Be sure whole operation works as a manually tweaked transaction instead of multiple small transaction by implementing
	//your own transaction log to revert back all the changes that occurred at particular time.
	public void startService() {
		Graph txGraph = null;

		//Retry allowance for commit() failure.
		//TODO: Allow specification via config file.
		long dbErrMaxRetryCount = StartupSoft.dbErrMaxRetryCount;
		long dbErrRetrySleepTime = StartupSoft.dbErrRetrySleepTime;

		String identifier = config.uid + "-" + config.storageId;
		logCredential = new Credential(Thread.currentThread().getName(), identifier, config.parentUid, config.preference.toString());
		StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "Crawler online. Thread name: " + Thread.currentThread().getName());

		//Setup all the task, only need one instance so not required to keep recreating it at every go, simply reuse them.
		//All these tasks were originally inlined, separated to new files individually for testing purposes.
		RawDataDistCacl rawDataDistCaclTask = new RawDataDistCacl(dbErrMaxRetryCount, dbErrRetrySleepTime);
		RawDataICL rawDataICLTask = new RawDataICL(dbErrMaxRetryCount, dbErrRetrySleepTime, StartupSoft.logger, logCredential);
		STISS STISSTask = new STISS(dbErrMaxRetryCount, dbErrRetrySleepTime);
		RSG RSGTask = new RSG(dbErrMaxRetryCount, dbErrRetrySleepTime);
		SCCRS SCCRSTask = new SCCRS(dbErrMaxRetryCount, dbErrRetrySleepTime, StartupSoft.logger, logCredential);
		ACTGDR ACTGDRTask = new ACTGDR(dbErrMaxRetryCount, dbErrRetrySleepTime);
		RSGFSB RSGFSBTask = new RSGFSB(dbErrMaxRetryCount, dbErrRetrySleepTime);
		RERAUP RERAUPTask = new RERAUP(dbErrMaxRetryCount, dbErrRetrySleepTime);

		//For error recovery, each operation are obligated to finish even if the isHalt() flag is on, but will stop forwarding the task
		//to the next phrase. If amid task passing encounter isHalt(), finish the passing anyway, next phrase will stop the calculation.
		//This is to ensure data integrity.
		//--NOTE: Each task here manage the begin() and finalize() of data on their own.
		boolean firstTimeGraphShutdownExceptionDone = false;
		while (!isHalt()) {
			try {
				txGraph.shutdown();
			}
			catch (Exception e) {
				if (firstTimeGraphShutdownExceptionDone)
					throw new IllegalStateException("Txgraph shutdown error cannot be thrown more than once (after the first initial expected fail)"
							+ ", you failed to close the graph appropriately this time.");
				firstTimeGraphShutdownExceptionDone = true;
			}
			txGraph = StartupSoft.factory.getTx();
			//Set the logger in order to use shorthand version of finalizeTask().
			txGraph.loggerSet(StartupSoft.logger, logCredential);

			//Get the next task vertex.
			Vertex taskVertex = STMClient.getNextCrawlerTask(config.storageId, txGraph);
			//taskVertex can be null if the task class doesn't exist (error or during halt where it drop the class) OR the queue is empty.
			//So we will continue to keep checking the state to see whether it is time to halt and also wait for potential new task input.
			if (taskVertex == null) {
				Util.sleep(noWorkSleepMilli);
				continue;
			}

			Vertex taskDetailVertex = null;
			try {
				taskDetailVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);
			}
			catch (IndexOutOfBoundsException e) {
				StartupSoft.logger.log(logCredential, LVL.ERROR, CLA.INTERNAL,
						"Task Detail Vertex doesn't exist, best probability is bad commit. Will remove this task."
						+ " If this message is reccurring real often, something is wrong.");

				//Task vertex is useless after we completed the task. Remove it manually as we will never reach the end of this task,
				//Where it will do this cleanup for us.
				txGraph.begin();
				taskVertex.remove();
				txGraph.commit();
				continue;
			}

			TaskDetail taskDetail = Util.kryoDeserialize( (byte[]) taskDetailVertex.getProperty(LP.data), TaskDetail.class);

			//The actual general vertex containing the data to be computed. Task detail vertex contain the edge to the actual general vertex, while task
			//vertex only contain an edge to the taskDetailVertex, and itself contain no data.
			ArrayList<Vertex> generalVertexList = Util.traverse(taskDetailVertex, Direction.OUT, DBCN.E.source);
			Vertex generalVertex = generalVertexList.get(0);

			if (generalVertexList.size() > 1)
				throw new IllegalStateException("General vertex for crawler task cannot be more than 1. " + taskDetailVertex);

			double globalDist = STMClient.getGlobalDist(txGraph);
			double globalVariance = STMClient.getGlobalVariance(txGraph);

			/*
			 * Begin of sequential data process. All of them had been encapsulated into their own particular class container, in order
			 * to promote modularization and reuse as now it can be stopped at particular moment, so user can plug in their own
			 * redirection logic.
			 * All the calls here are STRICTLY SEQUENTIAL, never change their order as they are dependent on each other.
			 */

			if (taskDetail.jobType.equals(CRAWLERTASK.rawDataDistCacl)) {
				DistCaclResult distCaclResult = rawDataDistCaclTask.distCaclTxL(generalVertex, txGraph);
				rawDataDistCaclTask.forwardToICLTxF(generalVertex, distCaclResult, txGraph);
				StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "ScanDist success: value is:" + distCaclResult.distValue);
			}

			else if (taskDetail.jobType.equals(CRAWLERTASK.rawDataICL)) {
				rawDataICLTask.execute(generalVertex, txGraph, globalVariance, globalDist);

				/*
				 * The rationale of the process flow: rawDataFetch -> rawDataICL -> GCA.    Is to serialize the flow to avoid deadlock.
				 * Do NOT optimize it to rawDatFetch -> rawDataICL
				 * 									 -> GCA          (Separate into 2 operation and run concurrently)
				 * as it will cause concurrent modification error. As all of them uses the raw data general vertex once concurrently.
				 */
			}

			/*
			 * Summary of the following operations:
			 * Demand given by WorkingMemory's new attention point decider.
			 * Go through STISS to setup initial solution tree structure.
			 * RSG and SCCRS to recursively fill in the tree with solution, ACTGDR to select which branch to focus and expand.
			 * RSGFSB to deal with error when certain selected branch is not achievable or after forwarded to WM,
			 * its prediction against check (PaRc) failed.
			 * RERAUP to send the solution tree for verification and execution at WM.
			 *
			 * Then the process keeps repeat.
			 */

			/*
			 * Note: Solution tree is not reuse able. If you wish to recreate how you had made that decision earlier, you will have to refetch all those
			 * resources you had used during the generation of that tree, which is stored within the tree itself. Read-only.
			 *
			 * Dynamic action reuse. Action deduction by how you treated it earlier. Ability to simulate things even when it had never existed before
			 * using deduction model, where you deduce things by similarity, and extract its action against current new.
			 * But due to time constrain, we will not implement it in minimalist model.
			 * Minimalist is a basic proof of concept only.
			 */

			else if (taskDetail.jobType.equals(CRAWLERTASK.DM_STISS)) {
				STISSTask.execute(generalVertex, taskDetailVertex, txGraph);

				StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "Crawler STISS success.");
			}

			else if (taskDetail.jobType.equals(CRAWLERTASK.DM_RSG)) {
				RSGMultiReturn taskMultiResult = RSGTask.execute(generalVertex, txGraph);
				RSGTask.deduceAndForwardToNextTaskTxE(taskMultiResult, generalVertex, taskDetailVertex, txGraph);
				StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "RSG Success.");
			}

			else if (taskDetail.jobType.equals(CRAWLERTASK.DM_SCCRS)) {
				SCCRSTask.execute(generalVertex, taskDetailVertex, txGraph);
				StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "SCCRS Success.");
			}

			else if (taskDetail.jobType.equals(CRAWLERTASK.DM_ACTGDR)) {
				ACTGDRMultiReturn taskMultiResult = ACTGDRTask.execute(generalVertex, globalDist, txGraph);
				ACTGDRTask.deduceAndForwardToNextTaskTxE(taskMultiResult, taskDetailVertex, txGraph);
				StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "ACTGDR Success.");
			}

			else if (taskDetail.jobType.equals(CRAWLERTASK.DM_RSGFSB)) {
				RSGFSBTask.execute(generalVertex, taskDetailVertex, txGraph);
				StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "RSGFSB Success.");
			}

			else if (taskDetail.jobType.equals(CRAWLERTASK.DM_RERAUP)) {
				RERAUPTask.execute(generalVertex, taskDetailVertex, txGraph);
				StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "RERAUP Success.");
				//End of crawler, will not invoke any other task. Completion of cyclic loop WM->crawler->WM->crawler->WM->.......
			}

			//TODO: 1 more scheduled appendable task: consequences checking after a branch is completely formed, if pass then execute,
			//else move to other branch, consequences will be checking the solution's future. Execute this first before RERAUP.
//			else if (taskDetail.jobType.equals(CRAWLERTASK.DM_ConsequencesCheck)) {
//
//			}

			//Remove the task and its detail vertex as to mark it as done.
			//Commit retry model.
			boolean txError = true;
			int txRetried = 0;
			while (txError) {
				if (txRetried > dbErrMaxRetryCount) {
					throw new IllegalStateException("Failed to complete transaction after number of retry:"
							+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
				}
				else if (txError) {
					if (txRetried != 0) {
						Util.sleep(dbErrRetrySleepTime);
					}
					txRetried++;
				}
				txGraph.begin();

				taskVertex = Util.vReload(taskVertex, txGraph);
				taskDetailVertex = Util.vReload(taskDetailVertex, txGraph);

				taskVertex.remove();
				taskDetailVertex.remove();
				txError = txGraph.finalizeTask(true);
			}
		}	//End of while loop

		StartupSoft.haltAccepted.set(config.haltIndex, new AtomicBoolean(true));
		//NOTE: StartupSoft's manager thread should unregister this crawler node, else next time you cannot log it as it will show you
		//are still online. Already implemented.
	}

	@Override
	public void run() {
		//Log all error before dying.
		try {
			startService();
		}
		catch(Error | Exception e) {
			StartupSoft.logger.log(logCredential, LVL.FATAL, CLA.EXCEPTION, "", e);
		}
	}
}