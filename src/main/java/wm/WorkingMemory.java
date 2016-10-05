package wm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.exception.OSchemaException;

import actionExecutor.ActionScheduler;
import crawler.CRAWLERTASK;
import isradatabase.Direction;
import isradatabase.Edge;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;
import startup.StartupSoft;
import stm.DBCN;
import stm.EXPSTATE;
import stm.LTM;
import stm.STMClient;
import utilities.Util;
import ymlDefine.YmlDefine.TaskDetail;
import ymlDefine.YmlDefine.WorkerConfig;

/**
 * Working Memory system.
 * Jobs are:
 * Accept prediction and schedule it to do Prediction against Reality check (PaRc).
 * Select new attention point.
 * Generate contagious memory and episodic memory.
 */
public class WorkingMemory implements Runnable {
	private WorkerConfig config;

	private Credential logCredential;

	private static final long GCAFeedbackAllowanceMilli = 500;
	private static final long commonAllowanceMilli = 10;
	//Maximum allowed GCA frame in memory, it will weed outs the oldest frame to free up memory congestion.
	//1 frame is 10ms, 1000 = 10 sec.
	public static final int totalGCAFrameAllowedInMemory = 1000;

	/**
	 * Call this to initialize crawler manager, then call run() will start the service loop (receive commands, auto push tasks to worker nodes)
	 * @param workerConfig Unique uid assigned to each node during startup, will never have duplicate.
	 */
	public WorkingMemory(WorkerConfig workerConfig) {
		this.config = workerConfig;
	}

	/**
	 * Check whether external management system has set us to halt.
	 * Note: All worker uses the same halt arraylist, but with different index.
	 * @return Halt or not status.
	 */
	private boolean isHalt() {
		//currently halt index is maintained by startupsoft, it may change in the future.
		return StartupSoft.halt.get(config.haltIndex).get();
	}

	/**
	 * Scheduled stuff will be invoked when their time arrives, processes will be called and executed, ICLPattern will be forwarded to
	 * relevant ICL engine, prediction will be checked against reality and its result be stored in the realResult.
	 * secondaryVertex in order to trace back to the expMainGeneral vertex then to its expResultGeneral, then we copy the realResult into
	 * that result, and mark the whole exp as completed.
	 */
	private class ScheduledElement {
		Vertex secondaryVertex;
		//Long is its start time offset for precise offset maneuver. Only realResult uses treeMap as its keys are unique.
		Multimap<Long, Vertex> processes;
		Multimap<Long, Vertex> ICLPatterns;
		Multimap<Long, Vertex> predictions;
		TreeMap<Integer, Vertex> realResult;

		//If they are done processed (uploaded/checked), then we mark it as done. Uses this in order to avoid skipping some check accidentally.
		ArrayList<Boolean> processDoneIndexList;
		ArrayList<Boolean> ICLPatternDoneIndexList;
		ArrayList<Boolean> predictionDoneIndexList;

		//If particular item had already done being processed (uploaded/checked).
		boolean processDone;
		boolean ICLPatternDone;
		boolean predictionDone;

		long startTime;

		public ScheduledElement() {
			secondaryVertex = null;
			processes = HashMultimap.create();
			ICLPatterns = HashMultimap.create();
			predictions = HashMultimap.create();
			realResult = new TreeMap<Integer, Vertex>();
			processDoneIndexList = new ArrayList<Boolean>();
			ICLPatternDoneIndexList = new ArrayList<Boolean>();
			predictionDoneIndexList = new ArrayList<Boolean>();
			processDone = false;
			ICLPatternDone = false;
			predictionDone = false;
			long startTime = -1;
		}
	}

	/**
	 * Store a list of sessionElement and relevant data to carry out scheduling work.
	 * SessionRid to unique identify this session, startTime is the absolute start time of this prediction session, depth is the current
	 * depth, all the data on the same depth must be completed before moving to the next depth. Data is the actual scheduled item list.
	 * Multiple such session can exist at the same time and work on different things.
	 */
	private class ScheduledElementSession {
		String sessionRid;
		Vertex mainConvergenceHead;
		long startTime;
		int totalDepth;
		//Integer is depth.
		Multimap<Integer, ScheduledElement> data;
		//Store the startTime(when it passed checkIsNow()) of the first entry that started its checking routine (forwarding, PaRc).
		//Entry means scheduled elements that are waiting to be checked in WM's check and execute operation.
		//Used during contagious memory generation, where each depth of data has their own offset to begin with (synchronize).
		ArrayList<Long> firstStartTimePerDepth;

		public ScheduledElementSession() {
			sessionRid = "";
			mainConvergenceHead = null;
			startTime = -1;
			//Total depth is the total amount of depth available, start from index 0.
			totalDepth = -1;
			data = HashMultimap.create();
			firstStartTimePerDepth = new ArrayList<Long>();
		}
	}

	/*
	 * As java doesn't allow pass by reference easily, we create an invisible global variable here to retain recursive injected result, else it
	 * would be tedious to keep on returning upward during deep recursion.
	 * We hide the existence of the global variable so implementation doesn't have to care about it, they just need to know it returns result.
	 * It will be reset by the wrapper function below, not this internal function.
	 * Lock to prevent other concurrent thread to rape the shared private member.
	 */
	private final ReentrantLock recursivePrivateMemberLock = new ReentrantLock();
	private ScheduledElementSession privateRecursiveScheduledElementSession;
	/**
	 * NEVER CALL THIS METHOD explicitly! This is internal method.
	 * NOTE: This function MANAGE ITS OWN TRANSACTION, do NOT embed it inside other transaction unless nested transaction is supported!
	 * Not even from its wrapper class!
	 * This method should never be called outside of recursiveExtractElementToBeScheduledFromSolutionTree.
	 */
	private void recursiveExtractElementToBeScheduledFromSolutionTreeInternal (Vertex givenMainConvergence, int depth, Graph txGraph) {
		ScheduledElement scheduledElement = new ScheduledElement();

		Vertex mainConvergence = givenMainConvergence;

		//Get the selected solution that you had previously calculated, this count correlates with sortedSolutionIndexList, where then the
		//sortedSolutionIndexList's data correlates with the real actual secondary convergence ordering.
		int selectedSolutionIndex = mainConvergence.getProperty(LP.selectedSolutionIndex);
		//-1 means uninitialized, means further node is invalid and not computed at all or simply no more node.
		if (selectedSolutionIndex == -1)
			return;

		ArrayList<Integer> sortedSolutionIndexList = Util.kryoDeserialize( (byte[])mainConvergence.getProperty(LP.sortedSolutionIndexList), ArrayList.class);
		int actualSolutionIndex = sortedSolutionIndexList.get(selectedSolutionIndex);

		ArrayList<Vertex> secondaryConvergenceList = Util.traverse(mainConvergence, Direction.IN, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);
		Vertex selectedSecondary = secondaryConvergenceList.get(actualSolutionIndex);
		scheduledElement.secondaryVertex = selectedSecondary;

		//Not used as we now uses startTimeOffSet, which is a better way than duration.
//		//Get the time requirement for each step.
//		ArrayList<Long> timeRequirementList = mainConvergence.getProperty(LP.timeRequirementList);

		/*
		 * Primary flow and design note:
		 * Secondary convergence vertex will always be last. Never extract data from main convergence as it is requirement derivative
		 * from its 'parent' secondary convergence, which if we do anything to that data, will result in duplicate check and result.
		 * Secondary convergence's data as requirement, traverse to its result counterpart and treat them as result and prediction.
		 * For all secondary convergence extracted data, we will not unpackage it from its encapsulating exp as that had been done by
		 * traversing the preceding convergence tree line ahead which extract each element into separate convergence and seek solution.
		 * But keep in mind that we must extract the startTimeOffSet value from our parent in order to support contagious memory operation.
		 * Do so in order to make sure all entry get into prediction at some part of their life in order to get its deserved 'occurrence' edge.
		 * PaRc pass will grant the both applicant party an 'occurrence' edge between them.
		 * The absolute beginning which is a main convergence will be automatically ignored due to we never going to extract data from it.
		 * It will always be requirement general vertexes and LTM vertexes here, never result, prediction or expMainGeneral as at crawler,
		 * it explicitly chooses requirement general type only, so afterward even in the contagious memory generation phrase, we will
		 * be using requirement general as main requirement or the generated contagious memory instead of any other exp type.
		 */
		Vertex dataGeneralVertex = Util.traverseOnce(selectedSecondary, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);
		String dataType = dataGeneralVertex.getCName();

		//If it is exp type, do not unpack it, simply just treat it as it will happen soon after all of its derivative requirements are done,
		//and those requirements are at the preceding convergences down the tree which will be processed next, thus we should just
		//plug it as prediction, then it will be predicted properly and have proper occurrence edge set up.

		//This will pass PaRc under its normal operation as it already has its 'occurrence' edge ready and is the latest input.
		//By contract design it should only be requirement or result, as both of them can link to any other requirement, result, expMain or LTM.
		if (dataType.equals(DBCN.V.general.exp.requirement.cn) || dataType.equals(DBCN.V.general.exp.result.cn)) {
			Vertex expMainData = null;
			//Fetch their relevant data according to their type.
			if (dataType.equals(DBCN.V.general.exp.requirement.cn)) {
				expMainData = Util.traverseOnce(dataGeneralVertex, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
			}
			else {
				expMainData = Util.traverseOnce(dataGeneralVertex, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
			}
			//This step is crucial as it setup depth for each selected operation, it will be used as one of the way to identify those solution
			//that had been selected, and those who had not been selected before may be deleted as they are redundant and useless.
			Vertex expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
			if ( (int)expMainGeneral.getProperty(LP.depth) == -1 ) {
				txGraph.begin();
				expMainGeneral.setProperty(LP.depth, depth);
				txGraph.finalizeTask();
			}

			//If it is embedded exp type as part of some bigger exp in form of contagious memory, it will have the startTimeOffSet edge inbound.
			//If they are new, or head, then they will not have startTimeOffSet edge as they had never been utilized in contagious memory
			//scheme yet, thus set it as 0l, means run now no wait.
			//Both of them are inverted of their type.
			if (dataType.equals(DBCN.V.general.exp.requirement.cn)) {
				ArrayList<Long> expRequirementStartTimeOffset = Util.traverseGetStartTimeOffset(dataGeneralVertex, Direction.IN, DBCN.E.requirement);
				if (expRequirementStartTimeOffset.isEmpty())
					scheduledElement.predictions.put(0l, dataGeneralVertex);
				else
					scheduledElement.predictions.put(expRequirementStartTimeOffset.get(0), dataGeneralVertex);

				Vertex expResultGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
				ArrayList<Long> expResultStartTimeOffset = Util.traverseGetStartTimeOffset(expResultGeneral, Direction.IN, DBCN.E.result);
				if (expResultStartTimeOffset.isEmpty())
					scheduledElement.predictions.put(0l, expResultGeneral);
				else
					scheduledElement.predictions.put(expResultStartTimeOffset.get(0), expResultGeneral);
			}
			else {
				//If it is embedded exp type as part of some bigger exp in form of contagious memory, it will have the startTimeOffSet edge inbound.
				//If they are new, or head, then they will not have startTimeOffSet edge as they had never been utilized in contagious memory
				//scheme yet, thus we set it as 0l, means run now no wait.
				ArrayList<Long> expResultStartTimeOffset = Util.traverseGetStartTimeOffset(dataGeneralVertex, Direction.IN, DBCN.E.result);
				if (expResultStartTimeOffset.isEmpty())
					scheduledElement.predictions.put(0l, dataGeneralVertex);
				else
					scheduledElement.predictions.put(expResultStartTimeOffset.get(0), dataGeneralVertex);

				Vertex expRequirementGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.requirement, DBCN.V.general.exp.requirement.cn);
				ArrayList<Long> expRequirementStartTimeOffset = Util.traverseGetStartTimeOffset(expRequirementGeneral, Direction.IN, DBCN.E.requirement);
				if (expRequirementStartTimeOffset.isEmpty())
					scheduledElement.predictions.put(0l, expRequirementGeneral);
				else
					scheduledElement.predictions.put(expRequirementStartTimeOffset.get(0), expRequirementGeneral);
			}
		}

		//LTM data simply add to prediction.
		else if (Util.equalAny(dataType, LTM.GENERAL)) {
			ArrayList<Long> startTimeOffset = Util.traverseGetStartTimeOffset(dataGeneralVertex, Direction.IN, DBCN.E.requirement);

			//ICL should be added to both predictions and ICLPatterns, to ICLPatterns means it will be forwarded to ICL engine and generate
			//a match, then it will come back in via GCA into WM, then we pair whether the ICL had succeed or not via the new 'occurrence'
			//edge that the ICL generate to tell us that he succeeded, generated by ICL engine.
			if (dataType.equals(DBCN.V.general.rawDataICL.cn) || dataType.equals(DBCN.V.general.rawDataICL.visual.cn)
					|| dataType.equals(DBCN.V.general.rawDataICL.audio.cn) || dataType.equals(DBCN.V.general.rawDataICL.movement.cn))
				scheduledElement.ICLPatterns.put(startTimeOffset.get(0), dataGeneralVertex);

			//LTM movement will be forwarded to actual device, the device will feedback to us, then check whether the action had completed
			//or not via the feedback value.
			else if (Util.equalAny(dataType, LTM.MOVEMENT))
				scheduledElement.processes.put(startTimeOffset.get(0), dataGeneralVertex);

			//All of them need to be forwarded to prediction, if they are not ICL pattern or device actions, put them to prediction directly.
			scheduledElement.predictions.put(startTimeOffset.get(0), dataGeneralVertex);
		}

		else
			throw new UnsupportedOperationException("Unknown and unsupported type:" + dataGeneralVertex + " at WM within"
					+ "secondary convergence rid:" + selectedSecondary.getRid());

		privateRecursiveScheduledElementSession.data.put(depth, scheduledElement);
		StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
				"ScheduledElementTraceSource: " + scheduledElement.ICLPatterns.size() + " " + scheduledElement.ICLPatterns.keys().size() + " "
				+ new ArrayList(scheduledElement.ICLPatterns.keys()).size() + " "
				+ scheduledElement.processes.size() + " " + scheduledElement.processes.keys().size() + " " +
				new ArrayList(scheduledElement.processes.keys()).size() + " "
				+ scheduledElement.predictions.size() + " " + scheduledElement.predictions.keys().size() + " "
				+ new ArrayList(scheduledElement.predictions.keys()).size());

		//Setup a new instance of mainConvergence for each of them.
		try {
			ArrayList<Vertex> mainConvergenceList = Util.traverse(selectedSecondary, Direction.IN, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);
			for (Vertex nextMainConvergence : mainConvergenceList) {
				if ( !nextMainConvergence.getCName().equals(DBCN.V.general.convergenceMain.cn) )
					throw new IllegalStateException(nextMainConvergence.toString());
				//Continue on to process recursively, depth + 1 as it goes in further.
				recursiveExtractElementToBeScheduledFromSolutionTreeInternal(nextMainConvergence, depth + 1, txGraph);
			}
		}
		catch (OSchemaException e) {
			//Do nothing. If exception happens it means it has no more parent edge, means it has no more exp to continue on to process and dig.
		}

		//When it finished, setup the data appropriately, as recursion is serial, we don't need concurrent guard here.
		//total depth start from index 0, not 1 like how size do.
		//Only the latest and the deepest depth get to set this variable.
		if (depth > privateRecursiveScheduledElementSession.totalDepth) {
			privateRecursiveScheduledElementSession.totalDepth = depth;
			privateRecursiveScheduledElementSession.startTime = System.currentTimeMillis();
		}
	}

	/**
	 * Recursively extract requirement and result then classify them into the format wanted.
	 * Creates and encapsulate the creation of a variable, and the call to internal recursive functions.
	 * NOTE: This function MANAGE ITS OWN TRANSACTION, do NOT embed it inside other transaction unless nested transaction is supported!
	 * Not even from its wrapper class!
	 * @param givenMainConvergence The absolute beginning of the to be processed solution tree.
	 * @return The generated session.
	 */
	private ScheduledElementSession recursiveExtractElementToBeScheduledFromSolutionTree (Vertex givenMainConvergence, Graph txGraph) {
		recursivePrivateMemberLock.lock();
		//reset it every time.
		privateRecursiveScheduledElementSession = new ScheduledElementSession();
		recursiveExtractElementToBeScheduledFromSolutionTreeInternal(givenMainConvergence, 0, txGraph);

		//Allocate initial firstStartTimePerDepth and setup its values at here once only for each round.
		//Total depth index start from 0, must add 1 to make it start from 1 as size start from index 1.
		for (int i=0; i<privateRecursiveScheduledElementSession.totalDepth + 1; i++)
			privateRecursiveScheduledElementSession.firstStartTimePerDepth.add(-1l);

		StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "At WM, recursiveExtractElementToBeScheduledFromSolutionTree success.");
		recursivePrivateMemberLock.unlock();

