package crawler;

import java.util.ArrayList;

import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import stm.LTM;
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
 * RSG Failed Switch Branch (RSGFSB)    RSG - Recursive Solution Generation, another crawler work.
 * RSG will sometimes fail to generate solution, if that happen, invalidate the branch he is working on, return upward 1 layer
 * within the solution tree then seek for the next best branch to execute, and initiate it.
 * If no more branch is available, return upward further, until reaches the upper origin, if still not functional branches,
 * then admit defeat and become idle.
 *
 * Doesn't extract the forwarding portion out as this operation is not expected to be reused like other DWDM operations.
 */
public class RSGFSB {
	private long dbErrMaxRetryCount;
	private long dbErrRetrySleepTime;

	public RSGFSB(long dbErrMaxRetryCount, long dbErrRetrySleepTime) {
		this.dbErrMaxRetryCount = dbErrMaxRetryCount;
		this.dbErrRetrySleepTime = dbErrRetrySleepTime;
	}

	public void execute(Vertex generalVertex, Vertex taskDetailVertex, Graph txGraph) {
		//Invalidate only secondary vertex, main convergence does not need to be invalidated.
		//-generalVertex here is mainConvergence vertex, as only RSG can call this clause, and RSG only work with mainConvergence.
		if (!generalVertex.getCName().equals(DBCN.V.general.convergenceMain.cn))
			throw new IllegalArgumentException("At RSGFSB: Expecting input type: "+ DBCN.V.general.convergenceMain.cn + " but get: " + generalVertex);

		//Inherit session data.
		Vertex session = Util.traverseOnce(taskDetailVertex, Direction.OUT, DBCN.E.session);

		Vertex parentSecondaryConvergenceVertex = null;
		Vertex parentMainConvergenceVertex = null;

		boolean arrOutOfBound = false;
		boolean reachedAbsoluteBeginning = false;
		boolean firstTime = true;

		//Check whether generalVertex is the absolute beginning or not. -1 is the unique indicator to identify it as the absolute beginning.
		//Set the parentMainConvergenceVertex as the code below will be using it.
		if ( (int)generalVertex.getProperty(LP.originalOrdering) == -1 ) {
			reachedAbsoluteBeginning = true;
		}

		//If they are not the first and and not reached absolute beginning yet, which means there are still solution left,
		//begin to select the next best solution. Else just do nothing, this is essential to break out from the macro loop where if
		//you don't break out here, it will be fed back into WM, PaRc fail, then back to here, then keep repeat.
		if (!firstTime && !reachedAbsoluteBeginning) {
			//arrOutOfBound means there is no more solution available, will have to invalidate even upper layer branch
			//and seek new branch from there. Else just switch branch and continue on usual execution.
			//Loop indefinitely until break point.
			while (true) {
				//First time initialize with default value. After that reuse previous value and update it using themselves.
				if (firstTime && !reachedAbsoluteBeginning) {
					parentSecondaryConvergenceVertex = Util.traverseOnce(generalVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);
					parentMainConvergenceVertex = Util.traverseOnce(parentSecondaryConvergenceVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);
				}
				//If it is not the first time and had ran out of solution for the vertex (secondary, main) that we fetched
				//last time (may be the first time), wrap upward once more to get to the upper branches by 1 layer.
				else if (!firstTime && arrOutOfBound) {
					parentSecondaryConvergenceVertex = Util.traverseOnce(parentMainConvergenceVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);
					parentMainConvergenceVertex = Util.traverseOnce(parentSecondaryConvergenceVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);
					arrOutOfBound = false;		//reset.
				}

				//Check once as the generalVertex might already be the absolute beginning.
				if (!reachedAbsoluteBeginning) {
					//Check whether it had reached absolute beginning or not, -1 is a sentinel value unique only to the beginning of the tree
					//at originalOrdering element.
					int originalOrdering = parentMainConvergenceVertex.getProperty(LP.originalOrdering);
					if (originalOrdering == -1) {
						reachedAbsoluteBeginning = true;
					}
				}

				if (reachedAbsoluteBeginning) {
					//Forward to next step then quit. Reached absolute beginning at here means RSG cannot generate any viable solution,
					//and this return will let the final DM decide its own fate, usually just idle.
					//Similar entry at ACTGDR and RSGFSB(here), do this for both of them.
					//TODO: IMPORTANT IMPLEMENT: forward this to consequences check before to the executing step, now don't, later must!

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

						parentMainConvergenceVertex = Util.vReload(parentMainConvergenceVertex, txGraph);
						session = Util.vReload(session, txGraph);

						Vertex newRERAUPTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.RERAUP.task.cn, DBCN.V.jobCenter.crawler.RERAUP.task.cn);
						Vertex RERAUPTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
						newRERAUPTask.addEdge(DBCN.E.source, RERAUPTaskDetailVertex);
						RERAUPTaskDetailVertex.addEdge(DBCN.E.source, parentMainConvergenceVertex);
						RERAUPTaskDetailVertex.addEdge(DBCN.E.session, session);

						TaskDetail RERAUPTaskDetail = new TaskDetail();
						RERAUPTaskDetail.jobId = "-1";
						RERAUPTaskDetail.jobType = CRAWLERTASK.DM_RERAUP;
						RERAUPTaskDetail.source = "";
						RERAUPTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.RERAUP.processing.cn;
						RERAUPTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.RERAUP.completed.cn;
						RERAUPTaskDetail.replyAddr = DBCN.V.devnull.cn;
						RERAUPTaskDetail.start = -1;
						RERAUPTaskDetail.end = -1;
						RERAUPTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(RERAUPTaskDetail) );

						txError = txGraph.finalizeTask(true);
					}

					break;
				}

