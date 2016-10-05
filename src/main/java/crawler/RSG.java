package crawler;

import java.util.ArrayList;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.orientechnologies.orient.core.exception.OSchemaException;

import ICL.ICL;
import gca.GCA;
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
/*
 * Design Note:
 * RSG will generate solution on demand, and only work with mainConvergence. It will also generate DM required data and store them
 * within its own given mainConvergence vertex.
 * SCCRS will accept data from RSG, then do a pairing, after that make a report about what had matched what had not within its own
 * secondary vertex that RSG had given to him. If SCCRS's work at its time is the last secondary convergence of the whole series setup
 * by the RSG that shares whom all the secondary convergence share the same parent to the mainConvergence, it will invoke DM (ACTGDR)
 * to make decision.
 * That DM will run and select a solution that he think is the best at the moment, then it will create another series of new main
 * convergence vertex, one for each expanded requirement (1 solution has 1 expRequirementGeneral that stores series of requirement data
 * inside it, we here create 1 main convergence for each of them), then create another RSG to work with it and continue on the cycle.
 *
 * RSG sometimes cannot generate any solution, he can post an error message to the DM (RSGFSB) to invalidate the branch he is
 * currently working on and that DM will choose the next best available path (neighboring branch). If no more path is available,
 * then he will become idle and the whole structure will be unresponsive, but the data and exp will still be consolidated, therefore
 * next time similar condition occur, he know what to do.
 */
/**
 * Recursive Solution Generation (RSG)
 * Recursively generate a set of solutions based on experiences that are dynamically reconstructed or directly fetched (if already
 * computed from WM) for the mainConvergence data given to us. After you diversified the solution (get all the possible solution) and
 * setup secondaryConvergence for each solution, forward it to SCCRS to check the viability of the solution.
 * If exception occurs, dynamically generate the solution to the exception. Add ability to signal other RSG to halt and change focus
 * when you have selected particular branch.
 * The load will indeed shorten over time, but without actual WM references, he can hardly make any reasonable move as he might include
 * some solution that was not reasonable, but this promote creativity. He can by default see the statistic of the route he want to take
 * via experience, including its globalDist, limits and threshold.
 * TODO: Allow dynamic switch between pre-WM requirement check, which will improve seek performance but reduce creativity.
 * TODO: Essential TODO: missing capability.
 * exp ICL capability during RSG, so you actually finds an exp that can best describe most of the elements, instead of working on the
 * individual element itself blindly. Seek bigger exp first, if all else fail then you do individual one.
 * Now all we do it only individual one, which is the most basic.
 */
/*
 * NOTE For convergence updates:
 * CONVERGENCE can contain parallel solution, therefore each convergence should tell us what route they had chosen to compute.
 * This is to shorten computation routine, else you will have to expand all branches, although it might give you better result
 * as you were given things to compare to, but it will take forever to complete.
 * Starting after the 'first' convergence main, the empty one, its selected route will be the route you chosen via myriad of solutions,
 * and then the secondary should contain what route he had chosen for its child, and so on.
 */
public class RSG {
	private long dbErrMaxRetryCount;
	private long dbErrRetrySleepTime;

	public RSG(long dbErrMaxRetryCount, long dbErrRetrySleepTime) {
		this.dbErrMaxRetryCount = dbErrMaxRetryCount;
		this.dbErrRetrySleepTime = dbErrRetrySleepTime;
	}

	/**
	 * RSG return type from multiple exit, it uses this unified interface, then user is required to deduce what data he needs on his own.
	 * Can be forwarded to next task directly as it has built in deduction logic.
	 */
	public class RSGMultiReturn {
		public final ArrayList<Vertex> newSecondaryConvergenceForSolutions;
		public final Vertex newSecondaryConvergenceForSolution;
		public final int exitRoute;

