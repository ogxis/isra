package crawler;

import java.util.ArrayList;
import java.util.Collections;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

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
 * Amid Convergence Tree Generation Decide Route (ACTGDR)
 * First DM structured logic to decide route. It will decide which solution to continue on generation based on several condition,
 * by generation is means generate further tree structure, it will port the solution branch  he want to RSG, where each individual solutions'
 * requirement are separated into a new mainConvergence, each to an individual instance of RSG to further the generation cycle.
 * TODO: IMPLEMENT Variable central point, aka the point adaptation model, if you stay at a point for too long, you adapt to it,
 * then your modulation range will change accordingly.
 */
public class ACTGDR {
	private long dbErrMaxRetryCount;
	private long dbErrRetrySleepTime;

	public ACTGDR(long dbErrMaxRetryCount, long dbErrRetrySleepTime) {
		this.dbErrMaxRetryCount = dbErrMaxRetryCount;
		this.dbErrRetrySleepTime = dbErrRetrySleepTime;
	}

	/**
	 * ACTGDR return type from multiple exit, it uses this unified interface, then user is required to deduce what data he needs on his own.
	 * Can be forwarded to next task directly as it has built in deduction logic.
	 */
	public class ACTGDRMultiReturn {
		public final Vertex selectedSolution;
		public final ArrayList<Integer> unmetRequirementIndex;
		public final ArrayList<Vertex> unmetRequirementList;
		public final Vertex furtherMainConvergenceVertex;
		public final int exitRoute;

		public ACTGDRMultiReturn(Vertex selectedSolution, ArrayList<Integer> unmetRequirementIndex, ArrayList<Vertex> unmetRequirementList) {
			this.selectedSolution = selectedSolution;
			this.unmetRequirementIndex = unmetRequirementIndex;
			this.unmetRequirementList = unmetRequirementList;
			this.furtherMainConvergenceVertex = null;
			this.exitRoute = 0;
		}
		public ACTGDRMultiReturn(Vertex furtherMainConvergenceVertex) {
			this.selectedSolution = null;
			this.unmetRequirementIndex = null;
			this.unmetRequirementList = null;
			this.furtherMainConvergenceVertex = furtherMainConvergenceVertex;
			this.exitRoute = 1;
		}
	}

