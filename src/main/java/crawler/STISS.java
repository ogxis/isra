package crawler;

import java.util.ArrayList;

import isradatabase.Direction;
import isradatabase.Edge;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import utilities.Util;
import ymlDefine.YmlDefine.TaskDetail;

/*
 * Crawler Function Convention is:
 * Without special modifier: stateless calculation. Modifies only given parameter and do not make any transaction to database.
 * TxL: Transaction in code, modify only the parameter fed in. Basically stateless. (LOCAL)
 * TxE: Transaction in code that modify local and expand to external interface. (EXTERNAL)
 * TxF: Transaction in code and lead to next phrase of work, that post states. (FORWARDING)
 */
/**
 * Solution Tree Initial Structure Setup (STISS)
 * The first step of crawler DM. Initiated by WM new attention point decider by assigning us with a demand to fulfill(recreate).
 * Setup the initial tree structure using the dual convergence system (main and secondary convergence, 2 type only)
 * Where Secondary convergence contains particular requirement to fulfill the demand alongside with procedure.
 * Main convergence contains links to all the secondary convergence to group them together.
 * When used together, it will form a continuous chain main -> secondary -> main2 -> secondary2 -> ... -> mainN -> secondaryN
 * That forms a solution tree which provide multiple ways to satisfy the demand given.
 *
 * Doesn't extract the forwarding portion out as this operation is not expected to be reused like other DWDM operations.
 */
public class STISS {
	private long dbErrMaxRetryCount;
	private long dbErrRetrySleepTime;

	public STISS(long dbErrMaxRetryCount, long dbErrRetrySleepTime) {
		this.dbErrMaxRetryCount = dbErrMaxRetryCount;
		this.dbErrRetrySleepTime = dbErrRetrySleepTime;
	}