		public RSGMultiReturn(ArrayList<Vertex> newSecondaryConvergenceForSolutions) {
			this.newSecondaryConvergenceForSolutions = newSecondaryConvergenceForSolutions;
			this.newSecondaryConvergenceForSolution = null;
			this.exitRoute = 0;
		}
		public RSGMultiReturn(Vertex newSecondaryConvergenceForSolution) {
			this.newSecondaryConvergenceForSolutions = null;
			this.newSecondaryConvergenceForSolution = newSecondaryConvergenceForSolution;
			this.exitRoute = 1;
		}
		public RSGMultiReturn() {
			this.newSecondaryConvergenceForSolutions = null;
			this.newSecondaryConvergenceForSolution = null;
			this.exitRoute = 2;
		}
	}

	/**
	 * Find potential solution from demand vertex for expRequirement or expResult type only.
	 * @param demand Demand vertex can either be an exp requirement/result or a LTM general entry.
	 * @return If available, the potential solution list, else return null.
	 */
	public ArrayList<Vertex> findPotentialSolutionFromDemandForRequirementOrResultType(Vertex demand) {
		//Occurrence(Sibling) that shares the same parent as you do. It is always ordered latest first in DB. Every sibling is a potential solution.
		//Note: Expect to get a list of latest vertexes ordered by newest first.
		try {
			//TODO: modify it to make use of index, do NOT fetch all the solution. Now we do fetch all, then filter it on the spot
			//right below by comparing its occurrence against the demand.
			ArrayList<Vertex> solutions = Util.traverseGetOccurrence(demand);

			//Traverse to its generalResult vertex side, as the following code is expecting it to be expResultGeneral, where
			//it will share code with LTM.
			ArrayList<Vertex> potentialSolutions = new ArrayList<Vertex>();
			for (Vertex solution : solutions) {
				String solutionType = solution.getCName();

				//Only treat them as solution if they are type exp.
				if (solutionType.equals(DBCN.V.general.exp.requirement.cn)) {
					//Traverse from requirement general type to result general type.
					Vertex expMainData = Util.traverseOnce(solution, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
					Vertex resultExpGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.result, DBCN.V.general.exp.result.cn);
					potentialSolutions.add(resultExpGeneral);
				}
				else if (solutionType.equals(DBCN.V.general.exp.result.cn))
					potentialSolutions.add(solution);
				else if (solutionType.equals(DBCN.V.general.exp.cn))
					throw new IllegalArgumentException("At RSG: do not accept type: " + solutionType);
				else
					throw new IllegalArgumentException("At RSG: Unsupported type: " + solutionType);
			}
			return potentialSolutions;
		}
		catch (OSchemaException e) {
			return null;
		}
	}
	/**
	 * Find potential solution from demand vertex for LTM type only.
	 * @param demand Demand vertex can either be an exp requirement/result or a LTM general entry.
	 * @return If available, the potential solution list, else return null.
	 */
	public ArrayList<Vertex> findPotentialSolutionFromDemandForLTMType(Vertex demand) {
		//Traverse to potential exp first, exp gets priority.
		//TODO: Should uses INDEX to speed up the search, it will take forever using full scan.
		//demand here is a general LTM vertex which hold edges to whoever used him as exp result during exp creation.
		//If exception occurs, mean it doesn't have any relevant exp entry, thus it will not be processed as exp but as GCA.
		//TODO: Make sure to seek its siblings as well to diversify the solution size.
		try {
			ArrayList<Vertex> potentialSolutions = Util.traverse(demand, Direction.IN, DBCN.E.result);
			return potentialSolutions;
		}
		catch (OSchemaException e) {
			return null;
		}
	}