		//No longer clone data as we think that the reference here will be reset into a new reference everytime. And as we have outside
		//reference, it will not be reset and will be exclusively owned by the caller as it will not be gc-ed due to existing reference
		//by the caller.
		return privateRecursiveScheduledElementSession;
	}

	//RCEG means recursiveContagiousExpGeneration.
	private final ReentrantLock polyValSumLock = new ReentrantLock();
	private ArrayList<Double> RCEGRequirementPolyValSum;
	private ArrayList<Double> RCEGResultPolyValSum;
	private ArrayList<Long> RCEGFirstStartTimePerDepth;
	/**
	 * NEVER CALL THIS METHOD explicitly! This is internal method.
	 * Call recursiveContagiousExpGeneration instead as it will setup all the proper value for us.
	 * Creates an episodic memory (contagious memory) and store it into exp to lengthen exp and create more complex activities.
	 * NOTE: This function MANAGE ITS OWN TRANSACTION, do NOT embed it inside other transaction unless nested transaction is supported!
	 * Not even from its wrapper class!
	 * @param givenMainConvergence The absolute beginning of the finalized solution tree.
	 * @param givenExpRequirementData The final output vertex that will be modified by side effect of this function recursively.
	 * @param givenExpResultData The final output vertex that will be modified by side effect of this function recursively.
	 * @param depth Should be 0 at first, and increment by 1 for each recursive call managed internally.
	 */
	private void recursiveContagiousExpGenerationInternal (
			Vertex givenMainConvergence, Vertex givenExpRequirementData, Vertex givenExpResultData, int depth, Graph txGraph) {
		Vertex mainConvergence = givenMainConvergence;

		//Get the selected solution that you had previously calculated, this count correlates with sortedSolutionIndexList, where then the
		//sortedSolutionIndexList's data correlates with the real actual secondary convergence ordering.
		int selectedSolutionIndex = mainConvergence.getProperty(LP.selectedSolutionIndex);
		ArrayList<Integer> sortedSolutionIndexList = Util.kryoDeserialize( (byte[])mainConvergence.getProperty(LP.sortedSolutionIndexList), ArrayList.class);
		int actualSolutionIndex = sortedSolutionIndexList.get(selectedSolutionIndex);

		ArrayList<Vertex> secondaryConvergenceList = Util.traverse(mainConvergence, Direction.IN, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);
		Vertex selectedSecondary = secondaryConvergenceList.get(actualSolutionIndex);

		Vertex fetchedDataGeneralVertex = Util.traverseOnce(selectedSecondary, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);
		String dataType = fetchedDataGeneralVertex.getCName();

		/*
		 * currentStartTimeOffset for depth 0 (beginning) is 0, means start now.
		 * RCEGFirstStartTimePerDepth contains the first element that passed the checkIsNow(), so it is the marking point of the start time.
		 * Thus RCEGFirstStartTimePerDepth.get(0) is relative to 0l (start now).
		 * For the first depth, RCEGFirstStartTimePerDepth.get(0) - RCEGFirstStartTimePerDepth.get(0) = 0l;
		 * For every next depth, their relative offset will be RCEGFirstStartTimePerDepth.get(depth) - RCEGFirstStartTimePerDepth.get(0),
		 * which basically means the offset between now and start.
		 */
		long currentStartTimeOffset = RCEGFirstStartTimePerDepth.get(depth) - RCEGFirstStartTimePerDepth.get(0);

		//Commit retry model.
		boolean txError = true;
		int txRetried = 0;
		while (txError) {
			if (txRetried > StartupSoft.dbErrMaxRetryCount) {
				throw new IllegalStateException("Failed to complete transaction after number of retry:"
						+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
			}
			else if (txError) {
				if (txRetried != 0) {
					Util.sleep(StartupSoft.dbErrRetrySleepTime);
				}
				txRetried++;
			}
			txGraph.begin();

			fetchedDataGeneralVertex = Util.vReload(fetchedDataGeneralVertex, txGraph);
			givenExpRequirementData = Util.vReload(givenExpRequirementData, txGraph);
			givenExpResultData = Util.vReload(givenExpResultData, txGraph);

			//By contract design it should only be requirement.
			if (dataType.equals(DBCN.V.general.exp.requirement.cn)) {
				Vertex fetchedExpRequirementGeneral = fetchedDataGeneralVertex;
				Vertex fetchedExpMainData = Util.traverseOnce(fetchedExpRequirementGeneral, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
				Vertex fetchedExpResultGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);

				//Add edge to the requirement and result vertexes of the target data (convergence contained data).
				Edge expRequirementDataEdge = givenExpRequirementData.addEdge(DBCN.E.requirement, fetchedExpRequirementGeneral);
				expRequirementDataEdge.setProperty(LP.startTimeOffset, currentStartTimeOffset);

				Edge expResultDataEdge = givenExpResultData.addEdge(DBCN.E.result, fetchedExpResultGeneral);
				expResultDataEdge.setProperty(LP.startTimeOffset, currentStartTimeOffset);

				RCEGRequirementPolyValSum.add( (Double) fetchedExpRequirementGeneral.getProperty(LP.polyVal) );
				RCEGResultPolyValSum.add( (Double) fetchedExpResultGeneral.getProperty(LP.polyVal));
			}

			//Else if they are LTM, including POFeedback, PI (physical input, physical output) and ICL.
			else if (Util.equalAny(dataType, LTM.GENERAL)) {
				//Start time offset for them is the given offset so they can be executed immediately.
				Edge expRequirementDataEdge = givenExpRequirementData.addEdge(DBCN.E.requirement, fetchedDataGeneralVertex);
				Edge expResultDataEdge = givenExpResultData.addEdge(DBCN.E.result, fetchedDataGeneralVertex);

				expRequirementDataEdge.setProperty(LP.startTimeOffset, currentStartTimeOffset);
				expResultDataEdge.setProperty(LP.startTimeOffset, currentStartTimeOffset);

				RCEGRequirementPolyValSum.add( (Double) fetchedDataGeneralVertex.getProperty(LP.polyVal) );
				RCEGResultPolyValSum.add( (Double) fetchedDataGeneralVertex.getProperty(LP.polyVal) );

				//LTM doens't have to calculate precisionRate for now as it will never be used, those data will be stored at its proxy exp vertex.
			}

			else
				throw new UnsupportedOperationException("At WM's exp consolidation phrase, unknown and supported type:" + dataType + "; "
						+ "RID:" + fetchedDataGeneralVertex.getRid() + "; "
						+ " secondary convergence rid:" + selectedSecondary.getRid());
			txError = txGraph.finalizeTask(true);
		}

		//Setup a new instance of mainConvergence for each of them. Their recursion order doesn't matter, as in the end when we make use of it,
		//we will sort it first, so ordering is insignificant.
		try {
			ArrayList<Vertex> mainConvergenceList = Util.traverse(selectedSecondary, Direction.IN, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);
			for (Vertex nextMainConvergence : mainConvergenceList) {
				//Continue on to process recursively.
				recursiveContagiousExpGenerationInternal(nextMainConvergence, givenExpRequirementData, givenExpResultData, depth + 1, txGraph);
			}
		}
		catch (OSchemaException e) {
			//Do nothing. If exception happens it means it has no more parent edge, means it has no more exp to continue on to process and dig.
		}
	}

	/**
	 * Wrapper for recursiveContagiousExpGenerationInternal. Purpose is to create a episodic memory (contagious memory) by chaining up all
	 * the exp present in the given solution tree into a big monolithic exp that gives us a view of continuous exp.
	 * NOTE: This function MANAGE ITS OWN TRANSACTION, do NOT embed it inside other transaction unless nested transaction is supported!
	 * @param mainConvergenceHead The beginning of a solution tree.
	 * @param session Session vertex of the tree.
	 * @param firstStartTimePerDepth For each depth, the first element that met the checkIsNow()'s time chained into an array.
	 * @return The newly generated contagious memory vertex of type expMainGeneral. Usually will be added to GCA by caller.
	 */
	private Vertex recursiveContagiousExpGeneration (Vertex mainConvergenceHead, Vertex session
			, ArrayList<Long> firstStartTimePerDepth, Graph txGraph) {
		polyValSumLock.lock();

		//This exp initial structure setup is guaranteed to succeed as it has no concurrent operation.
		txGraph.begin();
		//Setup new exp for each operation. Exp setup code copied from crawler's DWDM_SCCRS.
		Vertex expMainGeneral = txGraph.addVertex(DBCN.V.general.exp.cn, DBCN.V.general.exp.cn);
		Vertex expMainData = txGraph.addVertex(DBCN.V.LTM.exp.cn, DBCN.V.LTM.exp.cn);
		Vertex expRequirementGeneral = txGraph.addVertex(DBCN.V.general.exp.requirement.cn, DBCN.V.general.exp.requirement.cn);
		Vertex expRequirementData = txGraph.addVertex(DBCN.V.LTM.exp.requirement.cn, DBCN.V.LTM.exp.requirement.cn);
		Vertex expResultGeneral = txGraph.addVertex(DBCN.V.general.exp.result.cn, DBCN.V.general.exp.result.cn);
		Vertex expResultData = txGraph.addVertex(DBCN.V.LTM.exp.result.cn, DBCN.V.LTM.exp.result.cn);
		Vertex expPredictionGeneral = txGraph.addVertex(DBCN.V.general.exp.prediction.cn, DBCN.V.general.exp.prediction.cn);
		Vertex expPredictionData = txGraph.addVertex(DBCN.V.LTM.exp.prediction.cn, DBCN.V.LTM.exp.prediction.cn);
		expMainData.addEdge(DBCN.E.data, expMainGeneral);
		expRequirementGeneral.addEdge(DBCN.E.requirement, expMainData);
		expRequirementData.addEdge(DBCN.E.data, expRequirementGeneral);
		expResultGeneral.addEdge(DBCN.E.result, expMainData);
		expResultData.addEdge(DBCN.E.data, expResultGeneral);
		expPredictionGeneral.addEdge(DBCN.E.prediction, expMainData);
		expPredictionData.addEdge(DBCN.E.data, expPredictionGeneral);
		txGraph.finalizeTask();

		//Commit retry model.
		boolean txError = true;
		int txRetried = 0;
		while (txError) {
			if (txRetried > StartupSoft.dbErrMaxRetryCount) {
				throw new IllegalStateException("Failed to complete transaction after number of retry:"
						+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
			}
			else if (txError) {
				if (txRetried != 0) {
					Util.sleep(StartupSoft.dbErrRetrySleepTime);
					expMainGeneral = Util.vReload(expMainGeneral, txGraph);
				}
				txRetried++;
				session = Util.vReload(session, txGraph);
			}
			txGraph.begin();
			//Setup all the initial values.
			expMainGeneral.setProperty(LP.expState, session.getProperty(LP.expState));
			expMainGeneral.addEdge(DBCN.E.parentSession, session);
			expMainGeneral.setProperty(LP.timeStamp, System.currentTimeMillis());
			expMainGeneral.setProperty(LP.duration, -1);
			expMainGeneral.setProperty(LP.precisionRate, -1d);
			expMainGeneral.setProperty(LP.occurrenceCountPR, 0l);
			expMainGeneral.setProperty(LP.depth, -1);
			txError = txGraph.finalizeTask(true);
		}

		//Reset them.
		RCEGRequirementPolyValSum = new ArrayList<Double>();
		RCEGResultPolyValSum = new ArrayList<Double>();
		//Simply copy it in so we don't have to pass it as parameter to the internal function.
		RCEGFirstStartTimePerDepth = new ArrayList<Long>(firstStartTimePerDepth);

		//Run the function first to fill up all the data. Note that we manage the lock for it. It uses side effects to setup our data.
		recursiveContagiousExpGenerationInternal(mainConvergenceHead, expRequirementData, expResultData, 0, txGraph);

		assert !RCEGRequirementPolyValSum.isEmpty() :"RCEGRequirementPolyValSum cannot be empty, should have at least 1 value.";
		assert !RCEGResultPolyValSum.isEmpty() :"RCEGResultPolyValSum cannot be empty, should have at least 1 value.";

		//Sum all of them up then average it.
		Double requirementPolyVal = new Double(0);
		for (Double d : RCEGRequirementPolyValSum)
			requirementPolyVal += d;
		requirementPolyVal /= RCEGRequirementPolyValSum.size();

		Double resultPolyVal = new Double(0);
		for (Double d : RCEGResultPolyValSum)
			resultPolyVal += d;
		resultPolyVal /= RCEGResultPolyValSum.size();

		if (requirementPolyVal.isNaN() || resultPolyVal.isNaN())
			throw new IllegalStateException("Value is Nan: " + requirementPolyVal + ", " + resultPolyVal);

		txGraph.begin();
		expRequirementGeneral.setProperty(LP.polyVal, requirementPolyVal);
		expResultGeneral.setProperty(LP.polyVal, resultPolyVal);
		expMainGeneral.setProperty(LP.polyVal, (requirementPolyVal + resultPolyVal) / 2d );
		txGraph.finalizeTask();

		polyValSumLock.unlock();

		StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "At WM, recursiveContagiousExpGeneration success.");

		return expMainGeneral;
	}

	/**
	 * Check whether now is the time or not.
	 * @param scheduledTime Given time, will be paired agaist current time.
	 * @param threshold +-threshold allowance.
	 * @return True if it is time OR missed, false if it is not the time yet.
	 */
	private boolean checkIsNow(long scheduledTime, long threshold) {
		long currentTime = System.currentTimeMillis();

		//If the difference between current time and given time is within the threshold, both pre and post threshold can run.
		//Ex: first is scheduled, second is current time, third is threshold.
		//abs(2000 - 1500) <= 600 || 500 <= 600 (pretime); abs(2000 - 2200) <= 600 || 200 <= 600 (posttime);
		//abs(2000-2800) <= 600 || 800 <= 600 (post and exceed threshold)
		if ( Math.abs( (currentTime - scheduledTime) ) <= threshold)
			return true;
		//If missed the event, execute it now.
		if (currentTime - scheduledTime > 0)
			return true;
		else
			return false;
	}

	/**
	 * Check whether the given time exceed threshold, which means late.
	 * Used extensively by prediction against reality check only. Others' check just required to see if is time to run (checkIsNow)
	 * but PaRc requires thresholding check to make sure it can repeat its check until it exceed the given time threshold to allow
	 * other checking function to do their foreground work (forward to ICL, process and return), then the PaRc will use their result
	 * to check against reality.
	 * @param scheduledTime
	 * @param threshold Allowance time++.
	 * @return True if exceed (late), false otherwise.s
	 */
	private boolean checkIsLate(long scheduledTime, long threshold) {
		long currentTime = System.currentTimeMillis();
		//If it has exceeded threshold limit, means we are late.  eg now 10.00 and you scheduled 9.00, late 1 hour (10 - 9 > threshold 30 min)
		if (currentTime - scheduledTime > threshold)
			return true;
		return false;
	}

	/**
	 * Check whether RID's latest form exist in latest reality. Used by WMRequestListener and here only.
	 * Main logic of Prediction against Reality check (PaRc).
	 * @param rid The rid to be checked against.
	 * @return The rid if available, else return empty string.
	 */
	public static String checkRidExistInReality(String rid, Graph txGraph) {
		/*
		 * This is a special case, for LTM analog type (motor, speaker) only.
		 * Due to we never forward the LTM analog type to ICL, their 'occurrence' edge had never been set but at here we expect it.
		 * Thus we will set it here right now by using the given vertex (a analog type),
		 * seek any data that shares the same value with this vertex, if have, make an occurrence edge to it.
		 * The seek order is in reverse (latest first) and take only the first match.
		 *
		 * This will be the simulated ICL as it make no sense to forward them the ICL anyway, as it has no data to pair with
		 * unlike other exp or raw data patterns, where (raw data pattern) are specified by prediction by PaRc and exp are
		 * dynamically regenerated thus they will all pass.
		 *
		 * LTM analog patterns are similar to conventional raw data pattern (eg visual), they got prediction too, but they are not
		 * extracted like how visual does, it is already directly the result, thus it is a hardcore either match or not match,
		 * so a simple comparison == will do the job here, no sense to forward it to ICL, let him wait there, then after a split second
		 * it expires, then the matching fails about all the time, we just reverse seek, if within threshold, then treat a pass and
		 * assign an 'occurrence' edge to it.
		 */
		Vertex givenVertex = Util.ridToVertex(rid, txGraph);
		boolean typeIsRawDataAnalog = false;
		if (Util.equalAny(givenVertex.getCName(), LTM.MOVEMENT)) {
			double givenVertexDataValue = Util.traverseOnce(givenVertex, Direction.IN, DBCN.E.data, LTM.MOVEMENT).getProperty(LP.data);

			//Create an 'occurrence' edge to the latest matching vertex that share same value if available from the given vertex.
			//This returns the matching data vertex where it has the data field against our current
			//given general vertex's data vertex's data field.
			ArrayList<Vertex> latestMatchingDataVertexList = txGraph.directQueryExpectVertex("select from (select expand(in(" + DBCN.E.data + ")) from " +
					givenVertex.getCName() + " order by @rid desc limit " + totalGCAFrameAllowedInMemory + ") where data = " +
					givenVertexDataValue + " limit 1");

			//If no matches, return empty string.
			if (latestMatchingDataVertexList.isEmpty())
				return "";

			Vertex latestMatchingDataVertex = latestMatchingDataVertexList.get(0);
			//Thus we have to traverse it back to its general vertex as only general vertex is eligible to hold any 'occurrence' edge,
			//data vertex are meant to be static.
			Vertex latestMatchingGeneralVertex = Util.traverseOnce(latestMatchingDataVertex, Direction.OUT, DBCN.E.data, LTM.MOVEMENT);

			//Must create another transaction as it had shown error due to concurrent modification error if we cramp this
			//edge setup into the query.
			//Commit retry model.
			boolean txError = true;
			int txRetried = 0;
			while (txError) {
				if (txRetried > StartupSoft.dbErrMaxRetryCount) {
					throw new IllegalStateException("Failed to complete transaction after number of retry:"
							+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
				}
				else if (txError) {
					if (txRetried != 0) {
						Util.sleep(StartupSoft.dbErrRetrySleepTime);
					}
					txRetried++;
				}
				txGraph.begin();
				latestMatchingGeneralVertex = Util.vReload(latestMatchingGeneralVertex, txGraph);
				givenVertex = Util.vReload(givenVertex, txGraph);

				latestMatchingGeneralVertex.addEdge(DBCN.E.occurrence, givenVertex);

				txError = txGraph.finalizeTask(true);
			}
			typeIsRawDataAnalog = true;
		}

		/*
		 * Get the latest occurrence of the given vertex. And check if its RID exist in the current WM recent GCA.
		 * If exist then return the latest RID, else return "". As all of them when being recreated, will present themselves in the form of
		 * new RID with an occurrence edge to its parent (origin), and the origin vertex itself will never be selected again once it passed its
		 * given round as they are archived. Note that every element in ISRA is unique but can be related or treated as 'same' by the mean of
		 * occurrence or parent.
		 */
		/**
		 * The frame that will be replaced when new frame arrive (will no longer be treated as within current WM soon).
		 * Query: Get a frame from GCAMain list, want only the first frame that will be overridden if a new frame comes in and
		 * exceed the allowed limit or not (if not, then it will not be overridden, this is only possible during startup where frames
		 * count haven't reaches the limit yet, not important as the query expected it well).
		 */
		Vertex nextToBeOutatedGCAFrame = txGraph.directQueryExpectVertex("select from (select from " + DBCN.V.general.GCAMain.cn +
				" order by @rid desc limit " + totalGCAFrameAllowedInMemory + ") order by " + LP.timeStamp.toString() + " limit 1").get(0);
		long beforeOutdatedTimestamp = nextToBeOutatedGCAFrame.getProperty(LP.timeStamp);
		ArrayList<Vertex> occurrences = Util.traverse(givenVertex, Direction.IN, DBCN.E.occurrence);

		//If type is raw data analog, it is a sure pass, but rawDataGCA that contains him may haven't been finalized yet, thus cannot
		//run the check below as it expect that edge, but we all know that it will certainly be in WM soon and 100% valid.
		//Else we just run the usual checking by absolute timestamp via GCA compare.
		boolean ridExistInWM =  typeIsRawDataAnalog == true ? true : false;

		if (!occurrences.isEmpty()) {
			Vertex latestOccurrence = occurrences.get( occurrences.size()-1 );
			String occurrenceType = latestOccurrence.getCName();
			Vertex finalSelectedOccurrece = null;
			//If they are any type of exp' requirement or result, we will traverse to their occurrence's expMainGeneral vertex
			//as only their expMainGeneral got imported back into WM via GCA import. Else just simply calculate it.
			//Note that prediction
			if (occurrenceType.equals(DBCN.V.general.exp.requirement.cn)) {
				Vertex expMainData = Util.traverseOnce(latestOccurrence, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
				Vertex expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
				finalSelectedOccurrece = expMainGeneral;
			}
			else if (occurrenceType.equals(DBCN.V.general.exp.result.cn)) {
				Vertex expMainData = Util.traverseOnce(latestOccurrence, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
				Vertex expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
				finalSelectedOccurrece = expMainGeneral;
			}
			//Prediction is the clone of the result before it got replaced with real result.
			else if (occurrenceType.equals(DBCN.V.general.exp.prediction.cn)) {
				Vertex expMainData = Util.traverseOnce(latestOccurrence, Direction.OUT, DBCN.E.prediction, DBCN.V.LTM.exp.cn);
				Vertex expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
				finalSelectedOccurrece = expMainGeneral;
			}
			else
				finalSelectedOccurrece = latestOccurrence;

			//selectedOccurrenceRid can be either expMainGeneral OR any other vertexes excluding exp requirement and result general type.
			String selectedOccurrenceRid = finalSelectedOccurrece.getRid();

			//Check whether the rid exist in current WM by comparing their timestamp.
			if (!ridExistInWM) {
				System.out.println("finalSelectedOccurrece: " + finalSelectedOccurrece);
				long fetchedGCAMainRecordTime = Util.traverseGetGCAMainGeneral(finalSelectedOccurrece, txGraph).getProperty(LP.timeStamp);

				//If our fetched frame is more latter than the allowed time, means we are in active WM range.
				if (fetchedGCAMainRecordTime > beforeOutdatedTimestamp)
					ridExistInWM = true;
			}

			if (ridExistInWM) {
				//If they are exp requirement or result, we return the relevant requirement/result's rid instead of its expMainGeneral's rid.
				if (occurrenceType.equals(DBCN.V.general.exp.requirement.cn) || occurrenceType.equals(DBCN.V.general.exp.result.cn))
					return latestOccurrence.getRid();
				//If it is prediction type, returns its respective expResultGeneral vertex's RID as this will ultimately used by
				//the engine to assign real result to, we want the final result to point to the real final result instead of the cloned
				//expPredictionGeneral's data.
				else if (occurrenceType.equals(DBCN.V.general.exp.prediction.cn)) {
					Vertex expMainData = Util.traverseOnce(latestOccurrence, Direction.OUT, DBCN.E.prediction, DBCN.V.LTM.exp.cn);
					Vertex expResultGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
					return expResultGeneral.getRid();
				}
				else
					return selectedOccurrenceRid;
			}
		}

		/*
		 * If the occurrence IN is empty, it may be the latest vertex input, in this case, the latest vertex (the input vertex) should be
		 * returned directly, this is possible for exp based requirement OR result OR expMain general vertexes, where they had already setup
		 * a new instance at crawler's SCCRS before moving in, thus they are the latest.
		 * We can confirm its validity by checking whether it has an outgoing occurrence edge that points to previous exp AND it is now within
		 * latest WM, which must be true as it comes in via GCA in the latest GCA frame (or few earlier).
		 * At here we don't differentiate between expMainGeneral, expRequirementGeneral or expResultGeneral as we want to return their rid
		 * directly instead of like the above, which needs to traverse to its relative expMainGeneral then traverse back, as we here are
		 * already the specified type.
		 * If they are exp prediction type, we will get its respective result general's RID, then if the test pass, return that RID instead
		 * of prediction's RID as prediction is a clone to the real result's vertex.
		 *
		 * It is also possible for ALL LTM, as the new RSG contract state that LTM to LTM occurrence, exp to exp occurrence, but never LTM to exp
		 * and in reverse, thus LTM will no longer be encapsulated under exp, and thus will not have the occurrence edge to exp, but it will still
		 * have its occurrence edge to its previous LTM.
		 */
		else {
			//Check whether it has an outgoing occurrence edge.
			ArrayList<Vertex> check = Util.traverse(givenVertex, Direction.OUT, DBCN.E.occurrence);
			String givenVertexType = givenVertex.getCName();

			//Only continue the check if they are the specified type, else just return empty string to mean that it doens't exist.
			if (givenVertexType.equals(DBCN.V.general.exp.cn) || givenVertexType.equals(DBCN.V.general.exp.requirement.cn)
					|| givenVertexType.equals(DBCN.V.general.exp.result.cn) || givenVertexType.equals(DBCN.V.general.exp.prediction.cn)) {
				if (!check.isEmpty()) {
					//Check whether they exist in the current WM in order to be sure that they are the latest generated exp.
					//Traverse to their expMainGeneral as only exp of that type is imported via the GCA into WM.
					Vertex expMainData = null;
					Vertex expMainGeneral = null;
					if (givenVertexType.equals(DBCN.V.general.exp.requirement.cn)) {
						expMainData = Util.traverseOnce(givenVertex, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
						expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
					}
					else if (givenVertexType.equals(DBCN.V.general.exp.result.cn)) {
						expMainData = Util.traverseOnce(givenVertex, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
						expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
					}
					else if (givenVertexType.equals(DBCN.V.general.exp.prediction.cn)) {
						expMainData = Util.traverseOnce(givenVertex, Direction.OUT, DBCN.E.prediction, DBCN.V.LTM.exp.cn);
						expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
					}

					//Check whether the rid exist in current WM by comparing their timestamp.
					if (!ridExistInWM) {
						long fetchedGCAMainRecordTime = Util.traverseGetGCAMainGeneral(expMainGeneral, txGraph).getProperty(LP.timeStamp);

						//If our fetched frame is more latter than the allowed time, means we are in active WM range.
						if (fetchedGCAMainRecordTime > beforeOutdatedTimestamp)
							ridExistInWM = true;
					}
					if (ridExistInWM) {
						if (givenVertexType.equals(DBCN.V.general.exp.prediction.cn)) {
							Vertex expResultGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
							return expResultGeneral.getRid();
						}
						//Return itself if it is confirmed that he exist in current WM.
						else
							return rid;
					}
				}
			}
			//If it is type LTM, its occurrence edge was created during its creation, and it must be inside current WM in order to be treated
			//as exist.
			else if (Util.equalAny(givenVertexType, LTM.GENERAL)) {
				if (!check.isEmpty()) {
					//Check whether the rid exist in current WM by comparing their timestamp.
					if (!ridExistInWM) {
						long fetchedGCAMainRecordTime = Util.traverseGetGCAMainGeneral(givenVertex, txGraph).getProperty(LP.timeStamp);

						//If our fetched frame is more latter than the allowed time, means we are in active WM range.
						if (fetchedGCAMainRecordTime > beforeOutdatedTimestamp)
							ridExistInWM = true;
					}
					if (ridExistInWM) {
						return rid;
					}
				}
			}
		}

		System.out.println("At checkRidExistInMemory, failed. Details: givenVertex:" + givenVertex +
				" ; nextToBeOutatedGCAFrame: " + nextToBeOutatedGCAFrame);
		return "";
	}

	//Used for select attention ADD operation, which add new component to the system for experimental purposes.
	//Encapsulated in get set method as you may want to change it into DB based instead of static references.
	private static final ReentrantLock PaRcFailedConvergenceVertexLock = new ReentrantLock();
	private static ArrayList<Vertex> PaRcFailedConvergenceVertexList = new ArrayList<Vertex>();
	/**
	 * This will be treated as the ADD method's source pattern pool.
	 * This function manages its own transaction!
	 * @param failedConvergenceVertex The convergence that contains the failed exp or raw data or both.
	 * @param reset True if this is a new cycle, else it will just append vertexes to the internal list.
	 */
	private void setPaRcFailedConvergenceVertex(Vertex failedConvergenceVertex, boolean reset, Graph txGraph) {
		PaRcFailedConvergenceVertexLock.lock();
		if (reset)
			PaRcFailedConvergenceVertexList = new ArrayList<Vertex>();
		PaRcFailedConvergenceVertexList.add(failedConvergenceVertex);
		PaRcFailedConvergenceVertexLock.unlock();

		//Check whether the given convergence vertex is the absolute beginning, if so, traverse to its convergenceHead vertex, then setup
		//the PaRcPassed flag to false for analytic and recording purposes.
		int originalOrdering = failedConvergenceVertex.getProperty(LP.originalOrdering);
		//-1 is sentinel value, used to uniquely identify any particular convergence vertex as the beginning of the tree, the head,
		//as specified in STISS during creation.
		if (originalOrdering == -1) {
			txGraph.begin();
			Vertex targetConvergenceHead = Util.traverseOnce(failedConvergenceVertex, Direction.IN, DBCN.E.convergenceHead);
			targetConvergenceHead.setProperty(LP.PaRcPassed, false);
			txGraph.finalizeTask();
		}
	}
	private ArrayList<Vertex> getPaRcFailedConvergenceVertex() {
		PaRcFailedConvergenceVertexLock.lock();
		ArrayList<Vertex> result = PaRcFailedConvergenceVertexList;
		PaRcFailedConvergenceVertexLock.unlock();
		return result;
	}

	public void startService() {
		//When internal errors occurs, it may need to rollback multiple great transaction as a whole, this is to indicate that error occurs and
		//big rollback of all concerned things are required. Will be in effect after solution support nested commit.
		//Not implemented yet.
		boolean internalErrRollback = false;

		LinkedList<ScheduledElementSession> ScheduledElementSessionList = new LinkedList<ScheduledElementSession>();

		Graph txGraph = StartupSoft.factory.getTx();
		//Set the logger in order to use shorthand version of finalizeTask().
		txGraph.loggerSet(StartupSoft.logger, logCredential);

		//Start up a request listener thread to listen to incoming request.
		Thread requestListener = new Thread(new WMRequestListener(config.haltIndex, config.port, config.uid, txGraph));
		requestListener.start();

		String identifier = config.uid + "-" + config.storageId;
		logCredential = new Credential(Thread.currentThread().getName(), identifier, config.parentUid, config.preference.toString());
		StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "WM online. Thread name: " + Thread.currentThread().getName());

		txGraph.begin();

		//Create a record to record this new full system startup.
		Vertex startupRecordVertex = txGraph.addVertex(DBCN.V.startup.cn, DBCN.V.startup.cn);
		//Get the last GCA before shutdown for record purposes, so we can traverse to it in the future easier.
		Vertex lastGCAFrameBeforeLastShutdown = txGraph.getLatestVertexOfClass(DBCN.V.general.GCAMain.cn);
		startupRecordVertex.addEdge(DBCN.E.startupStartGCA, lastGCAFrameBeforeLastShutdown);
		startupRecordVertex.setProperty(LP.startupStartTime, System.currentTimeMillis());
		startupRecordVertex.setProperty(LP.startupPrecisionRate, -1d);
		startupRecordVertex.setProperty(LP.startupPrecisionRateElementCount, -1);
		startupRecordVertex.setProperty(LP.startupGCALength, -1);

		//Temporary holder to share the reference to this actual vertex. Replace any existing vertex with this new reference vertex.
		//TODO: Currently not used, as the WM is expected to be linear.
		Util.removeAllVertexFromClass(DBCN.V.startup.current.cn, txGraph);
		Vertex startupRecordCurrentPointerVertex = txGraph.addVertex(DBCN.V.startup.current.cn, DBCN.V.startup.current.cn);
		startupRecordCurrentPointerVertex.addEdge(DBCN.E.data, startupRecordVertex);

		txGraph.commit();

		//For error recovery, each operation are obligated to finish even if the isHalt() flag is on, but will stop forwarding the task
		//to the next phrase. If amid task passing encounter isHalt(), finish the passing anyway, next phrase will stop the calculation.
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

			//Reset it after error.
			internalErrRollback = false;

			double globalDist = STMClient.getGlobalDist(txGraph);

			//NOTE: lookout for txGraph commit, commented code does not have commit yet!
			/*
			 * Tasks for WM.
			 * -Update assigned prediction against latest reality
			 * -If error occurs, create a new error task and provide sufficient debug detail for STMServer to handle.
			 * -If error happens at other nodes, we will clean up all assigned prediction data as rollback. In the future you might delete only affected portion.
			 * -Provide service to query whether a data exist in reality, scheduled to exist or existed in the WM in form of RIDs. Span to sibling level.
			 */

			//-For each active session, check whether it is time to execute them.
			for (Iterator<ScheduledElementSession> sessionIterator = ScheduledElementSessionList.iterator(); sessionIterator.hasNext();) {
				//Breakpoint to skip further action and return.
				if (isHalt())
					break;

				ScheduledElementSession scheduledElementSession = sessionIterator.next();

				//If this is true, the affected element session will be removed and no episodic exp created.
				boolean predictionAgainstRealityFailed = false;

				//Globally shared timeNotMet, if one operation fails the time checking phrase, we will not continue on.
				boolean timeNotMet = false;

				//Total depth start from index 0 instead of 1 like how size do, thus it can be used directly as index, no conversion (+-1) required.
				int currentDepth = scheduledElementSession.totalDepth;

				//Iterate over all element if they are available, break if time not yet match, this will loop through all element eventually and
				//doesn't stop for element that are not previously completed, it will just get updated at the next full iteration.
				for (; currentDepth >= 0; currentDepth--) {
					//Get the current depth of interest.
					LinkedList<ScheduledElement> scheduledElementList = new LinkedList<ScheduledElement>
							( scheduledElementSession.data.get(currentDepth) );

					//The collection specify it will return empty list if the given seek index is invalid.
					//It will be invalid as we will remove it if it passed in the code below via
					//scheduledElementList.iterator() produced elementIterator by calling elementIterator.remove()
					//This is to protect it so we don't recompute it. So we also need to be aware of that and skip it
					//if it is empty (signifying that it had been processed) by simply continue, where it will update the
					//currentDepth value, so we can reach the recursiveContagiousExpGeneration() call below safely
					//where it expect the depth to be 0. Without this clause (continue) it will never reach depth 0
					//if the time is not met during PaRc code, where it will reset and try again later, causing it to miss this
					//as the depth value is being resetted without decrementing it properly again.
					if (scheduledElementList.isEmpty()) {
						continue;
					}

					for (Iterator<ScheduledElement> elementIterator = scheduledElementList.iterator(); elementIterator.hasNext();) {
						ScheduledElement scheduledElement = elementIterator.next();

						//Store the time of the first task that had reached the time for it to run (checkIsNow() pass).
						long checkIsNowPassedFirstStartTime = -1;

						StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
								"ScheduledElementTraceUser: " + scheduledElement.ICLPatterns.size() + " " + scheduledElement.ICLPatterns.keys().size() + " "
								+ new ArrayList<Long>(scheduledElement.ICLPatterns.keys()).size() + " "
								+ scheduledElement.processes.size() + " " + scheduledElement.processes.keys().size() + " " +
								new ArrayList<Long>(scheduledElement.processes.keys()).size() + " "
								+ scheduledElement.predictions.size() + " " + scheduledElement.predictions.keys().size() + " "
								+ new ArrayList<Long>(scheduledElement.predictions.keys()).size() + "  ID: "
								+ scheduledElement.secondaryVertex );

						//Forward physical output to external device.
						if (!scheduledElement.processDone) {
							boolean innerTimeNotMet = false;
							//Check whether it is first time run, then check whether it is an empty map, if so mark it as completed. Check only once.
							if (scheduledElement.processDoneIndexList.isEmpty()) {
								if (scheduledElement.processes.isEmpty())
									scheduledElement.processDone = true;
								for (int i=0; i<scheduledElement.processes.size(); i++)
									scheduledElement.processDoneIndexList.add(false);
							}

							ArrayList<Long> keys = new ArrayList<Long>( scheduledElement.processes.keys() );
							ArrayList<Vertex> values = new ArrayList<Vertex>( scheduledElement.processes.values() );
							assert keys.size() == values.size();
							for (int i=0; i<scheduledElement.processDoneIndexList.size(); i++) {
								//If it had been completed already, continue.
								if (scheduledElement.processDoneIndexList.get(i).booleanValue() == true)
									continue;
								long startTimeOffset = keys.get(i);
								Vertex process = values.get(i);

								//If it is time to execute then execute it, execute, else set up a flag and exit.
								if ( checkIsNow(scheduledElementSession.startTime + startTimeOffset, commonAllowanceMilli) ) {
									//If this is the first checkIsNow passes for this round of element check, set it to current time.
									checkIsNowPassedFirstStartTime = checkIsNowPassedFirstStartTime == -1
											? System.currentTimeMillis() : checkIsNowPassedFirstStartTime;
									ActionScheduler.execute(process, txGraph);

									scheduledElement.processDoneIndexList.set(i, true);
								}
								else {
									timeNotMet = true;
									innerTimeNotMet = true;
									break;
								}
							}

							//If time is met and we had processed all tasks, set state to completed.
							if (!innerTimeNotMet && Util.isAllTrue(scheduledElement.processDoneIndexList))
								scheduledElement.processDone = true;
						}

						//Forward expected patterns to ICL in order to identify it now against latest external input.
						if (!scheduledElement.ICLPatternDone) {
							boolean innerTimeNotMet = false;
							//Check whether it is first time run, then check whether it is an empty map, if so mark it as completed. Check only once.
							if (scheduledElement.ICLPatternDoneIndexList.isEmpty()) {
								if (scheduledElement.ICLPatterns.isEmpty())
									scheduledElement.ICLPatternDone = true;
								for (int i=0; i<scheduledElement.ICLPatterns.size(); i++)
									scheduledElement.ICLPatternDoneIndexList.add(false);
							}

							ArrayList<Long> keys = new ArrayList<Long>( scheduledElement.ICLPatterns.keys() );
							ArrayList<Vertex> values = new ArrayList<Vertex>( scheduledElement.ICLPatterns.values() );
							assert keys.size() == values.size();
							for (int i=0; i<scheduledElement.ICLPatternDoneIndexList.size(); i++) {
								//If it had been completed already, continue.
								if (scheduledElement.ICLPatternDoneIndexList.get(i).booleanValue() == true)
									continue;

								long startTimeOffset = keys.get(i);
								Vertex ICLPattern = values.get(i);
								String ICLPatternType = ICLPattern.getCName();

								//If it is time, post them all the appropriate crawler's ICL accessible storage, else set up a flag and exit.
								if (checkIsNow(scheduledElementSession.startTime + startTimeOffset, commonAllowanceMilli)) {
									//If this is the first checkIsNow passes for this round of element check, set it to current time.
									checkIsNowPassedFirstStartTime = checkIsNowPassedFirstStartTime == -1
											? System.currentTimeMillis() : checkIsNowPassedFirstStartTime;

									//Commit retry model.
									boolean txError = true;
									int txRetried = 0;
									while (txError) {
										if (txRetried > StartupSoft.dbErrMaxRetryCount) {
											throw new IllegalStateException("Failed to complete transaction after number of retry:"
													+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
										}
										else if (txError) {
											if (txRetried != 0)
												Util.sleep(StartupSoft.dbErrRetrySleepTime);
											txRetried++;
										}
										txGraph.begin();

										ICLPattern = Util.vReload(ICLPattern, txGraph);

										//Create a new ICLPattern forwarding vertex and setup the general raw data ICL vertex to it.
										if (ICLPatternType.equals(DBCN.V.general.rawDataICL.visual.cn)) {
											Vertex newICLPattern = txGraph.addVertex(DBCN.V.temp.ICLPatternFeedback.rawData.visual.cn,
													DBCN.V.temp.ICLPatternFeedback.rawData.visual.cn);
											newICLPattern.addEdge(DBCN.E.source, ICLPattern);
										}
										else if (ICLPatternType.equals(DBCN.V.general.rawDataICL.audio.cn)) {
											Vertex newICLPattern = txGraph.addVertex(DBCN.V.temp.ICLPatternFeedback.rawData.audio.cn,
													DBCN.V.temp.ICLPatternFeedback.rawData.audio.cn);
											newICLPattern.addEdge(DBCN.E.source, ICLPattern);
										}
										else if (ICLPatternType.equals(DBCN.V.general.rawDataICL.movement.cn)) {
											Vertex newICLPattern = txGraph.addVertex(DBCN.V.temp.ICLPatternFeedback.rawData.movement.cn,
													DBCN.V.temp.ICLPatternFeedback.rawData.movement.cn);
											newICLPattern.addEdge(DBCN.E.source, ICLPattern);
										}
										else
											throw new IllegalStateException("At WM ICLPattern pairing phrase, unknown and unsupported type:"
													+ ICLPatternType);

										txError = txGraph.finalizeTask(true);
									}
									scheduledElement.ICLPatternDoneIndexList.set(i, true);
								}
								else {
									timeNotMet = true;
									innerTimeNotMet = true;
									break;
								}
							}

							//If time is met and we had processed all tasks, set state to completed.
							if (!innerTimeNotMet && Util.isAllTrue(scheduledElement.ICLPatternDoneIndexList))
								scheduledElement.ICLPatternDone = true;
						}

						//Check whether predictions have come true.
						//Note: Only prediction can cause abrupt branch switch during operation if prediction doesn't match reality.
						if (!scheduledElement.predictionDone) {
							boolean innerTimeNotMet = false;

							//Check whether it is first time run, then check whether it is an empty map, if so mark it as completed. Check only once.
							if (scheduledElement.predictionDoneIndexList.isEmpty()) {
								if (scheduledElement.predictions.isEmpty())
									scheduledElement.predictionDone = true;
								for (int i=0; i<scheduledElement.predictions.size(); i++)
									scheduledElement.predictionDoneIndexList.add(false);
							}

							ArrayList<Long> keys = new ArrayList<Long>( scheduledElement.predictions.keys() );
							ArrayList<Vertex> values = new ArrayList<Vertex>( scheduledElement.predictions.values() );
							assert keys.size() == values.size();
							for (int i=0; i<scheduledElement.predictionDoneIndexList.size(); i++) {
								//If it had been completed already, continue.
								if (scheduledElement.predictionDoneIndexList.get(i).booleanValue() == true)
									continue;

								long startTimeOffset = keys.get(i);
								Vertex prediction = values.get(i);

								//If it is time to execute then execute it, execute, else set up a flag and exit.
								if (checkIsNow(scheduledElementSession.startTime + startTimeOffset, GCAFeedbackAllowanceMilli) ) {
									//If this is the first checkIsNow passes for this round of element check, set it to current time.
									checkIsNowPassedFirstStartTime = checkIsNowPassedFirstStartTime == -1
											? System.currentTimeMillis() : checkIsNowPassedFirstStartTime;
									/*
									 * For predictions that are in form of exp, it will pass the checkRidExistInReality as its 'occurrence'
									 * edges had already been set at crawler's SCCRS and forwarded back into here in WM.
									 * For processes type, it will pass as well as the 'occurrence' edge would have been set up during their
									 * entry and the edge will be set against its previous instance.
									 * ICL will not pass initially, until it completes the loop (to ICL, comes back to WM with proper 'occurrence'
									 * edge).
									 */
									String predictionRid = prediction.getRid();
									String resultRid = checkRidExistInReality(predictionRid, txGraph);
									boolean exist = true;
									if (resultRid.equals(""))
										exist = false;

									//If doesn't exist and exceeded allowance, it means we had predicted it wrongly, raise an error and exit.
									//Allowance is given to check isLate to give additional time for those whom need ICL or pre-forming to
									//have enough time to do their job and return their result to DB so we here can get its result by
									//checking their result existence, thus making this clause pass.
									//Do not forward task if it is isHalt().
									if (!exist && checkIsLate(scheduledElementSession.startTime + startTimeOffset, GCAFeedbackAllowanceMilli) && !isHalt()) {
										//Keep traversing until we reaches the expMainGeneral of the current secondaryVertex's data.
										//Then update their precisionRate value. If they are LTM type then do nothing as the update of
										//its precisionRate value should be done at its proxy exp generated by depth 0 operation at WM.
										Vertex dataGeneralVertex = Util.traverseOnce(scheduledElement.secondaryVertex, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);
										String dataType = dataGeneralVertex.getCName();
										boolean isExp = true;

										Vertex expMainGeneral = null;

										if (dataType.equals(DBCN.V.general.exp.requirement.cn)) {
											Vertex expMainData = Util.traverseOnce(dataGeneralVertex, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
											expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
										}
										//If it is already type exp result, we will extract the data directly.
										else if (dataType.equals(DBCN.V.general.exp.result.cn)) {
											Vertex expMainData = Util.traverseOnce(dataGeneralVertex, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
											expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
										}
										//If it is any LTM general type, do not create or update any data, only exp can store updated data, LTM data are immutable.
										//LTM doesn't have precisionRate value, it is stored in its proxy exp, thus totally no update here.
										else if (Util.equalAny(dataType, LTM.GENERAL)) {
											isExp = false;
										}
										else
											throw new IllegalStateException("At WM, secondaryVertex's data field contains unsupported dataType:" + dataType);

										/*
										 * For every PaRc passed or failed expMainGeneral, update their precisionRate to better value.
										 * precisionRate update only occurs in PaRc's passed, which is here, and failed, which is before PaRc
										 * forwarding to RSGFSB.
										 * Update given expMainGeneral's precisionRate value as for every element in here are passed,
										 * so need to be updated here.
										 * For all unpassed event, they are found and computed at PaRc just before forwarding to RSGFSB,
										 * and only for that depth, so no duplication will occur.
										 */
										//Only continue if they are exp as if they are not we do nothing.
										if (isExp) {
											assert expMainGeneral.getCName().equals(DBCN.V.general.exp.cn);
											//Update expMainGeneral's PR value, here is the failed version.
											long totalCalculatedOccurrenceCount = expMainGeneral.getProperty(LP.occurrenceCountPR);
											double precisionRate = expMainGeneral.getProperty(LP.precisionRate);

											//Commit retry model.
											boolean txError = true;
											int txRetried = 0;
											while (txError) {
												if (txRetried > StartupSoft.dbErrMaxRetryCount) {
													throw new IllegalStateException("Failed to complete transaction after number of retry:"
															+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
												}
												else if (txError) {
													if (txRetried != 0)
														Util.sleep(StartupSoft.dbErrRetrySleepTime);
													txRetried++;
												}
												txGraph.begin();

												expMainGeneral = Util.vReload(expMainGeneral, txGraph);

												//Occurrence count 0 means never been calculated before, and at here it is failed, so 0%.
												//Must be calculated externally to avoid /0 error.
												//Count becomes 1 to indicate we had processed its PR value once, and this value also synchronize with
												//the occurrence edge count as we only update precisionRate value once per new occurrence edge.
												if (totalCalculatedOccurrenceCount == 0) {
													expMainGeneral.setProperty(LP.precisionRate, 0d);
													expMainGeneral.setProperty(LP.occurrenceCountPR, 1);
												}
												else {
													long currentPassedCount = Util.precisionRateCalculatePassedCount(precisionRate, totalCalculatedOccurrenceCount);
													//passedCount doesn't increment by 1 as here we are treated as PaRc failed, no increment will drag
													//down the precisionRate value.
													//Total solution count +1 as now we had just calculated once more.
													//passedElementCount / totalElementCount = precisionRate  *100 to make them percentage.
													expMainGeneral.setProperty(LP.precisionRate,
															((double)(currentPassedCount) / (double)(totalCalculatedOccurrenceCount + 1l)) * 100d );
													expMainGeneral.setProperty(LP.occurrenceCountPR, totalCalculatedOccurrenceCount + 1l);
												}

												scheduledElement.secondaryVertex = Util.vReload(scheduledElement.secondaryVertex, txGraph);

												//Traverse to its mainConvergence vertex which holds multiple solutions which the RSGFSB needs.
												//We only have secondary convergence (the selected solution) here, we need to traverse once upward.
												Vertex mainConvergenceVertex = Util.traverseOnce(scheduledElement.secondaryVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);

												Vertex newRSGFSBTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.RSGFSB.task.cn, DBCN.V.jobCenter.crawler.RSGFSB.task.cn);
												Vertex RSGFSBTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
												newRSGFSBTask.addEdge(DBCN.E.source, RSGFSBTaskDetailVertex);
												RSGFSBTaskDetailVertex.addEdge(DBCN.E.source, mainConvergenceVertex);
												RSGFSBTaskDetailVertex.addEdge(DBCN.E.session, Util.ridToVertex(scheduledElementSession.sessionRid, txGraph));

												TaskDetail RSGFSBTaskDetail = new TaskDetail();
												RSGFSBTaskDetail.jobId = "-1";
												RSGFSBTaskDetail.jobType = CRAWLERTASK.DM_RSGFSB;
												RSGFSBTaskDetail.source = "";
												RSGFSBTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.RSGFSB.processing.cn;
												RSGFSBTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.RSGFSB.completed.cn;
												RSGFSBTaskDetail.replyAddr = DBCN.V.devnull.cn;
												RSGFSBTaskDetail.start = -1;
												RSGFSBTaskDetail.end = -1;
												RSGFSBTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(RSGFSBTaskDetail) );

												txError = txGraph.finalizeTask(true);

												//Add the PaRc failed convergence vertex to a list.
												setPaRcFailedConvergenceVertex(mainConvergenceVertex, true, txGraph);
											}
										}
										predictionAgainstRealityFailed = true;
										StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
												"Prediction against reality failed. Move to RSGFSB. RID: " + prediction +
												" ; " + scheduledElement.processDone + scheduledElement.ICLPatternDone + scheduledElement.predictionDone);
										break;
									}

									//If it exist, we will store the result temporary, then after all prediction is done, forward it
									//permanently into the original given exp by replacing the original result.
									else if (exist) {
										scheduledElement.realResult.put(i, Util.ridToVertex(resultRid, txGraph));
										scheduledElement.predictionDoneIndexList.set(i, true);
									}
								}
								else {
									timeNotMet = true;
									innerTimeNotMet = true;
									break;
								}
							}

							//If time is met, PaRc success and we had processed all tasks, set state to completed.
							if (!innerTimeNotMet && !predictionAgainstRealityFailed && Util.isAllTrue(scheduledElement.predictionDoneIndexList))
								scheduledElement.predictionDone = true;
						}

						//If something had just been run, check and update the time variables.
						if (checkIsNowPassedFirstStartTime != -1) {
							//Setup scheduledElementSession firstStartTimePerDepth for current depth if not yet set.
							if (scheduledElementSession.firstStartTimePerDepth.get(currentDepth) == -1)
								scheduledElementSession.firstStartTimePerDepth.set(currentDepth, checkIsNowPassedFirstStartTime);
						}

						//If time is not met yet, no point to continue onto next scheduled element where its time will be even further not met.
						if (timeNotMet)
							break;

						//If all of them are done, copy the prediction result into the original exp's result to replace them,
						//then remove the entry. Rationale of replacing them is to update the result to the latest form.
						//OR if PaRc failed, attempt to replace them with whatever we have to ensure that the exp itself gets
						//most of the correct things out from it.
						boolean replaceDummyResultWithRealResultNow = false;
						if (scheduledElement.processDone && scheduledElement.predictionDone && scheduledElement.ICLPatternDone  && !predictionAgainstRealityFailed)
							replaceDummyResultWithRealResultNow = true;
						else if (predictionAgainstRealityFailed)
							replaceDummyResultWithRealResultNow = true;

						if (replaceDummyResultWithRealResultNow) {
							//Get the original expResultData vertex, remove all of its old result edges and setup new edges.
							Vertex dataGeneralVertex = Util.traverseOnce(scheduledElement.secondaryVertex, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);
							String dataType = dataGeneralVertex.getCName();
							boolean isExp = true;

							ArrayList<Long> originalStartTimeOffset = new ArrayList<Long>( scheduledElement.predictions.keys() );

							//If they are type exp, they should init this as the logic below within isExp() requires this.
							Vertex expMainData = null;
							Vertex expResultData = null;

							//If it is type exp requirement, we have to traverse to its result then extract the data.
							if (dataType.equals(DBCN.V.general.exp.requirement.cn)) {
								expMainData = Util.traverseOnce(dataGeneralVertex, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
								Vertex expResultGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
								expResultData = Util.traverseOnce(expResultGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
							}
							//If it is already type exp result, extract the data directly.
							else if (dataType.equals(DBCN.V.general.exp.result.cn)) {
								expMainData = Util.traverseOnce(dataGeneralVertex, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
								expResultData = Util.traverseOnce(dataGeneralVertex, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
							}
							//If it is any LTM general type, do not create or update any data, only exp can store updated data, LTM data are immutable.
							//LTM doesn't have precisionRate value, it is stored in its proxy exp, thus totally no update here.
							else if (Util.equalAny(dataType, LTM.GENERAL)) {
								isExp = false;
							}
							else
								throw new IllegalStateException("At WM, secondaryVertex's data field contains unsupported dataType:" + dataType);

							//Only replace result with real result if they are exp, else do nothing.
							if (isExp) {
								ArrayList<Vertex> originalResults = Util.traverse(expResultData, Direction.OUT, DBCN.E.result);

								//Remove previous edges.
								//Commit retry model.
								boolean txError = true;
								int txRetried = 0;
								while (txError) {
									if (txRetried > StartupSoft.dbErrMaxRetryCount) {
										throw new IllegalStateException("Failed to complete transaction after number of retry:"
												+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
									}
									else if (txError) {
										if (txRetried != 0)
											Util.sleep(StartupSoft.dbErrRetrySleepTime);
										txRetried++;
									}
									txGraph.begin();

									Iterable<Edge> originalResultEdges = Util.traverseGetEdges(expResultData, Direction.OUT, DBCN.E.result);

									for (Edge e : originalResultEdges)
										e.remove();

									txError = txGraph.finalizeTask(true);
								}

								//Update the old copied result with new latest real result and copy the old start time off set to respect the
								//actual/original flow of the exp. Note that the key of the map is the index.
								//If it is a PaRc failed, it may have uncompleted vertexes, we will be aware of that and replace
								//those missing values with original values instead.
								ArrayList<Vertex> finalResultVertexList = new ArrayList<Vertex>();
								for (int i=0; i<originalResults.size(); i++) {
									Vertex target = scheduledElement.realResult.get(i);
									if (target == null)
										finalResultVertexList.add(originalResults.get(i));
									else
										finalResultVertexList.add(target);
								}

								//Begin update the resultGeneralVertex with real computed result data.
								for (int i=0; i<finalResultVertexList.size(); i++) {
									//Commit retry model.
									boolean txError2 = true;
									int txRetried2 = 0;
									while (txError2) {
										if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
											throw new IllegalStateException("Failed to complete transaction after number of retry:"
													+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
										}
										else if (txError2) {
											if (txRetried2 != 0)
												Util.sleep(StartupSoft.dbErrRetrySleepTime);
											txRetried2++;
										}
										txGraph.begin();

										expResultData = Util.vReload(expResultData, txGraph);
										Vertex realResultReloaded = Util.vReload(finalResultVertexList.get(i), txGraph);

										Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, realResultReloaded);
										expResultDataEdge.setProperty(LP.startTimeOffset, originalStartTimeOffset.get(i));

										txError2 = txGraph.finalizeTask(true);
									}
								}

								/*
								 * For every PaRc passed or failed expMainGeneral, update their precisionRate to better value.
								 * precisionRate update only occurs in PaRc's passed, which is here, and failed, which is before PaRc
								 * forwarding to RSGFSB.
								 * Update given expMainGeneral's precisionRate value as for every element in here are passed,
								 * so need to be updated here.
								 * For all unpassed event, they are found and computed at PaRc just before forwarding to RSGFSB,
								 * and only for that depth, so no duplication will occur.
								 */
								//Commit retry model.
								boolean txError2 = true;
								int txRetried2 = 0;
								while (txError2) {
									if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
										throw new IllegalStateException("Failed to complete transaction after number of retry:"
												+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
									}
									else if (txError2) {
										if (txRetried2 != 0)
											Util.sleep(StartupSoft.dbErrRetrySleepTime);
										txRetried2++;
									}
									txGraph.begin();

									Vertex expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);

									//Update expMainGeneral's PR value, here is the passed version.
									long totalCalculatedOccurrenceCount = expMainGeneral.getProperty(LP.occurrenceCountPR);
									double precisionRate = expMainGeneral.getProperty(LP.precisionRate);
									//Occurrence count 0 means never been calculated before, and at here it is pass, so direct 100%.
									//Must be calculated externally to avoid /0 error.
									//Count becomes 1 to indicate we had processed its PR value once, and this value also synchronize with
									//the occurrence edge count as we only update PR value once per new occurrence edge.
									if (totalCalculatedOccurrenceCount == 0) {
										if (predictionAgainstRealityFailed) {
											expMainGeneral.setProperty(LP.precisionRate, 0d);
											expMainGeneral.setProperty(LP.occurrenceCountPR, 1);
										}
										else {
											expMainGeneral.setProperty(LP.precisionRate, 100d);
											expMainGeneral.setProperty(LP.occurrenceCountPR, 1);
										}
									}
									else {
										if (predictionAgainstRealityFailed) {
											long currentPassedCount = Util.precisionRateCalculatePassedCount(precisionRate, totalCalculatedOccurrenceCount);
											//passedCount doesn't increment as PaRc failed.
											//Total solution count +1 as now we had just calculated once more.
											//passedElementCount / totalElementCount = precisionRate  *100 to make them percentage.
											expMainGeneral.setProperty(LP.precisionRate,
													((double)(currentPassedCount) / (double)(totalCalculatedOccurrenceCount + 1l)) * 100d );
											expMainGeneral.setProperty(LP.occurrenceCountPR, totalCalculatedOccurrenceCount + 1l);
										}
										else {
											long currentPassedCount = Util.precisionRateCalculatePassedCount(precisionRate, totalCalculatedOccurrenceCount);
											//passedCount +1 as here we are treated as PaRc passed, thus increment.
											//Total solution count +1 as now we had just calculated once more.
											//passedElementCount / totalElementCount = precisionRate  *100 to make them percentage.
											expMainGeneral.setProperty(LP.precisionRate,
													((double)(currentPassedCount + 1l) / (double)(totalCalculatedOccurrenceCount + 1l)) * 100d );
											expMainGeneral.setProperty(LP.occurrenceCountPR, totalCalculatedOccurrenceCount + 1l);
										}
									}

									txError2 = txGraph.finalizeTask(true);
								}
							}
							//Remove the whole element to treat it as completed.
							elementIterator.remove();

							if (predictionAgainstRealityFailed)
								break;
						}
					}	//End of specific scheduled element iterator.

					//We break out from the inner iterator until there is no more enclosing loop once more.
					if (predictionAgainstRealityFailed || timeNotMet) {
						break;
					}
					//Else we generate a episodic memory (contagious memory) from the exp we had just completed as a whole.
					else if (scheduledElementList.isEmpty()) {
						//If just completed all requirements for the depth and the depth is the last depth, then generate a new
						//exp based on the tree, to form episodic memory (contagious memory).
						//Note that if reached this stage, all of the related exp would have been updated with the latest result,
						//so just have to extract them out and put them into a contagious exp.
						//After the tree generation is complete, dispose the whole session as we had done with it.
						if (currentDepth == 0) {
							//Setup the contagious tree exp recursively.
							//NOTE: This function modulate its own begin(), finalizeTask() cycle in order to better manage resources.
							recursiveContagiousExpGeneration(
									scheduledElementSession.mainConvergenceHead, Util.ridToVertex(scheduledElementSession.sessionRid, txGraph)
									, scheduledElementSession.firstStartTimePerDepth, txGraph);

							//After the tree is setup correctly, remove the whole entry as a whole to treat it as completed.
							sessionIterator.remove();
							StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "recursiveContagiousExpGeneration returned to WM successfully.");
							break;
						}
					}
				}	//End of scheduled element by depth iterator.

				//If PaRc failed, remove the whole session as a whole as it will not be calculated anymore.
				if (predictionAgainstRealityFailed) {
					sessionIterator.remove();
				}
				//No need to check about timeNotMet here as at the next session it will be reset, and we do want them to run
				//instead of killing them due to previous session failure.
			} //End of session iterator.

			//Breakpoint to skip further action and return.
			if (isHalt())
				continue;

			///-Get new scheduled prediction.
			Vertex addPredictionTaskVertex = STMClient.getNextWMTask(config.storageId, txGraph);
			//null means no work available.
			if (addPredictionTaskVertex != null) {
				//MainConvergence type's head, NOT convergenceHead!
				Vertex mainConvergenceHead = Util.traverseOnce(addPredictionTaskVertex, Direction.OUT, DBCN.E.source, DBCN.V.general.convergenceMain.cn);

				String sessionRid = addPredictionTaskVertex.getProperty(LP.sessionRid);
				if (sessionRid == null || sessionRid.equals("") || sessionRid.isEmpty())
					throw new IllegalStateException("Invalid session Rid. Received: " + sessionRid);

				//NOTE NOTE: Now only support 1 WM instance, therefore the inter-WM sharing system in not yet implemented,
				//you would have to sync with all other WM instance by a counter to implement multiple in memory WM.
				//There is 2 of this thing, last one is at further distance. This is second, the further 1.
				//Remove it earlier before it enters the main processing routine which will cause concurrentMod error.
				//Commit retry model.
				boolean txError = true;
				int txRetried = 0;
				while (txError) {
					if (txRetried > StartupSoft.dbErrMaxRetryCount) {
						throw new IllegalStateException("Failed to complete transaction after number of retry:"
								+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
					}
					else if (txError) {
						if (txRetried != 0)
							Util.sleep(StartupSoft.dbErrRetrySleepTime);
						txRetried++;
					}
					txGraph.begin();
					addPredictionTaskVertex = Util.vReload(addPredictionTaskVertex, txGraph);

					addPredictionTaskVertex.remove();
					txError = txGraph.finalizeTask(true);
				}

				//Check whether it is the correct class.
				if (mainConvergenceHead.getCName().equals(DBCN.V.general.convergenceMain.cn)) {
					//It should be the head of the newly generated solution tree.
					//After fetching phrase it will go into processing phrase to install them into local memory for faster access and pairing.

					//Begin recursively extract data from the solution tree and add it to the active prediction list.
					//NOTE THIS function recursiveExtractElementToBeScheduledFromSolutionTree manage its own transaction! Do not embed
					//transaction around it!
					ScheduledElementSession newScheduledElementSession = recursiveExtractElementToBeScheduledFromSolutionTree(mainConvergenceHead, txGraph);
					newScheduledElementSession.sessionRid = sessionRid;
					newScheduledElementSession.mainConvergenceHead = mainConvergenceHead;
					ScheduledElementSessionList.add(newScheduledElementSession);

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "Fetch new prediction success.");
				}
				else
					throw new IllegalStateException("At WM add prediction phrase, expect vertex origin from class:"
							+ DBCN.V.WM.timeline.addPrediction.cn + " but get:" + mainConvergenceHead.getCName());
			}

			//Breakpoint to skip further action and return.
			if (isHalt())
				continue;

			/*
			 * Next design milestone:
			 * Concurrent attention running, keep checking new attention, if the new attention is better than the current attention, run the new
			 * attention, current attention will be notified and either be put to Util.sleep(may be waked) or merged.
			 * Attention may have interval so we don't seek new attention vigorously and ignore other vital WM functionality.
			 */
			///-Select new attention point if we had completed all of them.
			//Similar to the DM logic, but utilize different data and frame.
			if (ScheduledElementSessionList.isEmpty()) {
				/*
				 * Session design logic:
				 * Session is used to recall actions, store states of what had been tested and what had not in order to experiment and improve any
				 * exp of interest by increasing their precision rate (similarity between reality and prediction).
				 * Each selected exp here will own an exclusive session dedicated to store its states, it is not share-able. One exp can only
				 * have one session at a time (when they are selected by WM initial startup which is here). Any extra exp created during the session
				 * will share the expState (adding, removing, completed or any more new state) that the session carry, that is just a birthmark to
				 * indicate what they are here for, they are not eligible to have an edge to the session as they don't own it, only the original
				 * selected vertex can have edge to it.
				 * When we select solution at WM, if the selected exp already previously own a session, we will reuse that and check its previous
				 * state, then see whether you want to improve it or not by adding or removing its requirements and results.
				 * This will causes the exp to evolve and be more precise.
				 * Session will not be actively stored in WM for recall, as we want to diversify our actions, thus it can only by chance to reinvoke
				 * those who had been invoked previously, this is to ensure fairness and wider view. DM will pick the best route on its own.
				 * Session will also store the best solution that he had created, which is the one with highest precision rate, and session
				 * will also create a reference (edge or rid) to that vertex for faster retrieval.
				 *
				 * During solution generation, they can use session as shortcut, they may had used to old and not so precise solution in the past,
				 * now they can check whether the requirement had a session, if they had, move forward into it and see it they have better solution
				 * that are recommended by the session (higher precision rate than itself), if have then we will use it, thus the overall precision
				 * will be improved and you don't have to go far to find the best solution as many exp will not have the opportunity to actually
				 * be selected by the WM engine and own their own session.
				 * But sometimes the session may be outdated as well, thus it only serves as a shortcut, doesn't mean it guarantees the best route,
				 * so usual routine will still have to be checked.
				 *
				 * LTM data will not be accompanied by any session, only exp is allowed to have edge to session.
				 */

				/*
				 * IMPORTANT Design Note: LTMFH (LTM First Half), LTMSH (LTM Second Half) special properties
				 * when being selected as new attention point.
				 * For operation LTMFH:
				 * LTM general will be encapsulated into an exp, with its requirement and result be the actual LTM general vertex, its process
				 * be extracted from its current GCA frame.
				 *
				 * LTMSH is only possible for those exp (Everything after LTMFH is encapsulated into an exp) whose requirement and result
				 * are both the same LTM general vertex. We will rerun the exp once, then get the GCA frame concerned by this rerun.
				 * Now we have 2 GCA frame, 1 concerning the actual LTM, another concerning the encapsulated LTM.
				 * Extract similarity between both of the GCA frame, those similarity will become the new result for the exp created
				 * (create new exp for every run) for this operation, its requirement and process remains the same.
				 *
				 * Any data larger than LTMSH will have precise requirement (has only 1 element) and process, while result will be variable in
				 * size based on the similarity of elements between the 2 GCA mentioned above.
				 * Any larger than LTMSH will use the universal solution tree generation approach, where it will generate larger exp chains
				 * concerning multiple requirements and results in the future, and will also be able to tweak them now by adding, removing,
				 * replacing or do nothing during the attention selection phrase by modifying the selection before giving it to the universal
				 * solution generating algorithm. This is for encapsulation purposes so the universal algorithm (crawler code to be precise)
				 * will never have to know about these LTMFH, LTMSH special properties.
				 *
				 * NOTE: LTMFH is operation 0, LTMSH operation 1, total 2 operation, but its depth is still going to be 0 as we directly
				 * modify the exp's result instead of nesting anything to it.
				 * Not really meaning the real nesting depth of the actual requirement or result's data.
				 *
				 * For LTMSH exp and normal exp (without their own session or with session, which means that he had been selected before),
				 * they all share the same competition in the selection phrase, thus they all have chances once they get back from the GCA again.
				 * Or if they are recalled as side effect which gradually forwards them back into GCA and into the selection pool again.
				 */

				//Check LTM first then check exp, LTM is more important as it is the beginning, exp must have LTM as its base during the beginning.
				//LTM will form new exp, but exp cannot form new LTM.
				//Only LTM that are new will be concerned, things that had run 0 and 1 time (means very less) will be concerned. More than 1 usually
				//means he already have an exp counterpart, then we will no longer bother with LTM.
				//<2 means solution that had never been ran before (0) and ran only once(1) can be accepted, and 0(never ran) gets priority.
				//Compare it against globalDist to find the solution that its polyVal is closest to the globalDist.
				ArrayList<Vertex> LTMTimeRanLow = null;
				ArrayList<String> timeRanIndexClassClusterNameList = Util.WMSTMGetClusterName(DBCN.index.WMTimeRanIndex.cn, txGraph);
				//Record the class name, used for record deletion below.
				String LTMTimeRanLowOriginClusterName = null;
				for (String indexClassName : timeRanIndexClassClusterNameList) {
					LTMTimeRanLow = txGraph.directQueryExpectVertex("select expand(rid) from (select abs(eval('key - " + globalDist
							+ "')) as nearest, rid from (SELECT FROM INDEX:" + indexClassName +
							" WHERE key < 2 limit 1000) order by nearest asc limit 1)");
					//If we got the data, just break, doesn't need to query all indexes anymore.
					if (!LTMTimeRanLow.isEmpty()) {
						LTMTimeRanLowOriginClusterName = indexClassName;
						break;
					}
				}
				boolean LTMEmpty = false;
				if (LTMTimeRanLow == null || LTMTimeRanLow.isEmpty())
					LTMEmpty = true;

				//If there is something in LTM that needs to be selected (processed), we will select it based on polyVal. LTM First Half special operation.
				//LTMFH operation is self contained so the information it created here will not cascade downward to the selection phrase.
				//It will only be available at the next GCA frame which feedbacks back to here (WM).
				//TODO: Import them all at once so they get to the next GCA frame ready. This is the first and most important part of
				//exp generation in the whole protocol.
				if (!LTMEmpty) {
					txGraph.begin();
					//Setup new exp for each operation. Exp setup code copied from crawler's DWDM_SCCRS.
					Vertex expMainGeneral = txGraph.addVertex(DBCN.V.general.exp.cn, DBCN.V.general.exp.cn);
					Vertex expMainData = txGraph.addVertex(DBCN.V.LTM.exp.cn, DBCN.V.LTM.exp.cn);
					Vertex expRequirementGeneral = txGraph.addVertex(DBCN.V.general.exp.requirement.cn, DBCN.V.general.exp.requirement.cn);
					Vertex expRequirementData = txGraph.addVertex(DBCN.V.LTM.exp.requirement.cn, DBCN.V.LTM.exp.requirement.cn);
					Vertex expResultGeneral = txGraph.addVertex(DBCN.V.general.exp.result.cn, DBCN.V.general.exp.result.cn);
					Vertex expResultData = txGraph.addVertex(DBCN.V.LTM.exp.result.cn, DBCN.V.LTM.exp.result.cn);
					Vertex expPredictionGeneral = txGraph.addVertex(DBCN.V.general.exp.prediction.cn, DBCN.V.general.exp.prediction.cn);
					Vertex expPredictionData = txGraph.addVertex(DBCN.V.LTM.exp.prediction.cn, DBCN.V.LTM.exp.prediction.cn);
					expMainData.addEdge(DBCN.E.data, expMainGeneral);
					expRequirementGeneral.addEdge(DBCN.E.requirement, expMainData);
					expRequirementData.addEdge(DBCN.E.data, expRequirementGeneral);
					expResultGeneral.addEdge(DBCN.E.result, expMainData);
					expResultData.addEdge(DBCN.E.data, expResultGeneral);
					expPredictionGeneral.addEdge(DBCN.E.prediction, expMainData);
					expPredictionData.addEdge(DBCN.E.data, expPredictionGeneral);
					txGraph.finalizeTask();

					Vertex selectedNewAttentionVertex = LTMTimeRanLow.get(0);

					//Remove the LTM low entry so it will not be selected again, it will return at the next GCA frame as new data.
					txGraph.directQueryExpectVoid("DELETE FROM INDEX:" + LTMTimeRanLowOriginClusterName + " WHERE key = " +
							selectedNewAttentionVertex.getProperty(LP.occurrenceCountPR) + " AND rid = " + selectedNewAttentionVertex.getRid());

					//Commit retry model.
					boolean txError = true;
					int txRetried = 0;
					while (txError) {
						if (txRetried > StartupSoft.dbErrMaxRetryCount) {
							throw new IllegalStateException("Failed to complete transaction after number of retry:"
									+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
						}
						else if (txError) {
							if (txRetried != 0)
								Util.sleep(StartupSoft.dbErrRetrySleepTime);
							txRetried++;
						}
						txGraph.begin();

						expMainGeneral = Util.vReload(expMainGeneral, txGraph);
						selectedNewAttentionVertex = Util.vReload(selectedNewAttentionVertex, txGraph);
						expRequirementData = Util.vReload(expRequirementData, txGraph);
						expResultData = Util.vReload(expResultData, txGraph);
						expPredictionData = Util.vReload(expPredictionData, txGraph);
						expRequirementGeneral = Util.vReload(expRequirementGeneral, txGraph);
						expResultGeneral = Util.vReload(expResultGeneral, txGraph);
						expPredictionGeneral = Util.vReload(expPredictionGeneral, txGraph);

						//Create a new session for it and setup initial values.
						Vertex session = txGraph.addVertex(DBCN.V.general.session.cn, DBCN.V.general.session.cn);
						session.setProperty(LP.nodeUID, session.getRid() + Long.toString(System.currentTimeMillis()) );
						session.setProperty(LP.expState, EXPSTATE.LTMSH);
						session.setProperty(LP.lastPrecisionRate, selectedNewAttentionVertex.getProperty(LP.polyVal));
						session.setProperty(LP.bestPrecisionRate, 0d);

						//Setup new exp for each operation. Exp code copied from crawler's DWDM_SCCRS. We here setup its specialized properties.
						//-This exp will be the exp whose requirement and result will have the same data, the LTMFH exp.
						//ExpState is copied from session for statistical purposes, the session edge means that this exp own the session.
						//Note that this is 'session' (own), not 'parentSession' (created by). This exp own the session as this is the selected
						//new attention point. 'parentSession' edge will NOT be used here, it is meant for all exp created during universal
						//solution generation algorithm for statistical purposes.
						//Note: Only the LTMFH exp have no 'parentSession' edge to anything, it can be used to uniquely identify them (LTMFH).
						//Now uses EXPSTATE.LTMSH to uniquely identify this.
						expMainGeneral.setProperty(LP.expState, session.getProperty(LP.expState));
						expMainGeneral.addEdge(DBCN.E.session, session);

						//Setup initial values, duration, precisionRate as -1 to mark it as not yet implemented. Depth is 0 as he is the first and only.
						expMainGeneral.setProperty(LP.timeStamp, System.currentTimeMillis());
						expMainGeneral.setProperty(LP.precisionRate, -1d);
						expMainGeneral.setProperty(LP.occurrenceCountPR, 0l);
						expMainGeneral.setProperty(LP.polyVal, selectedNewAttentionVertex.getProperty(LP.polyVal) );
						expMainGeneral.setProperty(LP.depth, 0);

						//End of DWDM_SCCRS copied code.

						//Set the requirement, result and prediction by the selected attention point. start time offset is 0 as the are the head (first).
						Edge expRequirementDataEdge = expRequirementData.addEdge(DBCN.E.requirement, selectedNewAttentionVertex);
						expRequirementDataEdge.setProperty(LP.startTimeOffset, 0l);
						//Result will later be replaced with real actual result.
						Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, selectedNewAttentionVertex);
						expResultDataEdge.setProperty(LP.startTimeOffset, 0l);
						Edge expPredictionDataEdge = expPredictionData.addEdge(DBCN.E.prediction, selectedNewAttentionVertex);
						expPredictionDataEdge.setProperty(LP.startTimeOffset, 0l);

						//He has no choice but to simply copy from the source for the polyVal.
						expRequirementGeneral.setProperty(LP.polyVal, selectedNewAttentionVertex.getProperty(LP.polyVal));
						expResultGeneral.setProperty(LP.polyVal, selectedNewAttentionVertex.getProperty(LP.polyVal));
						expPredictionGeneral.setProperty(LP.polyVal, selectedNewAttentionVertex.getProperty(LP.polyVal));

						//Get its GCA frame.
						Vertex GCAMainGeneralVertex = Util.traverseGetGCAMainGeneral(selectedNewAttentionVertex, txGraph);

						//Duration is the time that this exp takes to finish, for LTM, its duration is 1 frame only as we cannot possibly
						//deduce any hidden message of when it began, but the 1 frame distance is always true as it really does happened 'just now'.
						expMainGeneral.setProperty(LP.duration, Util.traverseGCAMainCalculateDuration(GCAMainGeneralVertex, 1, txGraph));

						//NOTE that LTMFH operation doesn't have occurrence edge as when it is being selected, it will go into LTMSH operation,
						//over there a new exp will be created and encapsulate him, by then it will create proper occurrence edge.
						//This is due to LTM to LTM, exp to exp occurrence edge only, no exp to LTM or reverse cross breed occurrence allowed,
						//so RSG will work correctly as this is his contract and expectation.

						txError = txGraph.finalizeTask(true);
					}

					//Start a new transaction to avoid retry induced data inconsistency at GCA site. To guarantee idempotent.
					txGraph.begin();
					//Forward it to GCA, then GCA will forward it back here and log in its credentials, then continue on LTMSH operation,
					//then normal operations. Let it comes in here as exp so he can get a chance to be repicked as new attention.
					STMClient.expAddToGCAQueue(expMainGeneral, txGraph);
					txGraph.finalizeTask();

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "At 'select new attention', "
							+ "depth 0 operation (exp encapsulate LTM) success. " + "LTM Rid: " + selectedNewAttentionVertex);

					//Doesnt have to forward to any other operation, as the process had already took place, this is why we received the LTM
					//in the WM in the first place. LTMFH operation only requires you to setup an exp to encapsulate the selected LTM and
					//setup its requirement, process and result accordingly.
				}

				//Nothing interesting in LTM, we will select new attention point from exp.
				//-After LTM has been improvised with exp, we choose new attention point now. They will come in at the next frame, thus
				//we will not consider them yet.
				Vertex selectedNewAttentionVertex = null;
				//Note that this session can be either previous or new session, depending on usage, but never both or change state (previous to new).
				Vertex session = null;

				//Only LTMFH operation doesn't need to run this algorithm, LTMSH and all other must run this.
				boolean runUniversalSolutionGenerationAlgorithm = false;

				boolean preferUniqueRoute = false;
				if (globalDist > 25d && globalDist < 75d)
					preferUniqueRoute = true;

				//-Begin select new attention point. Read 30-6-16 for design details.
				ArrayList<Vertex> precisionVertexList = new ArrayList<Vertex>();
				ArrayList<Double> polyVals = new ArrayList<Double>();
				ArrayList<Integer> depths = new ArrayList<Integer>();
				ArrayList<Long> timeRans = new ArrayList<Long>();

				/*
				 * Using clustered index logic, seek for each cluster, then aggregate all of their data to form a better view of reality
				 * closer to real time, not just FIFO but loop through.
				 */
				ArrayList<String> precisionRateIndexClassClusterNameList = Util.WMSTMGetClusterName(DBCN.index.WMPrecisionRateIndex.cn, txGraph);
				//Seek usual route where timeRan is lower than normal, polyVal is close to globalDist and depth is nominal but precision high.
				if (preferUniqueRoute) {
					for (String indexClassName : precisionRateIndexClassClusterNameList) {
						precisionVertexList.addAll( txGraph.directQueryExpectVertex("select expand(rid) from (SELECT FROM INDEX:" +
								indexClassName + " WHERE key < 25 limit 100)") );
					}
				}
				//Seek unique route where timeRan is lower than normal, polyVal is close to globalDist and depth is nominal but precision low.
				else {
					for (String indexClassName : precisionRateIndexClassClusterNameList) {
						precisionVertexList.addAll( txGraph.directQueryExpectVertex("select expand(rid) from (SELECT FROM INDEX:" +
								indexClassName + " WHERE key > 75 limit 100)") );
					}
				}

				//If it have no record found, cannot continue on as the following operations requires it to determine next attention point.
				//Just skip it if not available and do nothing.
				if (precisionVertexList.isEmpty())
					continue;

				//Extract their polyVals and depths.
				for (Vertex currentPrecisionVertex : precisionVertexList) {
					polyVals.add(currentPrecisionVertex.getProperty(LP.polyVal));
					depths.add(currentPrecisionVertex.getProperty(LP.depth));
					timeRans.add(currentPrecisionVertex.getProperty(LP.occurrenceCountPR));
				}
				if (!( precisionVertexList.size() == polyVals.size() && precisionVertexList.size() == depths.size()
						&& precisionVertexList.size() == timeRans.size() )) {
					throw new IllegalStateException("List size not equal, precisionSize: " + precisionVertexList.size()
					+ " depthSize: " + depths.size() + " timeRanSize: " + timeRans.size());
				}

				//Reorder the solutions based on their distance to globalDist (relevancy against current global demand).
				//Double is the distance, string is the concerned rid. Only polyVal need this bonus step.
				ArrayList<Double> polyValDistance = new ArrayList<Double>();
				for (int i=0; i<polyVals.size(); i++) {
					polyValDistance.add( Math.abs(globalDist - polyVals.get(i)) );
				}
				//Get the scores of each vertex based on index, their index is based on the precisionLowRid array's ordering.
				ArrayList<Integer> polyValDistanceOrderedIndexScore = Util.sortGetIndex(polyValDistance, true);
				ArrayList<Integer> depthOrderedIndexScore = Util.sortGetIndex(depths, false);
				ArrayList<Integer> timeRanOrderedIndexScore = Util.sortGetIndex(timeRans, true);

				//Arrange the solution set to get a list of polyVal distance shortest, timeRan least leading list.
				ArrayList<Integer> polyValTimeRanMixedScore = new ArrayList<Integer>();
				for (int i=0; i<polyValDistanceOrderedIndexScore.size(); i++) {
					int score = polyValDistanceOrderedIndexScore.indexOf(i) + timeRanOrderedIndexScore.indexOf(i);
					polyValTimeRanMixedScore.add(score);
				}
				//Sort the scores and return us the index of the best solution of mixture of polyVal distance and timeRan.
				ArrayList<Integer> finalPolyValDistanceAndTimeRanMixedIndexScore = Util.sortGetIndex(polyValTimeRanMixedScore, true);

				//Test the solution list to see if the preceding solution has lower depth but share same timeRan count.
				for (int i=0; i<finalPolyValDistanceAndTimeRanMixedIndexScore.size(); i++) {
					//TODO: Check whether there is any vertex that has same score but lower depth, if so select it.
				}

				/*
				 * Order by polyVal distance, then order by timeRan least first, then resolve by depth,
				 * if they share same timeRan, then watch by depth, the lowest depth win.
				 */
				long bestTimeRan = -1;
				int selectedLowestDepth = -1;
				int selectedIndex = -1;

				for (int i=0; i<finalPolyValDistanceAndTimeRanMixedIndexScore.size(); i++) {
					int finalSortedIndex = finalPolyValDistanceAndTimeRanMixedIndexScore.get(i);
					if (selectedIndex == -1) {
						//These are the original data, synchronized with rid, depths and timeRan during fetch.
						//Best timeRan is essentially the first element of the sorted timeRan. Selected lowest depth is the first element
						//of the best solution list, and the selected index is i, which is 0, the head of the element as well.
						bestTimeRan = timeRans.get(finalSortedIndex);
						selectedLowestDepth = depths.get(finalSortedIndex);
						selectedIndex = i;
						continue;
					}

					//Compare with the current index's data, see if they are better or not.
					int currentDepth = depths.get(finalSortedIndex);
					long currentTimeRan = timeRans.get(finalSortedIndex);

					//They must have equal timeRan in order to prove that they are high leveled but in conflict, then check whether
					//they share the same depth, choose the one with the lowest depth as they are more unique than anything else,
					//a solution that is less popular than common mind and yet special enough to have so less timeRan.
					if (currentTimeRan == bestTimeRan) {
						if (currentDepth < selectedLowestDepth) {
							selectedLowestDepth = currentDepth;
							selectedIndex = i;
						}
					}
				}

				//Must convert it to the final index, as the selectedIndex itself is the index of this list instead of the real index of
				//the original list, get the final actual index that corresponds to the original ridList ordering by getting
				//it from the list below.
				int finalSelectedIndex = finalPolyValDistanceAndTimeRanMixedIndexScore.get(selectedIndex);

				selectedNewAttentionVertex = precisionVertexList.get(finalSelectedIndex);
				//-End of select new attention point.

				//Setup new exp for each operation. Exp setup code copied from crawler's DWDM_SCCRS.
				//Setup once so it can be share across multiple logic branch below.
				txGraph.begin();
				Vertex expMainGeneral = txGraph.addVertex(DBCN.V.general.exp.cn, DBCN.V.general.exp.cn);
				Vertex expMainData = txGraph.addVertex(DBCN.V.LTM.exp.cn, DBCN.V.LTM.exp.cn);
				Vertex expRequirementGeneral = txGraph.addVertex(DBCN.V.general.exp.requirement.cn, DBCN.V.general.exp.requirement.cn);
				Vertex expRequirementData = txGraph.addVertex(DBCN.V.LTM.exp.requirement.cn, DBCN.V.LTM.exp.requirement.cn);
				Vertex expResultGeneral = txGraph.addVertex(DBCN.V.general.exp.result.cn, DBCN.V.general.exp.result.cn);
				Vertex expResultData = txGraph.addVertex(DBCN.V.LTM.exp.result.cn, DBCN.V.LTM.exp.result.cn);
				Vertex expPredictionGeneral = txGraph.addVertex(DBCN.V.general.exp.prediction.cn, DBCN.V.general.exp.prediction.cn);
				Vertex expPredictionData = txGraph.addVertex(DBCN.V.LTM.exp.prediction.cn, DBCN.V.LTM.exp.prediction.cn);
				expMainData.addEdge(DBCN.E.data, expMainGeneral);
				expRequirementGeneral.addEdge(DBCN.E.requirement, expMainData);
				expRequirementData.addEdge(DBCN.E.data, expRequirementGeneral);
				expResultGeneral.addEdge(DBCN.E.result, expMainData);
				expResultData.addEdge(DBCN.E.data, expResultGeneral);
				expPredictionGeneral.addEdge(DBCN.E.prediction, expMainData);
				expPredictionData.addEdge(DBCN.E.data, expPredictionGeneral);
				txGraph.finalizeTask();

				//Successfully selected a new attention vertex, now we decide whether he is a LTMSH operation exp vertex (just born and comes
				//from LTMFH, initial exp encapsulation) OR a half way recalled/invoked vertex OR a veteran vertex that had already been
				//selected before. 3 condition sharp only.
				//Check whether the selected vertex has a session, if has then use it (meaning it had been selected as attention point before),
				//else create a new session (on the way generated vertex, never been selected before).
				//Note that all the selectedNewAttentionVertex's rid here is expMainGeneral.
				//If exception occurs means it doesn't have any previous session edges, we will create a new one for him.
				try {
					//Should throw exception right here if session doesn't exist.
					session = Util.traverseOnce(selectedNewAttentionVertex, Direction.OUT, DBCN.E.session);

					//Check previous status and progress. If session exist, it means this is not the first time this vertex is selected as
					//new attention point.
					int previousExpState = session.getProperty(LP.expState);

					//LTMSH operation is just run the selected exp once, where it will bind itself to a current GCA frame, then we fetch
					//both LTMFH and LTMSH's GCA frame and compare their similarity, those similar point will be set into the newly
					//created exp result, its requirement and process remain unchanged (simply copy from depth 1). This newly created exp is
					//depth 2, and is eligible for any further universal solution generation algorithm.
					boolean runLTMSHOperation = previousExpState == EXPSTATE.LTMSH ? true : false;

					if (runLTMSHOperation) {
						if (!selectedNewAttentionVertex.getCName().equals(DBCN.V.general.exp.cn))
							throw new IllegalStateException("Expecting type: " + DBCN.V.general.exp.cn + ", but get: "
									+ selectedNewAttentionVertex.getCName());

						//Fetch all the previous data needed.
						//Attempt to rerun it once using the universal solution generation algorithm.
						//Note that its result will be setup by the WM solution tree extraction procedure, it will uniquely identify that
						//this is LTMSH special operation and setup the exp properly for us.

						//Copy all the data from the original LTMFH exp to the newly created LTMSH exp. LTMSH exp will be forwarded to
						//the universal solution generation algorithm, and its result will be modified as this is special condition.
						Vertex fetchedExpMainData = Util.traverseOnce(selectedNewAttentionVertex, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.cn);
						Vertex fetchedExpRequirementGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.requirement, DBCN.V.general.exp.requirement.cn);
						Vertex fetchedExpRequirementData = Util.traverseOnce(fetchedExpRequirementGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.requirement.cn);
						ArrayList<Vertex> fetchedRequirements = Util.traverse(fetchedExpRequirementData, Direction.OUT, DBCN.E.requirement);
						ArrayList<Long> fetchedRequirementStartTimeOffset = Util.traverseGetStartTimeOffset(
								fetchedExpRequirementData, Direction.OUT, DBCN.E.requirement);

						Vertex fetchedExpResultGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
						Vertex fetchedExpResultData = Util.traverseOnce(fetchedExpResultGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
						ArrayList<Vertex> fetchedResults = Util.traverse(fetchedExpResultData, Direction.OUT, DBCN.E.result);
						ArrayList<Long> fetchedResulstStartTimeOffset = Util.traverseGetStartTimeOffset(
								fetchedExpResultData, Direction.OUT, DBCN.E.result);

						assert fetchedRequirements.size() != 0 : fetchedExpRequirementData;
						assert fetchedRequirementStartTimeOffset.size() != 0 : fetchedExpRequirementData;
						assert fetchedResults.size() != 0 : fetchedExpResultData;
						assert fetchedResulstStartTimeOffset.size() != 0 : fetchedExpResultData;

						//Setup initial properties.
						//TODO: Should break down this into smaller transaction as it uses many data.
						//Commit retry model.
						boolean txError = true;
						int txRetried = 0;
						while (txError) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;
							}
							txGraph.begin();

							expMainGeneral = Util.vReload(expMainGeneral, txGraph);
							session = Util.vReload(session, txGraph);
							selectedNewAttentionVertex = Util.vReload(selectedNewAttentionVertex, txGraph);
							expRequirementGeneral = Util.vReload(expRequirementGeneral, txGraph);
							expResultGeneral = Util.vReload(expResultGeneral, txGraph);
							expPredictionGeneral = Util.vReload(expPredictionGeneral, txGraph);

							//Update the session state from LTMSH to FIRSTTIME so it will no longer be accidentally ran as LTMSH operation.
							session.setProperty(LP.expState, EXPSTATE.FIRSTTIME);

							//Setup new exp for each operation. Exp code copied from crawler's DWDM_SCCRS. We here setup its specialized properties.
							expMainGeneral.setProperty(LP.expState, EXPSTATE.FIRSTTIME);

							//This vertex doesn't own any session yet.
							//We now have parentSession and will not be treated as LTMSH operation anymore.
							expMainGeneral.addEdge(DBCN.E.parentSession, session);

							//Add occurrence edges as it is similar and deprive from it.
							//Add occurrence edge to the selected vertex as at the point it got forwarded to crawler and back to form the
							//solution tree, the given head to the crawler's STISS will not be recreated with proper 'occurrence' edge,
							//so we must do it manually here.
							expMainGeneral.addEdge(DBCN.E.occurrence, selectedNewAttentionVertex);
							expRequirementGeneral.addEdge(DBCN.E.occurrence, fetchedExpRequirementGeneral);
							expResultGeneral.addEdge(DBCN.E.occurrence, fetchedExpResultGeneral);

							//Setup initial values, duration, precisionRate and depth are -1 to mark it as not yet implemented.
							//It will be put in later after its process is complete.
							//Duration is the time that this exp takes to finish, precisionRate is the similarity between prediction and actual result.
							//Depth means the depth of exp, exp can be nested, thus deeper the depth, more complex the action is. Will be set at the
							//final posting phrase, where it will update the depth value accordingly.
							expMainGeneral.setProperty(LP.timeStamp, System.currentTimeMillis());
							expMainGeneral.setProperty(LP.duration, -1);
							expMainGeneral.setProperty(LP.precisionRate, -1d);
							expMainGeneral.setProperty(LP.occurrenceCountPR, 0l);
							expMainGeneral.setProperty(LP.depth, -1);
							expMainGeneral.setProperty(LP.polyVal, selectedNewAttentionVertex.getProperty(LP.polyVal));

							//Should inherit directly from the parent for their polyVal.
							expRequirementGeneral.setProperty(LP.polyVal, fetchedExpRequirementGeneral.getProperty(LP.polyVal));
							expResultGeneral.setProperty(LP.polyVal, fetchedExpResultGeneral.getProperty(LP.polyVal));
							expPredictionGeneral.setProperty(LP.polyVal, fetchedExpResultGeneral.getProperty(LP.polyVal));

							//End of DWDM_SCCRS copied code.
							txError = txGraph.finalizeTask(true);
						}

						//TODO: Group some of them together if potential success rate is high, but we can hardly calculate that for now...
						for (int i=0; i<fetchedRequirements.size(); i++) {
							//Commit retry model.
							boolean txError2 = true;
							int txRetried2 = 0;
							while (txError2) {
								if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
									throw new IllegalStateException("Failed to complete transaction after number of retry:"
											+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
								}
								else if (txError2) {
									if (txRetried2 != 0) {
										Util.sleep(StartupSoft.dbErrRetrySleepTime);
									}
									txRetried2++;
								}
								txGraph.begin();
								expRequirementData = Util.vReload(expRequirementData, txGraph);
								Vertex fetchedRequirementReloaded = Util.vReload(fetchedRequirements.get(i), txGraph);

								Edge expRequirementDataEdge = expRequirementData.addEdge(DBCN.E.requirement, fetchedRequirementReloaded);
								expRequirementDataEdge.setProperty(LP.startTimeOffset, fetchedRequirementStartTimeOffset.get(i));

								txError2 = txGraph.finalizeTask(true);
							}
						}
						for (int i=0; i<fetchedResults.size(); i++) {
							//Commit retry model.
							boolean txError2 = true;
							int txRetried2 = 0;
							while (txError2) {
								if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
									throw new IllegalStateException("Failed to complete transaction after number of retry:"
											+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
								}
								else if (txError2) {
									if (txRetried2 != 0) {
										Util.sleep(StartupSoft.dbErrRetrySleepTime);
									}
									txRetried2++;
								}
								txGraph.begin();
								expResultData = Util.vReload(expResultData, txGraph);
								expPredictionData = Util.vReload(expPredictionData, txGraph);
								Vertex fetchedResultReloaded = Util.vReload(fetchedResults.get(i), txGraph);

								//Result will later be replaced with real actual result.
								Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, fetchedResultReloaded);
								expResultDataEdge.setProperty(LP.startTimeOffset, fetchedResulstStartTimeOffset.get(i));
								Edge expPredictionDataEdge = expPredictionData.addEdge(DBCN.E.prediction, fetchedResultReloaded);
								expPredictionDataEdge.setProperty(LP.startTimeOffset, fetchedResulstStartTimeOffset.get(i));
								txError2 = txGraph.finalizeTask(true);
							}
						}

						runUniversalSolutionGenerationAlgorithm = true;

						StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
								"At 'select new attention', Depth 1 first half success.");
					}
					//End of special LTMSH operation first half, second half lies at WM solution tree extraction procedure to setup result.

					//It is normal exp with previous experience of being selected as attention point.
					else {
						//Get the greenList, if not available, means this is the second time this exp been selected as attention point, and no
						//experiment had been conducted yet as the first time being selected its task will be simply just try to recreate itself,
						//we will follow the expState (command) to do the first experiment, then at the next go if this is being selected as
						//attention point again, the attention decider will create the list for us with those modification we will be doing here.
						//Note: banList as well. And if they one or both is absent, it is fine.
						ArrayList<String> greenList = null;
						ArrayList<String> banList = null;

						boolean greenListAvailable = true;
						boolean banListAvailable = true;

						byte[] greenListByteArr = session.getProperty(LP.greenList);
						if (greenListByteArr == null) {
							greenListAvailable = false;
							greenList = new ArrayList<String>();
						}
						else
							greenList = Util.kryoDeserialize(greenListByteArr, ArrayList.class);

						byte[] banListByteArr = session.getProperty(LP.banList);
						if (banListByteArr == null) {
							banListAvailable = false;
							banList = new ArrayList<String>();
						}
						else
							banList = Util.kryoDeserialize(banListByteArr, ArrayList.class);

						//Update the green and ban list from the previous operation, after the operation is done, the session's
						//lastPrecisionRate property will be updated with the latest precisionRate, if the decision rate is
						//better than the best precision rate, means the modification is successful, then store the changes
						//accordingly, else will rollback those changes.
						double lastPrecisionRate = session.getProperty(LP.lastPrecisionRate);
						double bestPrecisionRate = session.getProperty(LP.bestPrecisionRate);

						//If the last modification is better than the best modification, then set it as the best modification.
						if (lastPrecisionRate > bestPrecisionRate) {
							//Commit retry model.
							boolean txError = true;
							int txRetried = 0;
							while (txError) {
								if (txRetried > StartupSoft.dbErrMaxRetryCount) {
									throw new IllegalStateException("Failed to complete transaction after number of retry:"
											+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
								}
								else if (txError) {
									if (txRetried != 0)
										Util.sleep(StartupSoft.dbErrRetrySleepTime);
									txRetried++;
								}
								txGraph.begin();

								session = Util.vReload(session, txGraph);

								session.setProperty(LP.bestPrecisionRate, lastPrecisionRate);
								session.setProperty(LP.bestPrecisionRateRid, selectedNewAttentionVertex.getRid());

								txError = txGraph.finalizeTask(true);
							}

							ArrayList<Vertex> previouslyModifiedVertex = Util.traverse(session, Direction.IN, DBCN.E.modDuringSession);
							ArrayList<String> previouslyModifiedVertexRids = Util.vertexToRid(previouslyModifiedVertex);

							//Save those modification into our ban and green list.
							//If previous operation is add then we verify the modification by adding it to the greenList.
							if (previousExpState == EXPSTATE.ADD) {
								for (String s : previouslyModifiedVertexRids)
									greenList.add(s);
							}
							//If reduce then add to ban list.
							else if (previousExpState == EXPSTATE.REDUCE) {
								for (String s : previouslyModifiedVertexRids)
									banList.add(s);
							}
						}

						//Last modification is not the best modification, rollback its operation by doing nothing (don't record it).
						else
							;

						//Clean up all the previous modifications.
						try {
							Iterable<Edge> modVertexEdges = Util.traverseGetEdges(session, Direction.IN, DBCN.E.modDuringSession);
							for (Edge e : modVertexEdges) {
								//Commit retry model.
								boolean txError = true;
								int txRetried = 0;
								while (txError) {
									if (txRetried > StartupSoft.dbErrMaxRetryCount) {
										throw new IllegalStateException("Failed to complete transaction after number of retry:"
												+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
									}
									else if (txError) {
										if (txRetried != 0)
											Util.sleep(StartupSoft.dbErrRetrySleepTime);
										txRetried++;
									}
									txGraph.begin();

									e.remove();

									txError = txGraph.finalizeTask(true);
								}
							}
						}
						catch (OSchemaException e) {
							;	//Exception means there was previously no modification, never mind then.
						}

						//Traverse and get to the actual data.
						Vertex fetchedExpMainData = Util.traverseOnce(selectedNewAttentionVertex, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.cn);
						Vertex fetchedExpRequirementGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.requirement, DBCN.V.general.exp.requirement.cn);
						Vertex fetchedExpRequirementData = Util.traverseOnce(fetchedExpRequirementGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.requirement.cn);
						ArrayList<Vertex> fetchedRequirements = Util.traverse(fetchedExpRequirementData, Direction.OUT, DBCN.E.requirement);
						ArrayList<Long> fetchedRequirementStartTimeOffset = Util.traverseGetStartTimeOffset(
								fetchedExpRequirementData, Direction.OUT, DBCN.E.requirement);
						ArrayList<String> fetchedRequirementRids = Util.vertexToRid(fetchedRequirements);
						ArrayList<String> filteredRequirements = new ArrayList<String>();

						Vertex fetchedExpResultGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
						Vertex fetchedExpResultData = Util.traverseOnce(fetchedExpResultGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
						ArrayList<Vertex> fetchedResults = Util.traverse(fetchedExpResultData, Direction.OUT, DBCN.E.result);
						ArrayList<Long> fetchedResultStartTimeOffset = Util.traverseGetStartTimeOffset(
								fetchedExpResultData, Direction.OUT, DBCN.E.result);
						ArrayList<String> fetchedResultRids = Util.vertexToRid(fetchedResults);
						ArrayList<String> filteredResults = new ArrayList<String>();

						//Exclude all the banned element from the original requirement.
						if (banListAvailable) {
							for (String requirementRid : fetchedRequirementRids) {
								boolean banned = false;
								for (String bannedRid : banList) {
									//If the rid is banned.
									if (bannedRid.equals(requirementRid)) {
										banned = true;
										break;
									}
								}

								if (!banned)
									filteredRequirements.add(requirementRid);
							}
							for (String resultRid : fetchedResultRids) {
								boolean banned = false;
								for (String bannedRid : banList) {
									//If the rid is banned.
									if (bannedRid.equals(resultRid)) {
										banned = true;
										break;
									}
								}

								if (!banned)
									filteredResults.add(resultRid);
							}
						}
						else {
							filteredRequirements = fetchedRequirementRids;
							filteredResults = fetchedResultRids;
						}

						//-Note that banList and greenList will not have common element.
						/*
						 * Design note:
						 * When selecting element to be changed prioritize original result first. Must classify all of their elements
						 * into either banned or green list first, after that consider GCA at that moment in order to choose some
						 * external influences into the add/delete operation. If those original results are not yet being classified into
						 * either of the lists, then they will be marked as default green meaning that they will be included into the
						 * newly created result.
						 *
						 * Reduce first, to ensure all the result are precise, then ADD more to cover more field.
						 */

						ArrayList<String> selectedModVertexRid = new ArrayList<String>();
						//Add means add more result to the greenList in attempt to boost precision.
						int selectedExpState = previousExpState;
						//It had been selected once as it already have a session and flag as first time, now begin to reduce its
						//element to improve precision by changing its state to to reduce.
						if (selectedExpState == EXPSTATE.FIRSTTIME)
							selectedExpState = EXPSTATE.REDUCE;

						//Reduce until there is no more.
						if (selectedExpState == EXPSTATE.REDUCE) {
							//Reduce prioritize over original results.
							//The filteredResults list stores all the remaining original results that had not been banned, compare
							//them against greenList to find the remaining original results that had not been modified before.
							//If particular element had been modified before, it will go either into green or ban list.

							//For REDUCE use only, as unprocessed original result only possible during REDUCE phrase, add them
							//into the green list but not upload it to the session, this is part of the contract to treat unprocessed
							//original element as valid.
							for (String filteredRid : filteredResults) {
								//If the rid is not inside greenList, means it had never been processed before.
								boolean notProcessedBefore = true;
								if (Util.equalAny(filteredRid, greenList))
									notProcessedBefore = false;
								if (notProcessedBefore) {
									//Only intend to make 1 modification at a time.
									Vertex selectedModVertex = Util.ridToVertex(filteredRid, txGraph);

									//Commit retry model.
									boolean txError = true;
									int txRetried = 0;
									while (txError) {
										if (txRetried > StartupSoft.dbErrMaxRetryCount) {
											throw new IllegalStateException("Failed to complete transaction after number of retry:"
													+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
										}
										else if (txError) {
											if (txRetried != 0) {
												Util.sleep(StartupSoft.dbErrRetrySleepTime);
											}
											txRetried++;
										}
										txGraph.begin();

										selectedModVertex = Util.vReload(selectedModVertex, txGraph);
										session = Util.vReload(session, txGraph);

										selectedModVertex.addEdge(DBCN.E.modDuringSession, session);

										txError = txGraph.finalizeTask(true);
									}

									selectedModVertexRid.add(filteredRid);
									greenList.add(filteredRid);

									break;
								}
							}
							//If have reduced all original results, begin adding external data.
							if (selectedModVertexRid.isEmpty())
								selectedExpState = EXPSTATE.ADD;
						}

						//Add new vertex to the exp to diversify its view. Crucial generation feat.
						if (selectedExpState == EXPSTATE.ADD) {
							if ( (double)selectedNewAttentionVertex.getProperty(LP.precisionRate) >= 90d) {
								//If the precision is already very good, will not try to modify anything.
								selectedExpState = EXPSTATE.IDLE;
							}

							//TODO: Not yet supported, dunno what to add.
//							else {
//								//Original requirement already by default included unless banned, thus we will only consider external GCA input here.
//								//Select the unique data (those whom doesn't meet reality) with some links to this as priority.
//								//Second is add non unique but similar.
//								ArrayList<Vertex> failedConvergenceList = getPaRcFailedConvergenceVertex();
//								if (!failedConvergenceList.isEmpty()) {
//									//If not empty, means seek new addition from here.
//									//It is supposed to have only 1 element, more than 1 is currently not supported.
//									Vertex failedConvergence = failedConvergenceList.get(0);
//
//									//Traverse and get to the failed detail. Add them as our key.
//									ArrayList<Vertex> dataGeneralVertexes = Util.traverse(failedConvergence, Direction.OUT, DBCN.E.data);
//									for (Vertex v : dataGeneralVertexes) {
//										greenList.add( v.getRid() );
//									}
//								}
//								else {
//									//Else seek addition from the big GCA itself.
//									//This is undesirable as the scope is too great. But try anyway.
//									//Seek based on most probable linked. Aka the thing with great similarity but somehow not yet as
//									//part of it.
//									//TODO;
//								}
//							}
						}

						if (selectedExpState == EXPSTATE.IDLE) {
							//IDLE means should not do any modification, may because its precision is high, or it keeps fluncates even though
							//we didn't change anything and we want to document the fluncation rate to find the cause. Just do nothing now.
							//If last run is idle as well, means we may be outdated, so run REPLACE once to update to newer data.
							if (previousExpState == EXPSTATE.IDLE)
								selectedExpState = EXPSTATE.REPLACE;
						}

						//TODO: Not yet implemented.
						if (selectedExpState == EXPSTATE.REPLACE) {
							//If nothing worth changing and all things is up to date, set it to IDLE to signify nothing was done.
							selectedExpState = EXPSTATE.IDLE;
						}

						//Commit retry model.
						boolean txError = true;
						int txRetried = 0;
						while (txError) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;

							}
							txGraph.begin();

							expMainGeneral = Util.vReload(expMainGeneral, txGraph);
							session = Util.vReload(session, txGraph);
							selectedNewAttentionVertex = Util.vReload(selectedNewAttentionVertex, txGraph);
							expRequirementGeneral = Util.vReload(expRequirementGeneral, txGraph);
							expResultGeneral = Util.vReload(expResultGeneral, txGraph);
							expPredictionGeneral = Util.vReload(expPredictionGeneral, txGraph);

							//Setup new exp for each operation. Exp code copied from crawler's DWDM_SCCRS. We here setup its specialized properties.
							expMainGeneral.setProperty(LP.expState, selectedExpState);
							//This new expMainGeneral doesn't own the original session of the selected attention point, so only 'parentSession'
							//is allowed and for statistical purposes.
							expMainGeneral.addEdge(DBCN.E.parentSession, session);

							//Add occurrence edges as it is similar and deprive from it.
							//Add occurrence edge to the selected vertex as at the point it got forwarded to crawler and back to form the
							//solution tree, the given head to the crawler's STISS will not be recreated with proper 'occurrence' edge,
							//so must do it manually here.
							expMainGeneral.addEdge(DBCN.E.occurrence, selectedNewAttentionVertex);
							expRequirementGeneral.addEdge(DBCN.E.occurrence, fetchedExpRequirementGeneral);
							expResultGeneral.addEdge(DBCN.E.occurrence, fetchedExpResultGeneral);

							//Setup initial values, duration, precisionRate and depth are -1 to mark it as not yet implemented.
							//It will be put in later after its process is complete.
							//Duration is the time that this exp takes to finish, precisionRate is the similarity between prediction and actual result.
							//Depth means the depth of exp, exp can be nested, thus deeper the depth, more complex the action is. Will be set at the
							//final posting phrase, where it will update the depth value accordingly.
							expMainGeneral.setProperty(LP.timeStamp, System.currentTimeMillis());
							expMainGeneral.setProperty(LP.duration, -1);
							expMainGeneral.setProperty(LP.precisionRate, -1d);
							expMainGeneral.setProperty(LP.occurrenceCountPR, 0l);
							expMainGeneral.setProperty(LP.depth, -1);
							expMainGeneral.setProperty(LP.polyVal, selectedNewAttentionVertex.getProperty(LP.polyVal));

							//Should inherit directly from the parent for their polyVal.
							expRequirementGeneral.setProperty(LP.polyVal, fetchedExpRequirementGeneral.getProperty(LP.polyVal));
							expResultGeneral.setProperty(LP.polyVal, fetchedExpResultGeneral.getProperty(LP.polyVal));
							expPredictionGeneral.setProperty(LP.polyVal, fetchedExpResultGeneral.getProperty(LP.polyVal));
							//End of DWDM_SCCRS copied code.

							//Update original session.
							session.setProperty(LP.expState, selectedExpState);
							session.setProperty(LP.greenList, Util.kryoSerialize(greenList));
							session.setProperty(LP.banList, Util.kryoSerialize(banList));

							txError = txGraph.finalizeTask(true);
						}

						//Setup the all the requirement, process and result by copying original attention point's requirement and process.
						//result will be the finalGreenList. Also copied the start time offset element from the edge for precise timing.
						for (int i=0; i<fetchedRequirementRids.size(); i++) {
							for (String filteredRid : filteredRequirements) {
								if (fetchedRequirementRids.get(i).equals(filteredRid)) {
									//Commit retry model.
									boolean txError2 = true;
									int txRetried2 = 0;
									while (txError2) {
										if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
											throw new IllegalStateException("Failed to complete transaction after number of retry:"
													+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
										}
										else if (txError2) {
											if (txRetried2 != 0)
												Util.sleep(StartupSoft.dbErrRetrySleepTime);
											txRetried2++;
										}
										txGraph.begin();

										expRequirementData = Util.vReload(expRequirementData, txGraph);

										Edge expRequirementDataEdge = expRequirementData.addEdge(
												DBCN.E.requirement, Util.ridToVertex(filteredRid, txGraph));
										expRequirementDataEdge.setProperty(LP.startTimeOffset, fetchedRequirementStartTimeOffset.get(i));

										txError2 = txGraph.finalizeTask(true);
									}
								}
							}
						}

						if (selectedExpState == EXPSTATE.REDUCE) {
							boolean reducedAll = true;
							for (String filteredRid : filteredResults) {
								for (String selectedModRid : selectedModVertexRid) {
									//Only add the rid to result if it is not any of the selected to be REDUCEd vertex.
									if (filteredRid != selectedModRid) {
										//Find the original result's start time offset timing variable.
										int originalIndex = -1;
										for (int i=0; i<fetchedResultRids.size(); i++) {
											if (filteredRid.equals(fetchedResultRids.get(i))) {
												originalIndex = i;
												break;
											}
										}

										//Commit retry model.
										boolean txError2 = true;
										int txRetried2 = 0;
										while (txError2) {
											if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
												throw new IllegalStateException("Failed to complete transaction after number of retry:"
														+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
											}
											else if (txError2) {
												if (txRetried2 != 0)
													Util.sleep(StartupSoft.dbErrRetrySleepTime);
												txRetried2++;
											}
											txGraph.begin();

											expResultData = Util.vReload(expResultData, txGraph);

											Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, Util.ridToVertex(filteredRid, txGraph));
											expResultDataEdge.setProperty(LP.startTimeOffset, fetchedResultStartTimeOffset.get(originalIndex));

											txError2 = txGraph.finalizeTask(true);
										}
										reducedAll = false;
									}
								}
							}

							/*
							 * Temporary doesn't want to deal with empty exp result, or let any of them go into real application.
							 * This is due to it will causes myraid of error, mainly at all places that deal with secondary convergence,
							 * where it expect it to have 'data' edge to the requirement, but as it is empty, it yield no edges and causes
							 * many NPE.
							 * Now we simply randomly add at least 1 element.
							 */
							if (reducedAll) {
								//Commit retry model.
								boolean txError2 = true;
								int txRetried2 = 0;
								while (txError2) {
									if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
										throw new IllegalStateException("Failed to complete transaction after number of retry:"
												+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
									}
									else if (txError2) {
										if (txRetried2 != 0)
											Util.sleep(StartupSoft.dbErrRetrySleepTime);
										txRetried2++;
									}
									txGraph.begin();

									expResultData = Util.vReload(expResultData, txGraph);

									Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, Util.ridToVertex(filteredResults.get(0), txGraph));
									expResultDataEdge.setProperty(LP.startTimeOffset, fetchedResultStartTimeOffset.get(0));

									txError2 = txGraph.finalizeTask(true);
								}
							}
						}

						//Operation ADD.
						else if (selectedExpState == EXPSTATE.ADD) {
							//Add all original filtered vertexes.
							for (String filteredRid : filteredResults) {
								//Find the original result's start time offset timing variable.
								int originalIndex = -1;
								for (int i=0; i<fetchedResultRids.size(); i++) {
									if (filteredRid.equals(fetchedResultRids.get(i))) {
										originalIndex = i;
										break;
									}
								}

								//Commit retry model.
								boolean txError2 = true;
								int txRetried2 = 0;
								while (txError2) {
									if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
										throw new IllegalStateException("Failed to complete transaction after number of retry:"
												+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
									}
									else if (txError2) {
										if (txRetried2 != 0)
											Util.sleep(StartupSoft.dbErrRetrySleepTime);
										txRetried2++;
									}
									txGraph.begin();

									expResultData = Util.vReload(expResultData, txGraph);

									Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, Util.ridToVertex(filteredRid, txGraph));
									expResultDataEdge.setProperty(LP.startTimeOffset, fetchedResultStartTimeOffset.get(originalIndex));

									txError2 = txGraph.finalizeTask(true);
								}
							}

							for (String selectedModRid : selectedModVertexRid) {
								//TODO: Unknown timing as they are fetched, whether to intersect or append is unknown.
								//Then add all other selected ADD vertexes.
								//Find the original result's start time offset timing variable.
								int originalIndex = -1;
								for (int i=0; i<fetchedResultRids.size(); i++) {
									if (selectedModRid.equals(fetchedResultRids.get(i))) {
										originalIndex = i;
										break;
									}
								}

								//Commit retry model.
								boolean txError2 = true;
								int txRetried2 = 0;
								while (txError2) {
									if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
										throw new IllegalStateException("Failed to complete transaction after number of retry:"
												+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
									}
									else if (txError2) {
										if (txRetried2 != 0)
											Util.sleep(StartupSoft.dbErrRetrySleepTime);
										txRetried2++;
									}
									txGraph.begin();

									expResultData = Util.vReload(expResultData, txGraph);

									Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, Util.ridToVertex(selectedModRid, txGraph));
									expResultDataEdge.setProperty(LP.startTimeOffset, fetchedResultStartTimeOffset.get(originalIndex));

									txError2 = txGraph.finalizeTask(true);
								}
							}
						}

						runUniversalSolutionGenerationAlgorithm = true;

						StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
								"At 'select new attention', selected normal exp with previous session experience.");
					}
				}

				//Normal exp that had never been selected as attention point before. Identifiable as it doesn't own a 'session' vertex, but only
				//only a 'parentSession' vertex (inherited) where 'session' vertex can only be available if he had been selected before.
				catch (IllegalArgumentException e) {
					//Copy all the required data into the new exp from selected exp.
					Vertex fetchedExpMainData = Util.traverseOnce(selectedNewAttentionVertex, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.cn);
					Vertex fetchedExpRequirementGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.requirement, DBCN.V.general.exp.requirement.cn);
					Vertex fetchedExpRequirementData = Util.traverseOnce(fetchedExpRequirementGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.requirement.cn);
					ArrayList<Vertex> fetchedRequirements = Util.traverse(fetchedExpRequirementData, Direction.OUT, DBCN.E.requirement);
					ArrayList<Long> fetchedRequirementStartTimeOffset = Util.traverseGetStartTimeOffset(
							fetchedExpRequirementData, Direction.OUT, DBCN.E.requirement);

					Vertex fetchedExpResultGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
					Vertex fetchedExpResultData = Util.traverseOnce(fetchedExpResultGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
					ArrayList<Vertex> fetchedResults = Util.traverse(fetchedExpResultData, Direction.OUT, DBCN.E.result);
					ArrayList<Long> fetchedResulstStartTimeOffset = Util.traverseGetStartTimeOffset(
							fetchedExpResultData, Direction.OUT, DBCN.E.result);

					assert fetchedExpRequirementData.getCName().equals(DBCN.V.LTM.exp.requirement.cn) : fetchedExpRequirementData;
					assert fetchedExpResultData.getCName().equals(DBCN.V.LTM.exp.result.cn) : fetchedExpResultData;

					for (int i=0; i<fetchedRequirements.size(); i++) {
						//Commit retry model.
						boolean txError = true;
						int txRetried = 0;
						while (txError) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;
							}
							txGraph.begin();

							expRequirementData = Util.vReload(expRequirementData, txGraph);
							Vertex fetchedRequirementReloaded = Util.vReload(fetchedRequirements.get(i), txGraph);

							Edge expRequirementDataEdge = expRequirementData.addEdge(DBCN.E.requirement, fetchedRequirementReloaded);
							expRequirementDataEdge.setProperty(LP.startTimeOffset, fetchedRequirementStartTimeOffset.get(i));

							txError = txGraph.finalizeTask(true);
						}
					}
					for (int i=0; i<fetchedResults.size(); i++) {
						//Commit retry model.
						boolean txError = true;
						int txRetried = 0;
						while (txError) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;
							}
							txGraph.begin();

							//They both share the same vertex as result is really just prediction for the next round by design.
							expResultData = Util.vReload(expResultData, txGraph);
							expPredictionData = Util.vReload(expPredictionData, txGraph);
							Vertex fetchedResultReloaded = Util.vReload(fetchedResults.get(i), txGraph);
							Vertex fetchedPredictionReloaded = fetchedResultReloaded;

							//Result will later be replaced with real actual result.
							Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, fetchedResultReloaded);
							expResultDataEdge.setProperty(LP.startTimeOffset, fetchedResulstStartTimeOffset.get(i));
							Edge expPredictionDataEdge = expPredictionData.addEdge(DBCN.E.prediction, fetchedPredictionReloaded);
							expPredictionDataEdge.setProperty(LP.startTimeOffset, fetchedResulstStartTimeOffset.get(i));

							txError = txGraph.finalizeTask(true);
						}
					}

					//Commit retry model.
					boolean txError = true;
					int txRetried = 0;
					while (txError) {
						if (txRetried > StartupSoft.dbErrMaxRetryCount) {
							throw new IllegalStateException("Failed to complete transaction after number of retry:"
									+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
						}
						else if (txError) {
							if (txRetried != 0)
								Util.sleep(StartupSoft.dbErrRetrySleepTime);
							txRetried++;
						}
						txGraph.begin();

						selectedNewAttentionVertex = Util.vReload(selectedNewAttentionVertex, txGraph);
						expMainGeneral = Util.vReload(expMainGeneral, txGraph);
						expRequirementGeneral = Util.vReload(expRequirementGeneral, txGraph);
						expResultGeneral = Util.vReload(expResultGeneral, txGraph);
						expPredictionGeneral = Util.vReload(expPredictionGeneral, txGraph);

						//Create a new session for it and setup initial values.
						session = txGraph.addVertex(DBCN.V.general.session.cn, DBCN.V.general.session.cn);
						session.setProperty(LP.nodeUID, session.getRid() + Long.toString(System.currentTimeMillis()) );
						session.setProperty(LP.expState, EXPSTATE.FIRSTTIME);
						session.setProperty(LP.lastPrecisionRate, selectedNewAttentionVertex.getProperty(LP.polyVal));
						session.setProperty(LP.bestPrecisionRate, 0d);

						//Note: LTMSH special operation not possible here as LTMFH operation generated exp is guaranteed to have 'session' vertex.

						//Setup new exp for each operation. Exp code copied from crawler's DWDM_SCCRS. Here setup its specialized properties.
						expMainGeneral.setProperty(LP.expState, EXPSTATE.FIRSTTIME);

						//This vertex doesn't own any session yet. Give him a session to mark it as being selected as selected attention before.
						expMainGeneral.addEdge(DBCN.E.session, session);

						//Add occurrence edges as it is similar and deprive from it.
						//Add occurrence edge to the selected vertex as at the point it got forwarded to crawler and back to form the
						//solution tree, the given head to the crawler's STISS will not be recreated with proper 'occurrence' edge,
						//so must do it manually here.
						expMainGeneral.addEdge(DBCN.E.occurrence, selectedNewAttentionVertex);
						expRequirementGeneral.addEdge(DBCN.E.occurrence, fetchedExpRequirementGeneral);
						expResultGeneral.addEdge(DBCN.E.occurrence, fetchedExpResultGeneral);

						//Setup initial values, duration, precisionRate and depth are -1 to mark it as not yet implemented.
						//It will be put in later after its process is complete.
						//Duration is the time that this exp takes to finish, precisionRate is the similarity between prediction and actual result.
						//Depth means the depth of exp, exp can be nested, thus deeper the depth, more complex the action is. Will be set at the
						//final posting phrase, where it will update the depth value accordingly.
						expMainGeneral.setProperty(LP.timeStamp, System.currentTimeMillis());
						expMainGeneral.setProperty(LP.duration, -1);
						expMainGeneral.setProperty(LP.precisionRate, -1d);
						expMainGeneral.setProperty(LP.occurrenceCountPR, 0l);
						expMainGeneral.setProperty(LP.depth, -1);
						expMainGeneral.setProperty(LP.polyVal, selectedNewAttentionVertex.getProperty(LP.polyVal));

						//Should inherit directly from the parent for their polyVal.
						expRequirementGeneral.setProperty(LP.polyVal, fetchedExpRequirementGeneral.getProperty(LP.polyVal));
						expResultGeneral.setProperty(LP.polyVal, fetchedExpResultGeneral.getProperty(LP.polyVal));
						expPredictionGeneral.setProperty(LP.polyVal, fetchedExpResultGeneral.getProperty(LP.polyVal));

						txError = txGraph.finalizeTask(true);
					}

					assert Util.traverse(expResultData, Direction.OUT, DBCN.E.result).size() == fetchedResults.size() : expResultData;
					assert Util.traverse(expRequirementData, Direction.OUT, DBCN.E.requirement).size() == fetchedRequirements.size() : expRequirementData;

					runUniversalSolutionGenerationAlgorithm = true;

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
							"At 'select new attention', selected normal exp with NO previous session experience.");
				}

				if (runUniversalSolutionGenerationAlgorithm && !isHalt()) {
					//This expAddToGCAQueue doesn't extract out to another transaction as this loop is already idempotent.
					//Commit retry model.
					boolean txError = true;
					int txRetried = 0;
					while (txError) {
						if (txRetried > StartupSoft.dbErrMaxRetryCount) {
							throw new IllegalStateException("Failed to complete transaction after number of retry:"
									+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
						}
						else if (txError) {
							if (txRetried != 0)
								Util.sleep(StartupSoft.dbErrRetrySleepTime);
							txRetried++;
						}
						txGraph.begin();

						expMainGeneral = Util.vReload(expMainGeneral, txGraph);
						session = Util.vReload(session, txGraph);

						//Forward it to GCA, so it will reappear back to here into WM as a new vertex entry, so it can be referenced and
						//the PaRc operation will pass as this is the feedback of the head of the solution tree (solution tree head is the
						//selected attention point forwarded to crawler's STISS) as this is the 'occurrence' PaRc is expecting it from every
						//sent out operation.
						STMClient.expAddToGCAQueue(expMainGeneral, txGraph);

						//Forward to universal solution generation algorithm at crawler to attempt to rerun it.
						//TODO: You may want to allow some part of it to be replaced with latest exp with higher precision and set its
						//expState to REPLACE.
						Vertex newSTISSTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.STISS.task.cn, DBCN.V.jobCenter.crawler.STISS.task.cn);
						Vertex STISSTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
						newSTISSTask.addEdge(DBCN.E.source, STISSTaskDetailVertex);
						//Here uses the newly created exp instead of the selected one as we have did some modification to the new one and
						//want to experiment with that.
						STISSTaskDetailVertex.addEdge(DBCN.E.source, expMainGeneral);

						TaskDetail STISSTaskDetail = new TaskDetail();
						STISSTaskDetail.jobId = "-1";
						STISSTaskDetail.jobType = CRAWLERTASK.DM_STISS;
						STISSTaskDetail.source = "";
						STISSTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.STISS.processing.cn;
						STISSTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.STISS.completed.cn;
						STISSTaskDetail.replyAddr = DBCN.V.devnull.cn;
						STISSTaskDetail.start = -1;
						STISSTaskDetail.end = -1;
						STISSTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(STISSTaskDetail) );

						assert session != null;

						//This session will be the original session of the selected attention vertex, or a new session if it (the selected attention
						//vertex itself) doesn't have one yet, which is possible if this is his first time being selected as attention point.
						STISSTaskDetailVertex.addEdge(DBCN.E.session, session);

						//NOTE: This is a special case, where during commit it raise ORecordNotFoundException with message below occasionally.
						//"Cannot read record #-1:-2 since the position is invalid in database 'ISRA_MINIMAL_V0'"
						//Temporary solution is to simply bypass it, break the commit and continue.
						try {
							txError = txGraph.finalizeTask(true);
						}
						catch (ORecordNotFoundException ornfe) {
							internalErrRollback = true;
							StartupSoft.logger.log(logCredential, LVL.WARN, CLA.EXCEPTION,
									"At runUniversalSolutionGenerationAlgorithm: DB inconsistent error about ORecordNotFoundException "
									+ "during commit skipped. Operation rollbacked.", ornfe);
							break;
//							throw new IllegalStateException("Record not found, identity is: " + ornfe.getComponentName() + "  --  " + ornfe.getMessage()
//							+ "  ---  " + ornfe.getStorageURL()  +  "  Original Stack trace: " + Util.stackTraceToString(ornfe));
						}
					}

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
							"At 'select new attention', runUniversalSolutionGenerationAlgorithm export success. Selected attention vertex: "
									+ selectedNewAttentionVertex + " expMainGeneral: " + expMainGeneral);
				}
				//End of select attention point from exp.
			}	//End of WM new attention point selection.
		}	//End of isHalt().

		//THESE ARE FOR ARCHIVAL PURPOSE ONLY! The session data record.
		//Record the end time for this session, then make edge to the last GCA.
		//This serve as the end marker for this whole running session, putting him to eternal sleep.
		Vertex lastGCAFrameBeforeShutdown = txGraph.getLatestVertexOfClass(DBCN.V.general.GCAMain.cn);

		//Commit retry model.
		boolean txError = true;
		int txRetried = 0;
		while (txError) {
			if (txRetried > StartupSoft.dbErrMaxRetryCount) {
				throw new IllegalStateException("Failed to complete transaction after number of retry:"
						+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
			}
			else if (txError) {
				if (txRetried != 0)
					Util.sleep(StartupSoft.dbErrRetrySleepTime);
				txRetried++;
				lastGCAFrameBeforeShutdown = Util.vReload(lastGCAFrameBeforeShutdown, txGraph);
			}
			txGraph.begin();

			startupRecordVertex = Util.vReload(startupRecordVertex, txGraph);
			startupRecordVertex.setProperty(LP.startupEndTime, System.currentTimeMillis());

			//Setup the ending GCA edge to complete the session record.
			startupRecordVertex.addEdge(DBCN.E.startupEndGCA, lastGCAFrameBeforeShutdown);

			txError = txGraph.finalizeTask(true);
		}


		StartupSoft.haltAccepted.set(config.haltIndex, new AtomicBoolean(true));
		StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "WMWorker:"  + config.uid + "-" + config.storageId + "  Thread name:"
				+ Thread.currentThread().getName() + " sucessfully halted.");
	}

	//Extract it from startService in order to log the errors.
	@Override
	public void run() {
		try {
			startService();
		}
		catch(Error e) {
			StartupSoft.logger.log(logCredential, LVL.FATAL, CLA.EXCEPTION, "", e);
		}
	}
}