	public void execute(Vertex generalVertex, Vertex taskDetailVertex, Graph txGraph) {
		String className = generalVertex.getCName().toString();
		if (!className.equals(DBCN.V.general.exp.cn))
			throw new IllegalStateException("At STISS: The given general vertex must be type expMainGeneral from WM new attention decider"
					+ "by contract but received type:" + className);

		txGraph.begin();
		/*
		 * First 3 convergence unusual semantic and the preceding convergence standard semantic:
		 * First mainConvergence is an empty placeholder, first secondaryConvergence stores the result of the given demand (generalVertex),
		 * another preceding mainConvergence stores the requirement of the given demand (generalVertex).
		 * These first 3 convergence are special exception, they have special property that doesn't comply with the following usual procedure.
		 * All the following convergence will work using the standard procedure, which will be:
		 * Multiple secondary convergence created depending on the amount of data its parent main convergence contains,
		 * each secondary convergence contains solutions (requirement) that can be done to achieve its parent main convergence.
		 * Then for each secondary convergence's requirement, it will spawn a new main convergence.
		 * And the loop continues until the solution tree finalize (forward to WM via RERAUP).
		 */
		//This first mainConvergence, the empty head main convergence.
		Vertex firstMainConvergence = txGraph.addVertex(DBCN.V.general.convergenceMain.cn, DBCN.V.general.convergenceMain.cn);
		//---These are initial values, all convergence are required to have them. -1 is a special marker to signify that it is the beginning,
		//the head.
		firstMainConvergence.setProperty(LP.totalSolutionSize, 0);
		firstMainConvergence.setProperty(LP.remainingSolutionToBeCompleted, 0);
		firstMainConvergence.setProperty(LP.originalOrdering, -1);
		//Its selected route should be 0 as the best route always start from 0 ascending order.
		firstMainConvergence.setProperty(LP.selectedSolutionIndex, 0);
		//SortedSolutionIndexList is required during WM solution tree unmarshal to get all data. It will be expecting a maximum
		//size of the 'selectedSolutionIndex', here selectedSolutionIndex maximum value is only 0 (1 element),
		//add in the value 0 into the indexList so it will now have (1 element), equivalent to the maximum
		//possible size of the selectedSolutionIndex.
		ArrayList<Integer> sortedSolutionIndexList = new ArrayList<Integer>();
		sortedSolutionIndexList.add(0);
		firstMainConvergence.setProperty(LP.sortedSolutionIndexList, Util.kryoSerialize(sortedSolutionIndexList));
		//Send it to SCCRS, if SCCRS completed, then it will add this value by 1, for first mainConvergence this value will not be used.
		firstMainConvergence.setProperty(LP.SCCRSCompletedSize, 0);

		Vertex session = Util.traverseOnce(taskDetailVertex, Direction.OUT, DBCN.E.session);

		//Traverse and get the requirement and result needed to setup the convergence.
		Vertex expMainData = Util.traverseOnce(generalVertex, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.cn);
		Vertex expRequirementGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.requirement, DBCN.V.general.exp.requirement.cn);
		Vertex expResultGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);

		//-Traverse and get its actual result data and set it to the secondary convergence vertex.
		Vertex secondaryConvergence = txGraph.addVertex(DBCN.V.general.convergenceSecondary.cn, DBCN.V.general.convergenceSecondary.cn);
		//Add edge to its source mainConvergence to form a linked tree.
		secondaryConvergence.addEdge(DBCN.E.parent, firstMainConvergence);

		Vertex expResultData = Util.traverseOnce(expResultGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
		ArrayList<Vertex> resultDataList = Util.traverse(expResultData, Direction.OUT, DBCN.E.result);
		ArrayList<Long> resultDataStartTimeOffset = Util.traverseGetStartTimeOffset(expResultData, Direction.OUT, DBCN.E.result);

		//-Traverse to its actual requirement data and set it to the main convergence vertex. One convergence for each requirement.
		Vertex expRequirementData = Util.traverseOnce(expRequirementGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.requirement.cn);
		ArrayList<Vertex> requirementDataList = Util.traverse(expRequirementData, Direction.OUT, DBCN.E.requirement);
		ArrayList<Long> requirementDataStartTimeOffset = Util.traverseGetStartTimeOffset(expRequirementData, Direction.OUT, DBCN.E.requirement);

		//It uses requirement's solution size instead of the result solution size as we want the following main convergence to complete,
		//then return upward to reduce the remaining step, if you uses result's solution size here it would be incompatible.
		//TODO: Therefore maybe you should change the result to requirement and forbid result completely here?
		secondaryConvergence.setProperty(LP.remainingSolutionToBeCompleted, requirementDataList.size());
		secondaryConvergence.setProperty(LP.totalSolutionSize, requirementDataList.size());
		//Original ordering is 0 as its parent main convergence holds nothing, thus it is a special delimiter.
		secondaryConvergence.setProperty(LP.originalOrdering, 0);

		//Create an edge to the first convergenceMain (decision tree head) for archival purposes to ease future decision tree seek
		//for analysis tool.
		Vertex convergenceHead = txGraph.addVertex(DBCN.V.general.convergenceHead.cn, DBCN.V.general.convergenceHead.cn);
		convergenceHead.addEdge(DBCN.E.convergenceHead, firstMainConvergence);
		convergenceHead.setProperty(LP.timeStamp, System.currentTimeMillis());
		convergenceHead.setProperty(LP.PaRcPassed, true);

		txGraph.finalizeTask();

		for (int i=0; i<resultDataList.size(); i++) {
			//Commit retry model.
			boolean txError = true;
			int txRetried = 0;
			while (txError) {
				if (txRetried > dbErrMaxRetryCount) {
					throw new IllegalStateException("Failed to complete transaction after number of retry:"
							+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
				}
				else if (txError) {
					if (txRetried != 0)
						Util.sleep(dbErrRetrySleepTime);
					txRetried++;
				}
				txGraph.begin();
				secondaryConvergence = Util.vReload(secondaryConvergence, txGraph);
				Vertex currentResultData = Util.vReload(resultDataList.get(i), txGraph);
				Edge secondaryConvergenceEdge = secondaryConvergence.addEdge(DBCN.E.data, currentResultData);
				secondaryConvergenceEdge.setProperty(LP.startTimeOffset, resultDataStartTimeOffset.get(i));
				txError = txGraph.finalizeTask(true);
			}
		}

		for (int i=0; i<requirementDataList.size(); i++) {
			//Commit retry model.
			boolean txError = true;
			int txRetried = 0;
			while (txError) {
				if (txRetried > dbErrMaxRetryCount) {
					throw new IllegalStateException("Failed to complete transaction after number of retry:"
							+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
				}
				else if (txError) {
					if (txRetried != 0)
						Util.sleep(dbErrRetrySleepTime);
					txRetried++;
				}
				txGraph.begin();

				secondaryConvergence = Util.vReload(secondaryConvergence, txGraph);
				Vertex currentRequirementData = Util.vReload(requirementDataList.get(i), txGraph);
				session = Util.vReload(session, txGraph);

				Vertex mainConvergence = txGraph.addVertex(DBCN.V.general.convergenceMain.cn, DBCN.V.general.convergenceMain.cn);
				//Add edge to its source secondaryConvergence to form a linked tree.
				mainConvergence.addEdge(DBCN.E.parent, secondaryConvergence);

				Edge mainConvergenceEdge = mainConvergence.addEdge(DBCN.E.data, currentRequirementData);
				mainConvergenceEdge.setProperty(LP.startTimeOffset, requirementDataStartTimeOffset.get(i));
				//NOTE: mainConvergence solution size will be set by RSG.
				//Keep its original ordering so to let its main know which of its requirement had completed by referring to this ordering index.
				mainConvergence.setProperty(LP.originalOrdering, 0);

				//Every one of those convergence data, regardless their base type, will be forwarded normally.
				//Each of them will be expanded once, this is the standard operation, will still able to trace their origin using its original
				//ordering variable, in case of error. Error inclusive of PaRc error, which happens real often.

				//Recursively create the rest of the tree by assigning each main vertex as a new task, where that task should
				//check whether the requirement is satisfiable, already satisfied, scheduled to satisfied or not yet considered.
				//If they are not yet considered, recursively generate solution for that requirement, and then again check whether
				//the requirement is satisfiable, do this until all requirement are scheduled.
				Vertex newRSGTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.RSG.task.cn, DBCN.V.jobCenter.crawler.RSG.task.cn);
				Vertex RSGTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
				newRSGTask.addEdge(DBCN.E.source, RSGTaskDetailVertex);
				RSGTaskDetailVertex.addEdge(DBCN.E.source, mainConvergence);
				RSGTaskDetailVertex.addEdge(DBCN.E.session, session);

				TaskDetail RSGTaskDetail = new TaskDetail();
				RSGTaskDetail.jobId = "-1";
				RSGTaskDetail.jobType = CRAWLERTASK.DM_RSG;
				RSGTaskDetail.source = "";
				RSGTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.RSG.processing.cn;
				RSGTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.RSG.completed.cn;
				RSGTaskDetail.replyAddr = DBCN.V.devnull.cn;
				RSGTaskDetail.start = -1;
				RSGTaskDetail.end = -1;
				RSGTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(RSGTaskDetail) );

				//Hooks to catch DB inconsistency errors for all operation concerning transaction within loop.
				assert Util.traverse(mainConvergence, Direction.OUT, DBCN.E.parent).size() == 1 : mainConvergence;
				assert Util.traverse(mainConvergence, Direction.OUT, DBCN.E.data).size() == 1 : mainConvergence;
				txError = txGraph.finalizeTask(true);
			}
		}

		//Hooks to catch DB inconsistency errors for all operation concerning transaction within loop.
		assert Util.traverse(secondaryConvergence, Direction.OUT, DBCN.E.data).size() == resultDataList.size() : secondaryConvergence;
		assert requirementDataList.size() != 0 : "requirementDataList.size() cannot be 0, no exp's req can be 0. Size: "
				+ requirementDataList.size() + " expRequirementData RID: " + expRequirementData;
		assert Util.traverse(secondaryConvergence, Direction.IN, DBCN.E.parent).size() == requirementDataList.size() : secondaryConvergence;
	}
}
