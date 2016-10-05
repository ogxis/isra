package crawler;

import java.util.ArrayList;
import java.util.Iterator;

import gca.GCA;
import isradatabase.Direction;
import isradatabase.Edge;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import logger.Logger;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;
import stm.DBCN;
import stm.LTM;
import stm.STMClient;
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
 * Secondary Convergence Check Requirement Scheduled (SCCRS)
 * Check whether the demand's requirement already exist, exist now or scheduled to exist.
 * Then return the result back to the mainConvergence by traversing the secondary convergence given to him.
 * Check mainConvergence's remaining SCCRS task, if we are the last task and no more, then invoke ACTGDR to decide which secondary
 * convergence (route) to continue on generation and execute.
 * This also replaces designated component with similar component instead of absolute one as it is hard to get absolute item.
 * Return the statistic to DM so it can prematurely decide which route to go on without delving into all details.
 * This is run for every unmet requirement AS A WHOLE.
 *
 * Doesn't extract the forwarding portion out as this operation is not expected to be reused like other DWDM operations.
 */
public class SCCRS {
	private long dbErrMaxRetryCount;
	private long dbErrRetrySleepTime;
	private Logger logger;
	private Credential logCredential;
	private boolean loggerSet;

	public SCCRS(long dbErrMaxRetryCount, long dbErrRetrySleepTime, Logger logger, Credential logCredential) {
		this.dbErrMaxRetryCount = dbErrMaxRetryCount;
		this.dbErrRetrySleepTime = dbErrRetrySleepTime;
		this.logger = logger;
		this.logCredential = logCredential;
		if (logger != null && logCredential != null)
			loggerSet = true;
		else
			loggerSet = false;
	}