				//Both of this index and list data correspond to the original ordering of the index of the secondary vertex list
				//for the given mainConvergence.  sortedSolutionIndexList is the best solution calculated at the moment.
				ArrayList<Integer> sortedSolutionIndexList =
						Util.kryoDeserialize( (byte[])parentMainConvergenceVertex.getProperty(LP.sortedSolutionIndexList), ArrayList.class);
				int selectedSolutionIndex = parentMainConvergenceVertex.getProperty(LP.selectedSolutionIndex);

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

					parentSecondaryConvergenceVertex = Util.vReload(parentSecondaryConvergenceVertex, txGraph);

					//Invalidate current branch, the parentSecondaryConvergence is the equivalent of the selectedSolutionIndex's data
					//if fetched from the mainConvergence's secondary vertex list. Thus simply reuse it. -1 to invalidate the path.
					parentSecondaryConvergenceVertex.setProperty(LP.remainingSolutionToBeCompleted, -1);

					txError = txGraph.finalizeTask(true);
				}

				//Update selectedSolutionIndex to get the next best solution.
				selectedSolutionIndex += 1;
				//Check whether the increment causes array out of bound error, if does means we had run out of solution to go on and must
				//migrate 1 more layer up.
				if (selectedSolutionIndex == sortedSolutionIndexList.size()) {
					arrOutOfBound = true;
				}

				//Solution is available and ready to deploy.
				if (!arrOutOfBound) {
					//Commit retry model.
					boolean txError2 = true;
					int txRetried2 = 0;
					while (txError2) {
						if (txRetried2 > dbErrMaxRetryCount) {
							throw new IllegalStateException("Failed to complete transaction after number of retry:"
									+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
						}
						else if (txError2) {
							if (txRetried2 != 0)
								Util.sleep(dbErrRetrySleepTime);
							txRetried2++;
						}
						txGraph.begin();

						parentMainConvergenceVertex = Util.vReload(parentMainConvergenceVertex, txGraph);
						session = Util.vReload(session, txGraph);

						parentMainConvergenceVertex.setProperty(LP.selectedSolutionIndex, selectedSolutionIndex);

						//Get the real solution index by the original secondary vertex's ordering.
						int actualSolutionIndex = sortedSolutionIndexList.get(selectedSolutionIndex);
						ArrayList<Vertex> secondaryConvergenceList = Util.traverse(parentMainConvergenceVertex, Direction.IN, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);
						Vertex selectedSecondaryConvergence = secondaryConvergenceList.get(actualSolutionIndex);

						//Setup new RSG to compute our selected solution.
						Vertex dataGeneralVertex = Util.traverseOnce(selectedSecondaryConvergence, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);
						String dataType = dataGeneralVertex.getCName();

						if (!dataType.equals(DBCN.V.general.exp.requirement.cn) || !dataType.equals(DBCN.V.general.exp.result.cn)
								|| Util.equalAny(dataType, LTM.GENERAL)) {
							throw new IllegalStateException("At RSGFSB, unsupported type:" + dataType + ", should only be exp or LTM as RSG"
									+ "(the next destination) only support these types.");
						}

						Vertex newMainConvergenceVertex = txGraph.addVertex(DBCN.V.general.convergenceMain.cn, DBCN.V.general.convergenceMain.cn);
						newMainConvergenceVertex.addEdge(DBCN.E.data, dataGeneralVertex);
						newMainConvergenceVertex.addEdge(DBCN.E.parent, selectedSecondaryConvergence);
						newMainConvergenceVertex.setProperty(LP.originalOrdering, actualSolutionIndex);
						newMainConvergenceVertex.setProperty(LP.selectedSolutionIndex, -1);

						Vertex newRSGTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.RSG.task.cn, DBCN.V.jobCenter.crawler.RSG.task.cn);
						Vertex RSGTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
						newRSGTask.addEdge(DBCN.E.source, RSGTaskDetailVertex);
						RSGTaskDetailVertex.addEdge(DBCN.E.source, newMainConvergenceVertex);
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

						txError2 = txGraph.finalizeTask(true);
					}
					break;
				}
			}
		}
	}
}