	/**
	 * Core route selection logic, reorder route based on their relevance against current global condition.
	 * Best route are queued at the beginning (index 0).
	 * Get all the DM credentials required to make logical assumption on how to reorder routes and reorder based on it.
	 * @return The score list that share the original ordering of the requirement list, so you can deduce who they are by reversing it and
	 * get to the original index of the solution.
	 */
	public ArrayList<Integer> reorderRouteBasedOnGlobalDist(Vertex generalVertex, double globalDist, ArrayList<Vertex> secondaryVertexList, Graph txGraph) {
		//Get all the DM credentials.
		//-generalVertex here is the mainConvergence vertex that contains most of the DM credential, and have edges to other secondary
		//convergence vertexes where each of them represent a solution, and each secondary convergence had been through SCCRS and have
		//their contained requirements paired against reality and result stored in term of list within the convergence vertex itself.

		//This requirement can only be guaranteed valid here once, after that when you revisit this tree in the future, this requirement
		//list integrity can no longer be guaranteed, but you should be able to find its static counterparts in the exp that this tree
		//produce in the end if this branch actually got to be executed in the end.
		ArrayList<String> requirementsRidForm = Util.kryoDeserialize((byte[])generalVertex.getProperty(LP.requirementList), ArrayList.class);
		ArrayList<Vertex> requirements = Util.ridToVertex(requirementsRidForm, txGraph);

		//These data are not yet processed (sorted), it was just directly fetched from requirement list, it retain the original ordering.
		ArrayList<Long> timeRequirements = Util.kryoDeserialize( (byte[])generalVertex.getProperty(LP.timeRequirementList), ArrayList.class);
		ArrayList<Long> timeRans = Util.kryoDeserialize( (byte[])generalVertex.getProperty(LP.timeRanList), ArrayList.class);
		ArrayList<Double> polyVals = Util.kryoDeserialize( (byte[])generalVertex.getProperty(LP.polyValList), ArrayList.class);
		ArrayList<Double> precisionRates = Util.kryoDeserialize( (byte[])generalVertex.getProperty(LP.precisionRateList), ArrayList.class);

		//The score is the array index, its element is the original ordering of the original data.
		ArrayList<Integer> timeRequirementSortedScore = Util.sortGetIndex(timeRequirements, true);
		ArrayList<Integer> timeRanSortedScore = Util.sortGetIndex(timeRans, true);
		ArrayList<Integer> polyValSortedScore = new ArrayList<Integer>();
		ArrayList<Integer> precisionsRateSortedScore = Util.sortGetIndex(precisionRates, true);
		ArrayList<Integer> matchedPercentageSortedScore = new ArrayList<Integer>();

		//Calculate viability of action based on individual solution's matched count.
		ArrayList<Double> matchedPercentages = new ArrayList<Double>();

		assert secondaryVertexList.size() == requirements.size();

		for (int i=0 ; i<secondaryVertexList.size(); i++) {
			Vertex v = secondaryVertexList.get(i);
			int totalSolutionSize = v.getProperty(LP.totalSolutionSize);
			int remainingSolutionSize = v.getProperty(LP.remainingSolutionToBeCompleted);
			int completedSolutionSize = totalSolutionSize - remainingSolutionSize;
			double matchedPercentage = totalSolutionSize != 0 ? ( (double)completedSolutionSize / (double) totalSolutionSize ) * 100d : 0;
			matchedPercentages.add(matchedPercentage);

			assert matchedPercentage >= 0d && matchedPercentage <= 100d : "Matched percentage must be within 0~100 range, got: " + matchedPercentage;
		}
		matchedPercentageSortedScore = Util.sortGetIndex(matchedPercentages, true);

		//Note that polyVal requires special calculation, as it requires variance against latest globalDist, not simple sort.
		//Double is the polyVal variance [ abs(polyVal - current globalDist)],  Integer is the original ordering.
		Multimap<Double, Integer> sortedPolyVals = HashMultimap.create();
		for (int i=0; i<polyVals.size(); i++) {
			double currentVal = polyVals.get(i);
			sortedPolyVals.put( Math.abs(currentVal - globalDist), i);
		}

		//The smaller the variance, the better.
		polyValSortedScore.addAll(sortedPolyVals.values());

		//Central inclination calculation, any solution that leads one closer to the central point.
		//Central inclination promote oscillation of the system interest (globalDist).
		ArrayList<Integer> centralInclinatedSolutionIndex = new ArrayList<Integer>();
		for (int i=0; i<polyVals.size(); i++) {
			double currentVal = polyVals.get(i);

			//If globalDist <50, means at negative side, and currentVal is between any value larger than globalDist but smaller than the
			//center point 50d, then it will lead the overall solution inclined toward center more.
			//45~55 is optimal, 50 is perfect, can hardly perfect. Allow threshold of +-5 instead of absolute 50 to include also the
			//solution that meet the optimal range and beyond its scope range a little (neg to pos and vice versa) to promote
			//oscillation, yet still be able to avoid promoting those solution whom will lead to even extreme condition (<25, >75).
			if (globalDist < 50d) {
				if (currentVal > globalDist && currentVal <= 55d)
					centralInclinatedSolutionIndex.add(i);
			}
			else if (globalDist > 50d) {
				if (currentVal < globalDist && currentVal >= 45d)
					centralInclinatedSolutionIndex.add(i);
			}
			else {
				if (currentVal >= 45d && currentVal <= 55d)
					centralInclinatedSolutionIndex.add(i);
			}
		}

		assert requirements.size() == timeRequirementSortedScore.size() : "ReqSize: " + requirements.size() + " != timeRequirementSortedScore: " + timeRequirementSortedScore.size()
		+ "  OriginalArray: " + timeRequirementSortedScore;
		assert requirements.size() == timeRanSortedScore.size() : "ReqSize: " + requirements.size() + " != timeRanSortedScore: " + timeRanSortedScore.size()
		+ "  OriginalArray: " + timeRanSortedScore;
		assert requirements.size() == polyValSortedScore.size() : "ReqSize: " + requirements.size() + " != polyValSortedScore: " + polyValSortedScore.size()
		+ "  OriginalArray: " + polyValSortedScore;
		assert requirements.size() == precisionsRateSortedScore.size() : "ReqSize: " + requirements.size() + " != precisionsRateSortedScore: " + precisionsRateSortedScore.size()
		+ "  OriginalArray: " + precisionsRateSortedScore;
		assert requirements.size() == matchedPercentageSortedScore.size() : "ReqSize: " + requirements.size() + " != matchedPercentageSortedScore: " + matchedPercentageSortedScore.size()
		+ "  OriginalArray: " + matchedPercentageSortedScore + " ArrayList: " + secondaryVertexList;

		//NOTE: We haven checked the solutions against secondary convergence (viability).
		boolean extreme = false;
		boolean optimal = false;
		boolean greyArea = false;
		boolean nearNegative = false;
		boolean nearPositive = false;

		//Extremely stressful condition, will do things as quick as possible, high alert, heavy use of exp, mind numb.
		if (globalDist >= 75d || globalDist <= 25d) {
			extreme = true;
			if (globalDist <= 25d)
				nearNegative = true;
			else
				nearPositive = true;
		}

		//Stressful condition, amid transition, middle hanging. 25~45 || 55~75. Will be more vigilant, more careful about steps,
		//less interested in creativity. Just try to get things done.
		else if ( (globalDist > 25d && globalDist < 45d) || (globalDist > 55d && globalDist < 75d)) {
			greyArea = true;
			if (globalDist > 25d && globalDist < 45d)
				nearNegative = true;
			else
				nearPositive = true;
		}

		//Normal operating creative range (45 ~ 55). Comfortable mode, most creative period, incentive to try everything.
		else if (globalDist >= 45d && globalDist <= 55d) {
			optimal = true;
		}

		else
			throw new IllegalStateException("At ACTGDR GlobalDist must be within range 0~100 but get:" + globalDist);

		//Its index is equivalent to the actual requirement's index (original ordering), its element will be its composite scores (sum).
		//We will sort it later using treemap, now just record it down. Higher score is bad.
		ArrayList<Integer> scores = new ArrayList<Integer>();
		//Initialize it with 0 as the preceding action will simply sum value to it without checking any further.
		for (int i=0; i<requirements.size(); i++)
			scores.add(0);

		/*
		 * The weight process:
		 * Depending on globalDist, some point may be weighted more heavier than other point and some may be disabled.
		 * Here we will sum all all the scores based on condition. Lowest score is the best.
		 * There will be some minor tweaking in their number to shift their weight.
		 */
		//Extreme will avoid running unique route.
		if (extreme) {
			//Negative will react slower.
			if (nearNegative) {
				//Time score is added by 1 to signify it is not important. Higher score is bad.
				//Near negative will react slower, and time doesn't matter.
				for (int i=0; i<timeRequirementSortedScore.size(); i++) {
					int currentIndex = timeRequirementSortedScore.get(i);
					int currentScore = scores.get(currentIndex);
					scores.set(currentIndex, currentScore + i + 1);
				}
				//Prefer to run usual route, reverse it so the most ran come first.
				Collections.reverse(timeRanSortedScore);
				for (int i=0; i<timeRanSortedScore.size(); i++) {
					int currentIndex = timeRanSortedScore.get(i);
					int currentScore = scores.get(currentIndex);
					scores.set(currentIndex, currentScore + i);
				}
			}

			//High positive will react faster than usual.
			else {
				//Time score is deducted by 1 to signify it is important. Higher score is bad.
				//Note: This may cause some solution's score to become negative value.
				//Time is important during peak period.
				for (int i=0; i<timeRequirementSortedScore.size(); i++) {
					int currentIndex = timeRequirementSortedScore.get(i);
					int currentScore = scores.get(currentIndex);
					scores.set(currentIndex, currentScore + i - 1);
				}
				//Prefer to run usual route, reverse it so the most ran come first.
				Collections.reverse(timeRanSortedScore);
				for (int i=0; i<timeRanSortedScore.size(); i++) {
					int currentIndex = timeRanSortedScore.get(i);
					int currentScore = scores.get(currentIndex);
					scores.set(currentIndex, currentScore + i - 1);
				}
			}

			//These are the must run for both positive and negative state.
			for (int i=0; i<polyValSortedScore.size(); i++) {
				int currentIndex = polyValSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			//Prefer to run usual route, reverse it so the most ran come first.
			Collections.reverse(precisionsRateSortedScore);
			for (int i=0; i<precisionsRateSortedScore.size(); i++) {
				int currentIndex = precisionsRateSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			//Incline toward center is preferred, therefore we decrease its score to promote it. Extreme case will double the
			//necessity of this effect.
			for (int index : centralInclinatedSolutionIndex) {
				int currentScore = scores.get(index);
				scores.set(index, currentScore - 1*2);
			}
			//At extreme, prefer instantly viable route. Original ordering is ascending (less matched first).
			//Reverse it to get most matched first.
			Collections.reverse(matchedPercentageSortedScore);
			for (int i=0; i<matchedPercentageSortedScore.size(); i++) {
				int currentIndex = matchedPercentageSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
		}

		//Grey area is usually reached when oscillation went out of synchronization and exceeded given threshold OR some action requires
		//prolonged stay at greyArea OR unexpected actions or events trigger the imbalance.
		else if (greyArea) {
			if (nearNegative) {
				//Time score is added by 1 to signify it is not important. Higher score is bad.
				//Near negative will react slower, and time doesn't matter.
				for (int i=0; i<timeRequirementSortedScore.size(); i++) {
					int currentIndex = timeRequirementSortedScore.get(i);
					int currentScore = scores.get(currentIndex);
					scores.set(currentIndex, currentScore + i + 1);
				}
			}
			else {
				//Time score is deducted by 1 to signify it is important. Higher score is bad.
				//Time is important during peak period.
				for (int i=0; i<timeRequirementSortedScore.size(); i++) {
					int currentIndex = timeRequirementSortedScore.get(i);
					int currentScore = scores.get(currentIndex);
					scores.set(currentIndex, currentScore + i - 1);
				}
			}

			//These are the must run for both positive and negative state.
			//Doesnt care usual or unique much, but prefer unique more. Ascending order (default ordering) is unique first.
			for (int i=0; i<timeRanSortedScore.size(); i++) {
				int currentIndex = timeRanSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			for (int i=0; i<polyValSortedScore.size(); i++) {
				int currentIndex = polyValSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			//Doesnt care usual or unique much, but prefer unique more. Ascending order (default ordering) is unique first.
			for (int i=0; i<precisionsRateSortedScore.size(); i++) {
				int currentIndex = precisionsRateSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			//Incline toward center is preferred, therefore we decrease its score to promote it.
			for (int index : centralInclinatedSolutionIndex) {
				int currentScore = scores.get(index);
				scores.set(index, currentScore - 1);
			}
			//At greyArea, prefer viable route as well. Original ordering is ascending (less matched first).
			//Reverse it to get most matched first.
			Collections.reverse(matchedPercentageSortedScore);
			for (int i=0; i<matchedPercentageSortedScore.size(); i++) {
				int currentIndex = matchedPercentageSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
		}

		else if (optimal) {
			//Average time is acceptable, any time is acceptable.
			for (int i=0; i<timeRequirementSortedScore.size(); i++) {
				int currentIndex = timeRequirementSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			//Prefer unique or even generate new combination of solutions. Ascending order (default ordering) is unique first.
			for (int i=0; i<timeRanSortedScore.size(); i++) {
				int currentIndex = timeRanSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			for (int i=0; i<polyValSortedScore.size(); i++) {
				int currentIndex = polyValSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			//Prefer unique or even generate new combination of solutions. Ascending order (default ordering) is unique first.
			for (int i=0; i<precisionsRateSortedScore.size(); i++) {
				int currentIndex = precisionsRateSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
			//Incline toward center is preferred, therefore we decrease its score to promote it. Note it is only within optimal range,
			//not at optimal point, therefore central inclination is still applicable.
			for (int index : centralInclinatedSolutionIndex) {
				int currentScore = scores.get(index);
				scores.set(index, currentScore - 1);
			}
			//At optimal, prefer viable route as well. Original ordering is ascending (less matched first).
			//Reverse it to get most matched first.
			Collections.reverse(matchedPercentageSortedScore);
			for (int i=0; i<matchedPercentageSortedScore.size(); i++) {
				int currentIndex = matchedPercentageSortedScore.get(i);
				int currentScore = scores.get(currentIndex);
				scores.set(currentIndex, currentScore + i);
			}
		}

		else
			throw new IllegalStateException("Unknown state reached at ACTGDR.");

		return scores;
	}

	private void forwardToRSGTxE(ArrayList<Integer> unmetRequirementIndex, ArrayList<Vertex> unmetRequirementList,
			Vertex selectedSolution, Vertex session, Graph txGraph) {
		for (int unmetIndex : unmetRequirementIndex) {
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

				Vertex unmetRequirementReload = Util.vReload(unmetRequirementList.get(unmetIndex), txGraph);
				selectedSolution = Util.vReload(selectedSolution, txGraph);
				session = Util.vReload(session, txGraph);

				//Note: RSG is responsible to setup the solutionSize and remainingSteps
				Vertex newMainConvergenceVertex = txGraph.addVertex(DBCN.V.general.convergenceMain.cn, DBCN.V.general.convergenceMain.cn);
				newMainConvergenceVertex.addEdge(DBCN.E.data, unmetRequirementReload);
				newMainConvergenceVertex.addEdge(DBCN.E.parent, selectedSolution);
				newMainConvergenceVertex.setProperty(LP.originalOrdering, unmetIndex);
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

				txError = txGraph.finalizeTask(true);
			}
		}
	}

	private void forwardToRERAUPTxE(Vertex furtherMainConvergenceVertex, Vertex session, Graph txGraph) {
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

			furtherMainConvergenceVertex = Util.vReload(furtherMainConvergenceVertex, txGraph);
			session = Util.vReload(session, txGraph);

			Vertex newRERAUPTask = txGraph.addVertex(DBCN.V.jobCenter.crawler.RERAUP.task.cn, DBCN.V.jobCenter.crawler.RERAUP.task.cn);
			Vertex RERAUPTaskDetailVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
			newRERAUPTask.addEdge(DBCN.E.source, RERAUPTaskDetailVertex);
			RERAUPTaskDetailVertex.addEdge(DBCN.E.source, furtherMainConvergenceVertex);
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
	}

	/**
	 * Deduce return type of main core logic and decide which path to forward.
	 */
	public void deduceAndForwardToNextTaskTxE(ACTGDRMultiReturn result, Vertex taskDetailVertex, Graph txGraph) {
		//Forward inherited session data.
		Vertex session = Util.traverseOnce(taskDetailVertex, Direction.OUT, DBCN.E.session);

		if (result.exitRoute == 0)
			forwardToRSGTxE(result.unmetRequirementIndex, result.unmetRequirementList, result.selectedSolution, session, txGraph);
		else if (result.exitRoute == 1)
			forwardToRERAUPTxE(result.furtherMainConvergenceVertex, session, txGraph);
	}

	/**
	 * Main execution flow logic, will return null if nothing needs to be forwarded.
	 */
	public ACTGDRMultiReturn execute(Vertex generalVertex, double globalDist, Graph txGraph) {
		//-general vertex here is a main convergence vertex which is part of the branch of the solution, contains most of the DM credential,
		//and have edges to other secondary convergence vertexes where each of them represent a solution,
		//and each secondary convergence had been through SCCRS and have their contained requirements paired against reality
		//and result stored in term of list within the convergence vertex itself.

		if (!generalVertex.getCName().equals(DBCN.V.general.convergenceMain.cn))
			throw new IllegalArgumentException("At ACTGDR: Expecting input type: "+ DBCN.V.general.convergenceMain.cn + " but get: " + generalVertex);

		//Check whether it had switched branch or not by checking upward, if switched we will not do work as it would be waste of time.
		//Only secondaryVertex can be invalidated (marked as switched branch by assigning its remainingSolution to -1).
		//generalVerex should not be absolute beginning (STISS first convergence main) at here, else all the following will not work.
		boolean isSwitchedBranch = false;
		Vertex upperSecondaryConvergenceVertex = Util.traverseOnce(generalVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);
		int upperConvergenceRemainingSolution = upperSecondaryConvergenceVertex.getProperty(LP.remainingSolutionToBeCompleted);
		if (upperConvergenceRemainingSolution == -1)
			isSwitchedBranch = true;

		if (!isSwitchedBranch) {
			ArrayList<Vertex> secondaryVertexList = Util.traverse(generalVertex, Direction.IN, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);

			//Reorder route then forward all of them to appropriate exits.
			ArrayList<Integer> scores = reorderRouteBasedOnGlobalDist(generalVertex, globalDist, secondaryVertexList, txGraph);

			//Sort the scores using treemap and in the end output an ArrayList of solution index ordered best solution first.
			ArrayList<Integer> sortedSolutionIndex = Util.sortGetIndex(scores, true);

			//Make a record of which route DM has chosen and save it inside main.
			//0 means the first index of sortedSolutionIndex, where you can do sortedSolutionIndex.get(selectedSolutionIndex) to get the
			//actual best solution. Default to 0 as it is the best solution.
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

				generalVertex.setProperty(LP.selectedSolutionIndex, 0);
				generalVertex.setProperty(LP.sortedSolutionIndexList, Util.kryoSerialize(sortedSolutionIndex));

				txError = txGraph.finalizeTask(true);
			}

			Vertex selectedSolution = secondaryVertexList.get(sortedSolutionIndex.get(0));
			int remainingSolutionSize = selectedSolution.getProperty(LP.remainingSolutionToBeCompleted);

			/*
			 * Core functionality and coherence design note here:
			 * Sometime there is really no more requirement is needed and the final base unmet requirement is a LTM instance.
			 * LTM instance is equivalent to PO, this is how it is treated at WM, thus to enable 'do nothing', we should allow it here
			 * to avoid forwarding PO to even further RSG and return upward, so the validity of this route will be determined by WM
			 * prediction against reality pair, where it will forward any LTM PO to relevant ICL checking phrase, then it will yield
			 * relevant (similar) pattern, then it will pass instead of failing and coming back to RSGFSB.
			 * As we had already ran out of possible route if it is unmet and only 1 single LTM element left, we are fine to post
			 * it to RERAUP and face our fate, which is IDLE, but it may sometime pass at WM, then we had learn a new experience,
			 * when to stop and listen and when to react, a core lesson.
			 * This can also avoid RSG and SCCRS to keep duplicating itself thus forming an infinite loop due to lack of other solution
			 * to resolve the problem.
			 * Read 14-6-16 for in depth simulation and guide. It also contain what need to be done to achieve the full version ISRA.
			 */
			boolean unmetIsLTMSkipFurtherCheckingAndReturnUpward = false;
			if (remainingSolutionSize != 0) {
				//Setup new RSG to compute our selected solution for every of its unmet requirements.
				//Get the solution that had failed, its origin secondary vertex and the actual requirement data.
				//-selectedSolution is a secondaryConvergence, the selectedRoute.
				ArrayList<Integer> unmetRequirementIndex =
						Util.kryoDeserialize( (byte[])selectedSolution.getProperty(LP.realityPairFailedIndexList), ArrayList.class );
				//The secondaryConvergence's data can either be a LTM or a exp requirement/result vertex. One element only, where
				//the exp can contain more element, but the LTM cannot.
				Vertex unmetRequirementDataTypeYetToBeDeduced = Util.traverseOnce(selectedSolution, Direction.OUT, DBCN.E.data, LTM.DEMAND_DATA);
				String unmetRequirementType = unmetRequirementDataTypeYetToBeDeduced.getCName();

				ArrayList<Vertex> unmetRequirementList = new ArrayList<Vertex>();

				//We here intend to get actual data' general vertex OR LTM general vertex.
				//If its type is exp, we must traverse once more to extract the actual solutions.
				//For both requirement and result, its 'selectedSolution' is the exp data vertex that contain edges to the actual data.
				if (unmetRequirementType.equals(DBCN.V.general.exp.requirement.cn)) {
					Vertex expRequirementData = Util.traverseOnce(unmetRequirementDataTypeYetToBeDeduced, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.requirement.cn);
					assert expRequirementData.getCName().equals(DBCN.V.LTM.exp.requirement.cn);
					unmetRequirementList = Util.traverse(expRequirementData, Direction.OUT, DBCN.E.requirement);
				}
				else if (unmetRequirementType.equals(DBCN.V.general.exp.result.cn)) {
					Vertex expResultData = Util.traverseOnce(unmetRequirementDataTypeYetToBeDeduced, Direction.IN, DBCN.E.data, DBCN.V.LTM.exp.result.cn);
					assert expResultData.getCName().equals(DBCN.V.LTM.exp.result.cn);
					unmetRequirementList = Util.traverse(expResultData, Direction.OUT, DBCN.E.result);
				}
				//If it is LTM, we cannot expand it any further, thus we will just add it as the requirement.
				else if (Util.equalAny(unmetRequirementType, LTM.GENERAL))
					unmetRequirementList.add(unmetRequirementDataTypeYetToBeDeduced);

				//Begin check for ignore failure(unmet) and execute it anyway due to depression.
				if (unmetRequirementList.size() == 1) {
					if (Util.equalAny( unmetRequirementList.get(0).getCName(), LTM.GENERAL) ) {
						//Forward it to RERAUP, similar to no solution left condition, implementation below.
						unmetIsLTMSkipFurtherCheckingAndReturnUpward = true;
					}
				}

				//Only proceed if not going to run special operation, else it will create a dangling mainConvergence.
				if (!unmetIsLTMSkipFurtherCheckingAndReturnUpward) {
					//Else just follow the drill and make more RSG to compute further solution.
					return new ACTGDRMultiReturn(selectedSolution, unmetRequirementIndex, unmetRequirementList);
				}
			}

			//If our selected solution has no uncompleted field OR the special LTM solo left return upward, we will return upward.
			if (remainingSolutionSize == 0 || unmetIsLTMSkipFurtherCheckingAndReturnUpward) {
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

					upperSecondaryConvergenceVertex = Util.vReload(upperSecondaryConvergenceVertex, txGraph);

					//This vertex and its remainingSolution count is used during branch change check, now we reuse them.
					upperSecondaryConvergenceVertex.setProperty(LP.remainingSolutionToBeCompleted, upperConvergenceRemainingSolution - 1);

					txError2 = txGraph.finalizeTask(true);
				}

				//Its initial value we set to the updated original upperConvergenceRemainingSolution (by deducting 1), if it is 0, we will
				//further continue to return upward until we reaches head or the solution size is not 0.
				//If it happens to be -1, we will not continue as the requirement's demand contract had been terminated.
				//(branch switched but we failed to detect it, which only possible if branch switch happen during this DM is running).
				int furtherSolutionSize = upperConvergenceRemainingSolution - 1;
				Vertex furtherMainConvergenceVertex = null;
				Vertex furtherSecondaryConvergenceVertex = null;

				//Iteratively return upward if condition is met (all requirement for the solution are completed).
				//2 special condition that can break out of this loop:
				//-If we had reached the first mainConvergence vertex (absolute beginning).
				//-If our branch had been invalidated at upper layer but we only found it out now.
				while (furtherSolutionSize == 0) {
					furtherMainConvergenceVertex = Util.traverseOnce(upperSecondaryConvergenceVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceMain.cn);

					//Original ordering cannot be -1, if it is -1 it means it is the absolute tree beginning, we had setup this sentinel
					//value the really beginning of the tree creation at STISS.
					//If it is done, we will forward it to next execution step.
					//TODO: IMPORTANT IMPLEMENT: forward this to consequences check before to the executing step, now we don't, later must!
					//Similar entry at ACTGDR (here) and RSGFSB, do this for both of them.
					int originalOrdering = furtherMainConvergenceVertex.getProperty(LP.originalOrdering);
					//If we had reached the absolute beginning, the first mainConvergence vertex.
					//We will call the final task (RERAUP) to forward our tree to the WM for execution.
					if (originalOrdering == -1) {
						return new ACTGDRMultiReturn(furtherMainConvergenceVertex);
					}

					furtherSecondaryConvergenceVertex = Util.traverseOnce(furtherMainConvergenceVertex, Direction.OUT, DBCN.E.parent, DBCN.V.general.convergenceSecondary.cn);
					furtherSolutionSize = furtherSecondaryConvergenceVertex.getProperty(LP.remainingSolutionToBeCompleted);

					//It must not be -1, else we will do nothing as it had been invalidated, other branch should do the work now.
					if (furtherSolutionSize != -1) {
						//Update the remaining solution size, every time you run by each node, you should deduct by 1 step for them.
						//This is to tell them you had just completed one of their requirement.
						furtherSolutionSize -= 1;

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

							furtherSecondaryConvergenceVertex = Util.vReload(furtherSecondaryConvergenceVertex, txGraph);

							//Refetch the solution size, it may had been modified while we are running this, update the count and
							//recheck the condition for safety purposes.
							furtherSolutionSize = furtherSecondaryConvergenceVertex.getProperty(LP.remainingSolutionToBeCompleted);
							furtherSecondaryConvergenceVertex.setProperty(LP.remainingSolutionToBeCompleted, furtherSolutionSize);

							txError3 = txGraph.finalizeTask(true);
						}
					}

					//Our currently running branch (this branch) had already been invalidated (switch branched). Just exit and do nothing.
					else
						break;
				}
			}
		}
		return null;
	}
}