	public void execute(Vertex generalVertex, Vertex taskDetailVertex, Graph txGraph) {
		//-general vertex here is a secondary convergence vertex which contains 'data' edge to the actual requirement data vertex.
		if (!generalVertex.getCName().equals(DBCN.V.general.convergenceSecondary.cn))
			throw new IllegalArgumentException("At SCCRS: Expecting input type: "+ DBCN.V.general.convergenceSecondary.cn + " but get: " + generalVertex);

		//Inherit session data.
		Vertex session = Util.traverseOnce(taskDetailVertex, Direction.OUT, DBCN.E.session);

		//generalVertex's data' datatype can be both LTM or exp requirement/result type.
		//NOTE: The requirement data can be any type. Any their operation differs as well.
		ArrayList<Vertex> demandRequirementVertexList = Util.traverse(generalVertex, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);

		//It can either contain requirement/result general exp (which can embed more specific data in it) OR LTM general vertex (single) only.
		if (demandRequirementVertexList.size() > 1) {
			throw new IllegalStateException("At SCCRS, mainConvergence data size can never be larger than 1 but received:"
					+ demandRequirementVertexList.size() + " elemnts. Their RIDs are: " + demandRequirementVertexList);
		}
		else if (demandRequirementVertexList.size() == 0) {
			throw new IllegalStateException("demandRequirementVertexList.size() == 0. Fix will be skip this vertex as a whole and mark it as failed."
					+ generalVertex);
		}

		Vertex demandRequirementVertex = demandRequirementVertexList.get(0);
		String demandType = demandRequirementVertex.getCName();

		//Begin to pair against WM to see if they are existed, exist now or scheduled to exist. 'Scheduled to' is possible due to previous
		//side effect, those side effect may be remnant of other sessions OR simply RSG fabricated.
		//Calculate it as a whole. If it doesn't match, then delve into its inner and find other solutions.
		//If for exp, matched total count will be the exp's actual data count, if it is LTM, it count size will be 1 (the LTM itself).
		ArrayList<Integer> matched = new ArrayList<Integer>();
		ArrayList<Integer> notMatched = new ArrayList<Integer>();
		int totalRequirementSize = 0;

		if (demandType.equals(DBCN.V.general.exp.requirement.cn) || demandType.equals(DBCN.V.general.exp.result.cn)) {
			//Commit the exp template first to reduce the rollback size.
			txGraph.begin();
			//Setup new exp here for every new SCCRS input, store all the changes and data here before moving to next ACTGDR step.
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

				session = Util.vReload(session, txGraph);
				expMainGeneral = Util.vReload(expMainGeneral, txGraph);
				expRequirementGeneral = Util.vReload(expRequirementGeneral, txGraph);
				expResultGeneral = Util.vReload(expResultGeneral, txGraph);

				//Copy the expState from the session to all newly created exp, meaning that this exp is created during what criteria (state).
				//He is not eligible to have edge to the actual session vertex. Only the selected vertex (during WM initial selection) can have
				//edge to the actual session vertex. Here the expState is given to every new exp created to tell that the exp is borned under
				//what state, doesn't means he have the ownership to the session. He may own his own session if he is specially selected by WM
				//initial creation. Session only bind to the selected vertex by WM during and set during selection.
				expMainGeneral.setProperty(LP.expState, session.getProperty(LP.expState));

				//Make an edge to the session that created you for archival purposes.
				expMainGeneral.addEdge(DBCN.E.parentSession, session);

				//Add occurrence edges as it is similar and deprive from it. Must traverse to its expMainGeneral vertex as
				//occurrence edge contract explicitly tells that all occurrence edges must be in either expMainGeneral or LTM general ONLY.
				//If it is LTM here, then it doesn't need anymore traversal as the givenExpGeneralVertex is the general vertex we wanted.
				Vertex givenExpGeneralVertex = Util.traverseOnce(generalVertex, Direction.OUT, DBCN.E.data, LTM.EXP);
				String givenExpGeneralClassName = givenExpGeneralVertex.getCName();
				if (givenExpGeneralClassName.equals(DBCN.V.general.exp.requirement.cn)
						|| givenExpGeneralClassName.equals(DBCN.V.general.exp.result.cn)) {
					Vertex traversedExpMainData = null;
					Vertex traversedExpRequirementGeneral = null;
					Vertex traversedExpResultGeneral = null;

					if (givenExpGeneralClassName.equals(DBCN.V.general.exp.requirement.cn)) {
						traversedExpMainData = Util.traverseOnce(givenExpGeneralVertex, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
						traversedExpRequirementGeneral = givenExpGeneralVertex;
						traversedExpResultGeneral = Util.traverseOnce(traversedExpMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
					}
					else {
						traversedExpMainData = Util.traverseOnce(givenExpGeneralVertex, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
						traversedExpRequirementGeneral = Util.traverseOnce(traversedExpMainData, Direction.IN, DBCN.E.requirement);
						traversedExpResultGeneral = givenExpGeneralVertex;
					}
					Vertex traversedExpMainGeneral = Util.traverseOnce(traversedExpMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);

					assert traversedExpMainGeneral.getCName().equals(DBCN.V.general.exp.cn) : traversedExpMainGeneral;
					assert traversedExpRequirementGeneral.getCName().equals(DBCN.V.general.exp.requirement.cn) : traversedExpRequirementGeneral;
					assert traversedExpResultGeneral.getCName().equals(DBCN.V.general.exp.result.cn) : traversedExpResultGeneral;

					//Setup occurrence edge properly, when it enters WM, it will pass the PaRc directly.
					//And its occurrence edges will not be reset or there.
					expMainGeneral.addEdge(DBCN.E.occurrence, traversedExpMainGeneral);
					expRequirementGeneral.addEdge(DBCN.E.occurrence, traversedExpRequirementGeneral);
					expResultGeneral.addEdge(DBCN.E.occurrence, traversedExpResultGeneral);
				}
				else
					throw new IllegalStateException("Unsupported type: " + givenExpGeneralClassName);

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

				txError = txGraph.finalizeTask(true);
			}

			//If they are experiences, will have to expand them in order to pair them.
			//Doesn't care about its original given requirement (the expRequirementGeneral vertex), instead will expand it first
			//then calculate the actual requirement data that he carry.
			//Expand the exp to pair it element by element.

			Vertex fetchedExpRequirementGeneral = null;

			//We want its requirement, so by completing its requirement, we get its result done. As at here it can be
			//either requirement or result now. It means that the 'currentRequirement' may be 'result' as well, depending
			//on how you look at it. Usually it will be requirement.
			if (demandType.equals(DBCN.V.general.exp.result.cn)) {
				Vertex fetchedExpMainData = Util.traverseOnce(demandRequirementVertex, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
				fetchedExpRequirementGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.requirement, DBCN.V.general.exp.requirement.cn);
			}
			else if (demandType.equals(DBCN.V.general.exp.requirement.cn))
				fetchedExpRequirementGeneral = demandRequirementVertex;
			else
				throw new IllegalStateException("Unsupported type: " + demandType);

			//Requirement's start time offset has to be extracted and copied into the new exp as well.
			Vertex fetchedExpRequirementData = Util.traverseOnce(fetchedExpRequirementGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.requirement.cn);
			ArrayList<Vertex> requirements = Util.traverse(fetchedExpRequirementData, Direction.OUT, DBCN.E.requirement);
			ArrayList<Long> requirementStartTimeOffset = Util.traverseGetStartTimeOffset(fetchedExpRequirementData,
					Direction.OUT, DBCN.E.requirement);
			totalRequirementSize = requirements.size();

			//Inner calculation for the deeper child node cycle, outer layer is explicitly
			//ignored as what we care is how its innard works, not how it as a whole work from the outside view.
			//That outside view would have been done by the previous iteration, where its package innards is the
			//current requirements' requirement as a whole.
			double requirementPolyValSum = 0d;
			for (int i=0; i<requirements.size(); i++) {
				//It returns also the latest data so we can inject it into our new exp requirements.
				Vertex returnResult = STMClient.checkRidExistInWorkingMemory(requirements.get(i), txGraph);

				boolean exist = true;
				if (returnResult == null)
					exist = false;

				if (exist)
					matched.add(i);
				else
					notMatched.add(i);

				//If it exist in the WM, substitute it with the latest WM data, else just use original data.
				if (exist) {
					requirementPolyValSum += (double)returnResult.getProperty(LP.polyVal);
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

						returnResult = Util.vReload(returnResult, txGraph);
						expRequirementData = Util.vReload(expRequirementData, txGraph);

						Edge requirementEdge = expRequirementData.addEdge(DBCN.E.requirement, returnResult);
						//TODO: should it use start time offset of itself? Check whether at this time he had the offset ready?
						requirementEdge.setProperty(LP.startTimeOffset, requirementStartTimeOffset.get(i));
						txError2 = txGraph.finalizeTask(true);
					}
				}
				else {
					requirementPolyValSum += (double)requirements.get(i).getProperty(LP.polyVal);
					//Commit retry model.
					boolean txError2 = true;
					int txRetried2 = 0;
					while (txError2) {
						if (txRetried2 > dbErrMaxRetryCount) {
							throw new IllegalStateException("Failed to complete transaction after number of retry:"
									+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
						}
						else if (txError2) {
							if (txRetried2 != 0) {
								Util.sleep(dbErrRetrySleepTime);
							}
							txRetried2++;
						}
						txGraph.begin();

						expRequirementData = Util.vReload(expRequirementData, txGraph);
						Vertex requirementReloaded = Util.vReload(requirements.get(i), txGraph);

						Edge requirementEdge = expRequirementData.addEdge(DBCN.E.requirement, requirementReloaded);
						requirementEdge.setProperty(LP.startTimeOffset, requirementStartTimeOffset.get(i));
						txError2 = txGraph.finalizeTask(true);
					}
				}
			}
			assert Util.traverse(expRequirementData, Direction.OUT, DBCN.E.requirement).size() == requirements.size() : expRequirementData
					+ "  " + requirements.size() + requirements.toString();

			//Copy its results to prediction to complete the exp declaration.
			//--Also copy it to its results to allow it to be recalled successfully if they are to be recalled into
			//these functions (ACTGDR might cycle them if they are not fully matched to find more solution), the result
			//copied here will be temporary, it will be replaced with real result after it is confirmed and selected
			//to the WM and complete all of its prediction, WM is responsible to forward and replace those real result
			//with these old temporary copied result.
			//Copy all the things you need as we will not return to the original exp anymore.
			Vertex fetchedExpMainData = Util.traverseOnce(fetchedExpRequirementGeneral, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);

			Vertex fetchedResultGeneral = Util.traverseOnce(fetchedExpMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
			Vertex fetchedResultData = Util.traverseOnce(fetchedResultGeneral, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
			ArrayList<Vertex> results = Util.traverse(fetchedResultData, Direction.OUT, DBCN.E.result);
			ArrayList<Long> resultStartTimeOffset = Util.traverseGetStartTimeOffset(fetchedResultData,
					Direction.OUT, DBCN.E.result);
			double resultPolyValSum = 0d;
			for (int i=0; i<results.size(); i++) {
				resultPolyValSum += (double)results.get(i).getProperty(LP.polyVal);

				//Commit retry model.
				boolean txError2 = true;
				int txRetried2 = 0;
				while (txError2) {
					if (txRetried2 > dbErrMaxRetryCount) {
						throw new IllegalStateException("Failed to complete transaction after number of retry:"
								+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
					}
					else if (txError2) {
						if (txRetried2 != 0) {
							Util.sleep(dbErrRetrySleepTime);
						}
						txRetried2++;
					}
					txGraph.begin();

					expPredictionData = Util.vReload(expPredictionData, txGraph);
					expResultData = Util.vReload(expResultData, txGraph);

					Vertex resultReloaded = Util.vReload(results.get(i), txGraph);

					Edge expPredictionDataEdge = expPredictionData.addEdge(DBCN.E.prediction, resultReloaded);
					expPredictionDataEdge.setProperty(LP.startTimeOffset, resultStartTimeOffset.get(i));
					//This is copied and will be replaced with real result later by WM after PaRc passes.
					Edge expResultDataEdge = expResultData.addEdge(DBCN.E.result, resultReloaded);
					expResultDataEdge.setProperty(LP.startTimeOffset, resultStartTimeOffset.get(i));
					txError2 = txGraph.finalizeTask(true);
				}
			}
			assert Util.traverse(expResultData, Direction.OUT, DBCN.E.result).size() == results.size() : expResultData;

			//Set and forward polyVal.
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

				expPredictionGeneral = Util.vReload(expPredictionGeneral, txGraph);
				expResultGeneral = Util.vReload(expResultGeneral, txGraph);
				expRequirementGeneral = Util.vReload(expRequirementGeneral, txGraph);
				expMainGeneral = Util.vReload(expMainGeneral, txGraph);

				expPredictionGeneral.setProperty(LP.polyVal, resultPolyValSum);
				expResultGeneral.setProperty(LP.polyVal, resultPolyValSum);
				expRequirementGeneral.setProperty(LP.polyVal, requirementPolyValSum);

				//Exp main polyVal will be requirement + result polyVals. Divide by 2 to average it.
				expMainGeneral.setProperty(LP.polyVal, (requirementPolyValSum + resultPolyValSum ) / 2d);

				//Add the expMain polyVal value to the globalDist calculation scheme.
				STMClient.addDist((double) expMainGeneral.getProperty(LP.polyVal), DBCN.V.general.exp.cn, txGraph);

				txError2 = txGraph.finalizeTask(true);
			}

			//After done using the original exp, replace it with the new exp's requirement part. So tree now contains
			//only the modified latest elements.
			//Commit retry model.
			boolean txError3 = true;
			int txRetried3 = 0;
			while (txError3) {
				if (txRetried3 > dbErrMaxRetryCount) {
					throw new IllegalStateException("Failed to complete transaction after number of retry:"
							+ dbErrMaxRetryCount + " with sleep duration of each:" + dbErrRetrySleepTime);
				}
				else if (txError3) {
					if (txRetried3 != 0)
						Util.sleep(dbErrRetrySleepTime);
					txRetried3++;
				}
				txGraph.begin();

				generalVertex = Util.vReload(generalVertex, txGraph);
				expRequirementGeneral = Util.vReload(expRequirementGeneral, txGraph);
				session = Util.vReload(session, txGraph);

				//Remove the tree's original requirementGeneralVertex. It should be guaranteed 1 element only as we had checked upstair
				//during requirement check phrase, the beginning of this clause.
				Iterator<Edge> itr = generalVertex.getEdges(Direction.OUT, DBCN.E.data).iterator();
				Edge toBeRemoved = null;
				if (itr.hasNext()) {
					toBeRemoved = itr.next();

					if (toBeRemoved == null)
						throw new IllegalStateException("Warning: toBeRemoved edge is null");

					if (itr.hasNext())
						throw new IllegalStateException("mainConvergence cannot have more than 1 data edges to any other expRequirement as "
								+ "it will make the it ambiguous. Edge info: " + itr.next().toString());
				}
				else
					throw new IllegalStateException("At SCCRS: DB inconsistent error, generalVertex(mainConvergence) has no '" + DBCN.E.data
							+ "' edge to its assigned requirement or result. generalVertex identity: " + generalVertex);

				//Hotfix 1, as we had already checked state during startup, demandRequirementVertexList size must be 1, and made sure
				//no intermediate changes occurs, it still tells us that it is null, so we really cant do anything else.
				//But to just treat it as successful. The remove() function of the DB seems to be immune to transaction.
				try {
					toBeRemoved.remove();
				}
				catch (NullPointerException npe) {
					if (loggerSet)
						logger.log(logCredential, LVL.WARN, CLA.INTERNAL
							, "NullPointerException when removing edge at SCCRS. Ignored.");
				}

				generalVertex.addEdge(DBCN.E.data, expRequirementGeneral);

				//generalVerex is the original secondary vertex, initialize their solution size here.
				generalVertex.setProperty(LP.totalSolutionSize, totalRequirementSize);
				generalVertex.setProperty(LP.remainingSolutionToBeCompleted, totalRequirementSize - matched.size());
				//Set the not matched data to the secondary vertex given. Index here is the expanded exp's original ordering, or LTM which
				//only have 1 element (where its index will always be 0, aka first in the array term).
				generalVertex.setProperty(LP.realityPairFailedIndexList, Util.kryoSerialize(notMatched));

				//Update the remaining solution to be SCCRS-ed then forward it to DM if all of them is done.
				//Return upward once to reduce 1 count to tell them that SCCRS has completed.
				Vertex parentMainConvergence = Util.traverseOnce(generalVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);
				int parentSCCRSCompletedCount = parentMainConvergence.getProperty(LP.SCCRSCompletedSize);
				int parentTotalSolutionSize = parentMainConvergence.getProperty(LP.totalSolutionSize);
				int updatedParentSCCRSCompletedCount = parentSCCRSCompletedCount + 1;
				parentMainConvergence.setProperty(LP.SCCRSCompletedSize, updatedParentSCCRSCompletedCount);

				if (updatedParentSCCRSCompletedCount > parentTotalSolutionSize)
					throw new IllegalStateException("Completed Count cannot be larger than total solution count. CompletedCount: "
							+ updatedParentSCCRSCompletedCount + "  totalSolutionCount: " + parentTotalSolutionSize);

				//If all requirement are matched, return upward and then see how the upper layer work. If he is completed as well,
				//return upward again. Until reaches the absolute end. Outsource it to avoid concurrent modification error.
				//THIS IS THE INVOCATION part, this will invoke DM task, which will then decide route, if there is still solution left, the DM will
				//not be invoked. Else if the remaining solution size is -1, it will also NOT trigger any DM reaction, it will just save the data,
				//then exit. -1 means the solution has already changed route.
				//If the total completed solution size is equal to the total solution size, means we have completed all solution.
				if (updatedParentSCCRSCompletedCount == parentTotalSolutionSize) {
					//The general vertex is the given secondary vertex, convert it back to its mainConvergence to simplify DM operation
					//and also keep the fact that this secondary convergence triggered the DM invisible to the DM, encapsulation purpose.
					Vertex newACTGDRTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.ACTGDR.task.cn, DBCN.V.jobCenter.crawler.ACTGDR.task.cn);
					Vertex ACTGDRTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
					newACTGDRTask.addEdge(DBCN.E.source, ACTGDRTaskDetailVertex);
					ACTGDRTaskDetailVertex.addEdge(DBCN.E.source, parentMainConvergence);
					ACTGDRTaskDetailVertex.addEdge(DBCN.E.session, session);

					TaskDetail ACTGDRTaskDetail = new TaskDetail();
					ACTGDRTaskDetail.jobId = "-1";
					ACTGDRTaskDetail.jobType = CRAWLERTASK.DM_ACTGDR;
					ACTGDRTaskDetail.source = "";
					ACTGDRTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.ACTGDR.processing.cn;
					ACTGDRTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.ACTGDR.completed.cn;
					ACTGDRTaskDetail.replyAddr = DBCN.V.devnull.cn;
					ACTGDRTaskDetail.start = -1;
					ACTGDRTaskDetail.end = -1;
					ACTGDRTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(ACTGDRTaskDetail) );
				}
				txError3 = txGraph.finalizeTask(true);
			}

			//Start a new transaction to avoid retry induced data inconsistency at GCA site. To guarantee idempotent.
			txGraph.begin();
			//Add the newly created exp to the GCA queue to be grouped into GCA.
			STMClient.expAddToGCAQueue(expMainGeneral, txGraph);
			txGraph.finalizeTask();
		}

		//Else if they are LTM, including POFeedback and PI (physical input, physical output).
		else if (Util.equalAny(demandType, LTM.GENERAL)) {
			Vertex returnResult = STMClient.checkRidExistInWorkingMemory(demandRequirementVertex, txGraph);

			boolean exist = true;
			if (returnResult == null)
				exist = false;

			if (exist)
				matched.add(0);
			else
				notMatched.add(0);

			//NOTE: Dont have to add polyVal to polyVal update column as it had already been done during the LTM creation.

			//Copied from above with slight modification.
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

				generalVertex = Util.vReload(generalVertex, txGraph);

				//Only replace the data with the latest data if the vertex actually exist in WM.
				if (exist) {
					//Remove the tree's original requirementGeneralVertex. It should be guaranteed 1 element only as we had checked upstair
					//during requirement check phrase, the beginning of this clause.
					Iterator<Edge> itr = generalVertex.getEdges(Direction.OUT, DBCN.E.data).iterator();
					Edge toBeRemoved = null;
					if (itr.hasNext()) {
						toBeRemoved = itr.next();

						if (toBeRemoved == null)
							throw new IllegalStateException("Warning: toBeRemoved edge is null");

						if (itr.hasNext())
							throw new IllegalStateException("mainConvergence cannot have more than 1 data edges to any other expRequirement as "
									+ "it will make the it ambiguous. Edge info: " + itr.next().toString());
					}
					else
						throw new IllegalStateException("At SCCRS: DB inconsistent error, generalVertex(mainConvergence) has no '" + DBCN.E.data
								+ "' edge to its assigned requirement or result. generalVertex identity: " + generalVertex);

					//Hotfix 1, as we had already checked state during startup, demandRequirementVertexList size must be 1, and made sure
					//no intermediate changes occurs, it still tells us that it is null, so we really cant do anything else.
					//But to just treat it as successful. The remove() function of the DB seems to be immune to transaction.
					try {
						toBeRemoved.remove();
					}
					catch (NullPointerException npe) {
						if (loggerSet)
							logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"NullPointerException when removing edge at SCCRS. Ignored.");
					}

					generalVertex.addEdge(DBCN.E.data, returnResult);
				}

				//generalVerex is the original secondary vertex, initialize their solution size here.
				generalVertex.setProperty(LP.totalSolutionSize, totalRequirementSize);
				generalVertex.setProperty(LP.remainingSolutionToBeCompleted, totalRequirementSize - matched.size());
				//Set the not matched data to the secondary vertex given. Index here is the expanded exp's original ordering, or LTM which
				//only have 1 element (where its index will always be 0, aka first in the array term).
				generalVertex.setProperty(LP.realityPairFailedIndexList, Util.kryoSerialize(notMatched));

				//Update the remaining solution to be SCCRS-ed then forward it to DM if all of them is done.
				//Return upward once to reduce 1 count to tell them that SCCRS has completed.
				Vertex parentMainConvergence = Util.traverseOnce(generalVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);
				int parentSCCRSCompletedCount = parentMainConvergence.getProperty(LP.SCCRSCompletedSize);
				int parentTotalSolutionSize = parentMainConvergence.getProperty(LP.totalSolutionSize);
				int updatedParentSCCRSCompletedCount = parentSCCRSCompletedCount + 1;
				parentMainConvergence.setProperty(LP.SCCRSCompletedSize, updatedParentSCCRSCompletedCount);

				if (updatedParentSCCRSCompletedCount > parentTotalSolutionSize)
					throw new IllegalStateException("Completed Count cannot be larger than total solution count. CompletedCount: "
							+ updatedParentSCCRSCompletedCount + "  totalSolutionCount: " + parentTotalSolutionSize);

				//If all requirement are matched, return upward and then see how the upper layer work. If he is completed as well,
				//return upward again. Until reaches the absolute end. Outsource it to avoid concurrent modification error.
				//THIS IS THE INVOCATION part, this will invoke DM task, which will then decide route, if there is still solution left, the DM will
				//not be invoked. Else if the remaining solution size is -1, it will also NOT trigger any DM reaction, it will just save the data,
				//then exit. -1 means the solution has already changed route.
				//If the total completed solution size is equal to the total solution size, means we have completed all solution.
				if (updatedParentSCCRSCompletedCount == parentTotalSolutionSize) {
					//The general vertex is the given secondary vertex, convert it back to its mainConvergence to simplify DM operation
					//and also keep the fact that this secondary convergence triggered the DM invisible to the DM, encapsulation purpose.
					Vertex newACTGDRTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.ACTGDR.task.cn, DBCN.V.jobCenter.crawler.ACTGDR.task.cn);
					Vertex ACTGDRTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
					newACTGDRTask.addEdge(DBCN.E.source, ACTGDRTaskDetailVertex);
					ACTGDRTaskDetailVertex.addEdge(DBCN.E.source, parentMainConvergence);
					ACTGDRTaskDetailVertex.addEdge(DBCN.E.session, session);

					TaskDetail ACTGDRTaskDetail = new TaskDetail();
					ACTGDRTaskDetail.jobId = "-1";
					ACTGDRTaskDetail.jobType = CRAWLERTASK.DM_ACTGDR;
					ACTGDRTaskDetail.source = "";
					ACTGDRTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.ACTGDR.processing.cn;
					ACTGDRTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.ACTGDR.completed.cn;
					ACTGDRTaskDetail.replyAddr = DBCN.V.devnull.cn;
					ACTGDRTaskDetail.start = -1;
					ACTGDRTaskDetail.end = -1;
					ACTGDRTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(ACTGDRTaskDetail) );
				}

				txError = txGraph.finalizeTask(true);
			}
		}
		//These are the types that are explicitly not supported.
		else if (demandType.equals(DBCN.V.general.exp.cn) || demandType.equals(DBCN.V.general.exp.prediction.cn)
				|| Util.equalAny(demandType, GCA.GENERAL)) {
			throw new UnsupportedOperationException("At SCCRS, exp type generalMain, prediction and GCA are not supported."
					+ " As their type will not be used within convergence scheme. Received type:" + demandType);
		}
		else
			throw new IllegalStateException("At SCCRS, unknown OR unexpected type:" + demandType + " RID:" + demandRequirementVertex);
	}
}