	/**
	 * Setup a new secondaryConvergence for each solution.
	 */
	private Vertex setupNextSecondaryConvergenceTxL(Vertex generalVertex, Vertex requirementGeneral, int originalOrdering, Graph txGraph) {
		Vertex newSecondaryConvergenceForSolution = null;
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

			requirementGeneral = Util.vReload(requirementGeneral, txGraph);
			generalVertex = Util.vReload(generalVertex, txGraph);

			//Note: SecondaryConvergence's solution size will be set at SCCRS not here as he will expand the data at his premises.
			newSecondaryConvergenceForSolution = txGraph.addVertex(DBCN.V.general.convergenceSecondary.cn, DBCN.V.general.convergenceSecondary.cn);
			newSecondaryConvergenceForSolution.addEdge(DBCN.E.data, requirementGeneral);
			//Add edge to its mainConvergence to form a linked tree.
			newSecondaryConvergenceForSolution.addEdge(DBCN.E.parent, generalVertex);
			newSecondaryConvergenceForSolution.setProperty(LP.originalOrdering, originalOrdering);

			txError = txGraph.finalizeTask(true);
		}
		return newSecondaryConvergenceForSolution;
	}

	private void forwardToSCCRSTxE(ArrayList<Vertex> newSecondaryConvergenceForSolutions, Vertex session, Graph txGraph) {
		for (int i=0; i<newSecondaryConvergenceForSolutions.size(); i++) {
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
				//NOTE: SCCRS results will be available directly on the secondary vertex that you will be creating below.
				//Did so to ensure we can get to the data and at the mean time also understand where did it come from.
				//Therefore we DONT have to do additional bookkeeping data in order to uniquely identify them.

				//Setup secondary convergence for each requirements and send it to SCCRS. Then SCCRS will do its job and forward it to ACTGDR
				//to decide which route to take next.
				//Send the requirement as a whole without opening it up by 1 layer to expose its actual requirements.
				//At the SCCRS side, it will expand it before beginning its pairing, then the report will be in term of the opened requirement,
				//the given original requirement (unopened general exp requirement vertex) will not be calculated.
				//Then at the DM side after SCCRS returns, it will read the report and setup new mainConvergence correctly, and forward to
				//here (RSG) again, then the cycle continues until all are scheduled.
				txGraph.begin();

				Vertex newSCCRSTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.SCCRS.task.cn, DBCN.V.jobCenter.crawler.SCCRS.task.cn);
				Vertex SCCRSTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
				newSCCRSTask.addEdge(DBCN.E.source, SCCRSTaskDetailVertex);
				SCCRSTaskDetailVertex.addEdge(DBCN.E.source, Util.vReload(newSecondaryConvergenceForSolutions.get(i), txGraph));
				SCCRSTaskDetailVertex.addEdge(DBCN.E.session, session);

				TaskDetail SCCRSTaskDetail = new TaskDetail();
				SCCRSTaskDetail.jobId = "-1";
				SCCRSTaskDetail.jobType = CRAWLERTASK.DM_SCCRS;
				SCCRSTaskDetail.source = "";
				SCCRSTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.SCCRS.processing.cn;
				SCCRSTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.SCCRS.completed.cn;
				SCCRSTaskDetail.replyAddr = DBCN.V.devnull.cn;
				SCCRSTaskDetail.start = -1;
				SCCRSTaskDetail.end = -1;
				SCCRSTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(SCCRSTaskDetail) );
				txError = txGraph.finalizeTask(true);
			}
		}
	}

	private void forwardToSCCRSTxE(Vertex generalVertex, Vertex newSecondaryConvergenceForSolution, Vertex session, Graph txGraph) {
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
			Vertex newSCCRSTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.SCCRS.task.cn, DBCN.V.jobCenter.crawler.SCCRS.task.cn);
			Vertex SCCRSTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
			newSCCRSTask.addEdge(DBCN.E.source, SCCRSTaskDetailVertex);
			SCCRSTaskDetailVertex.addEdge(DBCN.E.source, Util.vReload(newSecondaryConvergenceForSolution, txGraph));
			SCCRSTaskDetailVertex.addEdge(DBCN.E.session, session);

			TaskDetail SCCRSTaskDetail = new TaskDetail();
			SCCRSTaskDetail.jobId = "-1";
			SCCRSTaskDetail.jobType = CRAWLERTASK.DM_SCCRS;
			SCCRSTaskDetail.source = "";
			SCCRSTaskDetail.processingAddr = DBCN.V.jobCenter.crawler.SCCRS.processing.cn;
			SCCRSTaskDetail.completedAddr = DBCN.V.jobCenter.crawler.SCCRS.completed.cn;
			SCCRSTaskDetail.replyAddr = DBCN.V.devnull.cn;
			SCCRSTaskDetail.start = -1;
			SCCRSTaskDetail.end = -1;
			SCCRSTaskDetailVertex.setProperty(LP.data, Util.kryoSerialize(SCCRSTaskDetail) );

			//The mainConvergence given had not set its total solution size yet, this will store that value then set it to the DB.
			//Note that it has no remainingSolutionToBeCompleted as he only store links to solution, it itself is not a solution.
			//1 as LTM is the only solution and we had tried to find other solution for this LTM but no luck during upper operation where
			//we seek for its exp counterparts but failed.
			generalVertex = Util.vReload(generalVertex, txGraph);
			generalVertex.setProperty(LP.totalSolutionSize, 1);
			//Send it to SCCRS, if SCCRS completed, then it will add this value by 1.
			generalVertex.setProperty(LP.SCCRSCompletedSize, 0);

			txError = txGraph.finalizeTask(true);
		}

		if (Util.traverse(generalVertex, Direction.IN, DBCN.E.parent).size() > 1) {
			throw new IllegalStateException(Util.traverse(generalVertex, Direction.IN, DBCN.E.parent).size() + "!=" + "1 " + generalVertex);
		}
	}

	private void forwardToRSGFSBTxE(Vertex generalVertex, Vertex session, Graph txGraph) {
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

			//Setup task to the DM system, telling him we failed. Its remaining step will be set to -1 by the DM to indicate that he had
			//been abandoned and switched branch.
			Vertex newRSGFSBTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.RSGFSB.task.cn, DBCN.V.jobCenter.crawler.RSGFSB.task.cn);
			Vertex RSGFSBTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
			newRSGFSBTask.addEdge(DBCN.E.source, RSGFSBTaskDetailVertex);
			RSGFSBTaskDetailVertex.addEdge(DBCN.E.source, generalVertex);
			RSGFSBTaskDetailVertex.addEdge(DBCN.E.session, session);

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

			//The mainConvergence given to us had not set its total solution size yet, this will store that value then set it to the DB.
			//Note that it has no remainingSolutionToBeCompleted as he only store links to solution, it itself is not a solution.
			//No solution at all, just switch branch.
			generalVertex.setProperty(LP.totalSolutionSize, 0);
			//We send it to SCCRS, if SCCRS completed, then it will add this value by 1.
			generalVertex.setProperty(LP.SCCRSCompletedSize, 0);

			txError = txGraph.finalizeTask(true);
		}
	}

	/**
	 * Deduce return type of main core logic and decide which path to forward.
	 */
	public void deduceAndForwardToNextTaskTxE(RSGMultiReturn result, Vertex generalVertex, Vertex taskDetailVertex, Graph txGraph) {
		//Forward inherited session data.
		Vertex session = Util.traverseOnce(taskDetailVertex, Direction.OUT, DBCN.E.session);

		if (result.exitRoute == 0)
			forwardToSCCRSTxE(result.newSecondaryConvergenceForSolutions, session, txGraph);
		else if (result.exitRoute == 1)
			forwardToSCCRSTxE(generalVertex, result.newSecondaryConvergenceForSolution, session, txGraph);
		else if (result.exitRoute == 2)
			forwardToRSGFSBTxE(generalVertex, session, txGraph);
	}

	/**
	 * Execute the core logic and returns the credential needed to forward to next stage.
	 * @param generalVertex
	 * @param txGraph
	 * @return
	 */
	public RSGMultiReturn execute(Vertex generalVertex, Graph txGraph) {
		//TODO: CHECK whether parents is still active DWDM or not. If not, save states and terminate. tell convergence point about it.
		//-generalVertex is a mainConvergence vertex containing the data vertex you need to expand its requirement and calculate solutions for.
		//Get the demand vertex that were wished to be recreated.
		if (!generalVertex.getCName().equals(DBCN.V.general.convergenceMain.cn))
			throw new IllegalArgumentException("At RSG: Expecting input type: "+ DBCN.V.general.convergenceMain.cn + " but get: " + generalVertex);

		Vertex demand = null;
		try {
			//generalVertex is a mainConvergence vertex where he has a 'data' edge to the actual demand vertex.
			//demand vertex can either be an exp requirement/result or a LTM general entry.
			demand = Util.traverseOnce(generalVertex, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);
		}
		catch (OSchemaException e) {
			throw new IllegalStateException("Demand not available but expected in recursive solution generating step. Most likely STISS failed "
					+ "to setup the vertex correctly.");
		}

		String demandType = demand.getCName();

		boolean useExp = true;
		boolean defaultIsExp = false;
		boolean isLTM = false;
		ArrayList<Vertex> potentialSolutions = null;

		if (demandType.equals(DBCN.V.general.exp.requirement.cn) || demandType.equals(DBCN.V.general.exp.result.cn)) {
			defaultIsExp = true;
			potentialSolutions = findPotentialSolutionFromDemandForRequirementOrResultType(demand);
			if (potentialSolutions == null)
				useExp = false;
		}

		/*
		 * This is the most important part, exp generation depends on this as this is the first interface that GCA and LTM data
		 * will land, it will extract all the requirement, setup properties, and forward it to the next step as experience.
		 */
		//If it is GCA, attempt to find exp that are similar to this GCA requirements. If not available, just extract and create new
		//shortened exp, then use that exp instead.
		else if (Util.equalAny(demandType, GCA.GENERAL)) {
			//Extract and form an exp based on other exp of exp, your exp on how to build exp will be put into test.
			//Does not support direct GCA input, as it make no sense at all, it should always have a LTM head, where it will serve as a
			//hint and as a keypoint.
			useExp = false;
			throw new UnsupportedOperationException("GCA General is not supported in RSG as we cannot possibly seek main point from GCA,"
					+ "which is too broad.");
			//TODO: Actually we may attempt to locate that preference spot by scanning all GCA, then seek any data that matches the
			//threshold of globalDist, but it will be bad and not synchronized with the whole big picture, where the big picture might
			//already have some ongoing sync work that should execute in order.
		}

		//Attempt to find its exp counterpart in terms of result first, if exp not available, use GCA instead, which will certainly
		//be available at any time by design.
		else if (Util.equalAny(demandType, LTM.GENERAL)) {
			isLTM = true;
			potentialSolutions = findPotentialSolutionFromDemandForLTMType(demand);
			if (potentialSolutions == null || potentialSolutions.isEmpty())
				useExp = false;
		}
		else
			throw new IllegalStateException("Unknown or unsupported work type:" + demandType + " at Crawler RSG.");

		//NOTE: For LTM data we don't have any preferred route, have to scan them all to find the wanted route, unlike any exp
		//instance where it will have requirement or result, a direct recommended route.
		//If use exp to generate solution. Include diversification by including other exp into account and replace as needed to broaden
		//neighboring exp usage and promote creativity.
		if (useExp) {
			//Potential solution cannot be empty as the following operation requires it.
			if (!potentialSolutions.isEmpty()) {
				//Double is the matching percentage, integer is the original ordering of vertexes in the original occurrence list.
				Multimap<Double, Integer> matchingScoreList = HashMultimap.create();

				//Extract those qualified results into another list.
				//By qualified it means better than minimum threshold, larger than 25% matches OR it is result extracted from LTM.
				//TODO: Avoid hardcoding values, use globalVariance instead.
				ArrayList<Vertex> qualifiedResults = new ArrayList<Vertex>();

				//Compare each of their result against the result we wanted, if it matches, then it will be preferred.
				//Note: exp from LTM will not be compared against demand as LTM is small and single element, its results are guaranteed
				//to achieve the demand anyway else it would not have the result edge in the first place.
				//Exp is different as it may comprise of more than 1 element, thus need to be compared to find similarity.
				if (defaultIsExp) {
					ArrayList<Double> scoreListBeforeEnteringMap = ICL.General.compareOccurrenceResult(demand, potentialSolutions);
					for (int i=0; i<scoreListBeforeEnteringMap.size(); i++)
						matchingScoreList.put(scoreListBeforeEnteringMap.get(i), i);

					//Get all the sorted treemap's entry into the format we wanted. Score and originalOrderingBasedOnScore shares the same
					//index, where the first element of score correspond to the first element in originalOrderingBasedOnScore and so on.
					ArrayList<Double> scores = new ArrayList<Double>( matchingScoreList.keys() );
					ArrayList<Integer> originalOrderingBasedOnScore = new ArrayList<Integer>( matchingScoreList.values() );

					for (int i=scores.size()-1; i>=0; i--) {
						double currentScore = scores.get(i);
						int currentOriginalOrderingIndex = originalOrderingBasedOnScore.get(i);

						if (currentScore > 25d) {
							qualifiedResults.add(potentialSolutions.get(currentOriginalOrderingIndex));
						}
					}
				}

				//Stores LTM extracted result as potentialSolutions, now just have to forward it to qualifiedResults as it is
				//qualified by default, for sure going to satisfy the result as it has 'result' edge to the original LTM general vertex.
				//If we run through here, the potentialSolutions will be type expResultData, we want expResultGeneral vertex instead
				//as all the following operations are expecting it.
				else {
					for (Vertex v : potentialSolutions) {
						Vertex expResultGeneral = Util.traverseOnce(v, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.result.cn);
						if (expResultGeneral == null)
							throw new IllegalStateException("expResultGeneral cannot be null, vertex is: " + v);
						qualifiedResults.add(expResultGeneral);
					}
				}

				if (qualifiedResults.isEmpty())
					throw new IllegalStateException("At RSG: qualified result size cannot be 0.");

				//Now we have a list of potential results that are matched to certain level, we will see which of its requirement is more
				//feasible. These solutions may or may not include the default solution depending on whether defaultIsExp is true or not.
				//True then include, false otherwise. Treat it as a normal solution from now on, without caring whether it is default or not.
				ArrayList<Vertex> requirementGeneralVertexes = new ArrayList<Vertex>();

				for (int i=0; i<qualifiedResults.size(); i++) {
					Vertex expMainData = Util.traverseOnce(qualifiedResults.get(i), Direction.OUT, DBCN.E.result);
					if (!expMainData.getCName().equals(DBCN.V.LTM.exp.cn))
						throw new IllegalStateException("Expecting type " + DBCN.V.LTM.exp.cn + " but get " + expMainData);

					Vertex expRequirementGeneral = Util.traverseOnce(expMainData, Direction.IN, DBCN.E.requirement, DBCN.V.general.exp.requirement.cn);
					requirementGeneralVertexes.add(expRequirementGeneral);
				}

				/*
				 * Prepare the required datasets for DM to decide which route to take and compute in the next level.
				 * T only thing that need to be outsourced is the SCCRS part, to match requirements against reality.
				 * Then the DM requirements will be complete.
				 */
				//Filter out solutions that doesn't meet time requirement (Seek most efficient route).
				ArrayList<Long> timeRequirements = new ArrayList<Long>();
				ArrayList<Long> timeRan = new ArrayList<Long>();
				ArrayList<Double> polyVals = new ArrayList<Double>();
				ArrayList<Double> precisionRates = new ArrayList<Double>();

				for (Vertex current : requirementGeneralVertexes) {
					//Get how long the operation requires.
					Vertex expMainData = Util.traverseOnce(current, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
					Vertex expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
					timeRequirements.add( (Long) expMainGeneral.getProperty(LP.duration));
					timeRan.add( (Long) expMainGeneral.getProperty(LP.occurrenceCountPR) );

					//Individual solutions' distribution (polyVal).
					polyVals.add( (Double) expMainGeneral.getProperty(LP.polyVal) );

					//Preferences of certain action. How good the solution is in term of previous success rate.
					precisionRates.add( (Double) expMainGeneral.getProperty(LP.precisionRate));
				}

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

					//-generalVertex here is the mainConvergence vertex given to us.
					//Store into generalVertex to make it available to all other vertexes.
					//Use current convergence to store those data. The convergence here will always be main convergence.
					generalVertex.setProperty(LP.timeRequirementList, Util.kryoSerialize(timeRequirements));
					generalVertex.setProperty(LP.timeRanList, Util.kryoSerialize(timeRan));
					generalVertex.setProperty(LP.polyValList, Util.kryoSerialize(polyVals));
					generalVertex.setProperty(LP.precisionRateList, Util.kryoSerialize(precisionRates));
					//Convert it to its rid form first, then at the other end, refetch it again, do not allow cross border vertex.
					generalVertex.setProperty(LP.requirementList, Util.kryoSerialize( Util.vertexToRid(requirementGeneralVertexes) ));

					//The mainConvergence given to us had not set its total solution size yet, this will store that value then set it to the DB.
					//Note that it has no remainingSolutionToBeCompleted as he only store links to solution, it itself is not a solution.
					generalVertex.setProperty(LP.totalSolutionSize, requirementGeneralVertexes.size());	//qualifiedResults.size() acceptable as well.
					//Send it to SCCRS, if SCCRS completed, then it will add this value by 1.
					generalVertex.setProperty(LP.SCCRSCompletedSize, 0);

					txError = txGraph.finalizeTask(true);
				}

				//Setup the next convergence secondary.
				ArrayList<Vertex> newSecondaryConvergenceForSolutions = new ArrayList<Vertex>();
				for (int i=0; i<requirementGeneralVertexes.size(); i++) {
					newSecondaryConvergenceForSolutions.add(
							setupNextSecondaryConvergenceTxL(generalVertex, requirementGeneralVertexes.get(i), i, txGraph) );
				}

				return new RSGMultiReturn(newSecondaryConvergenceForSolutions);
			}
			else {
				throw new IllegalStateException("potentialSolutions cannot be empty as you had just fetched it already.");
			}
		}
		//Else if it is LTM, forward it to SCCRS to have it paired, it will then return upward from there no matter he succeeded or not.
		else if (isLTM && !useExp) {
			//Setup the next convergence secondary.
			Vertex newSecondaryConvergenceForSolution = setupNextSecondaryConvergenceTxL(generalVertex, demand, 0, txGraph);
			return new RSGMultiReturn(newSecondaryConvergenceForSolution);
		}

		//If it is originally an exp but have no solution (only possible for LTM entry, default exp entry should have default route) OR
		//it is not an exp then use GCA method to generate solution for it.
		//TODO: For now there will be no GCA method, treat it as no solution generatable, as GCA method will be jointed into an
		//exp after it had failed anyway, then we can get it the next time.
		else {
			//Every new entry should check themselves against every index to make sure they index themselves properly.
			//Will always prioiritize over latest. The expansion seek index. seek from family tree, index will have an edge to its family
			//head, then you can choose which index you want to use by looking at its statistic, eg element size, contained element.

			//If after both exp and GCA evaluation still cannot get a reasonable solution, treat this branch as dead.
			//Return upward, then upper layer will trigger to switch branch if available, or halt as he did not know what to do next.
			return new RSGMultiReturn();
		}
	}
}
