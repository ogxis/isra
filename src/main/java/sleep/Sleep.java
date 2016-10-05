package sleep;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.common.util.concurrent.AtomicDouble;

import crawler.RSG;
import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import utilities.Util;

/**
 * Activity run during system shutdown intervals.
 * To boost learning potential and simulate real sleeping behavior benefits.
 * This can be invoked manually via console integration OR staged to run automatically after WM shutdown (session end).
 * It will run on its own until completion, all the output will be posted to both STDOUT and a output queue where user can poll from it.
 * Cannot be interrupted once started, else risk extreme data corruption.
 *
 * Currently not deployed.
 */
public class Sleep {
	private static ConcurrentLinkedQueue<String> outputs = new ConcurrentLinkedQueue<String>();
	public static String pollOutput() {
		return outputs.poll();
	}
	private static AtomicDouble progress = new AtomicDouble(-1d);
	public static double getProgress() {
		return progress.get();
	}

	/**
	 * Given a tree, expand the whole tree recursively and record down all the possible route into exp with proper edges.
	 * Expand layer by layer, if last layer complete (full layer), then advance to next layer.
	 * Basically the layer can never be finished, can hardly be depleted (infinite combination in theory), but in reality it may
	 * deplete only at the beginning, when we are lack of complex unique solution.
	 * TODO: create a compression algorithm, that compress those redundant array.
	 * @param txGraph
	 */
	private static void layeredBranchExpansion(Graph txGraph) {
		//Get the latest session.
		Vertex latestSession = txGraph.getLatestVertexOfClass(DBCN.V.startup.current.cn);

		long startTime = latestSession.getProperty(LP.startupStartTime);
		long endTime = latestSession.getProperty(LP.startupEndTime);

		//Get all the tree within boundary (created during the given startup session).
		ArrayList<Vertex> decisionTrees = txGraph.directQueryExpectVertex("select from " + DBCN.V.general.convergenceHead.cn +
				" where " + LP.timeStamp.toString() + " between " + startTime + " and " + endTime);

		if (decisionTrees.isEmpty())
			throw new IllegalStateException("The supplied startup session vertex has no decision tree within range. " + latestSession);

		/*
		Setup WM reality first by importing GCA into WM index. Then keep update it as time goes.
		Or simply generate neutral operations? Finding hidden meaning will be more meaningful. (dream)
		Compose alternate reality series to test out newly generated patterns to integrate them fully.
		Better equipped, reverse come back to bind better solution that was realized afterward to the point of entry.
		Second go is lengthen globalDIst allowance window to seek larger side, previously not discovered solutions and ROIs,
		then rerun them with newly found data, then do a prediction roll and finalize them into exp.
		NOTE: all of them must be valid and with high precision rate so we don't stumble and fall in the future when using them.
		Also ICL all of them as far as possible to create occurrence edge all over the place where previously unnoticed. (indexing)
		All is done, but body still recovering from fatigue, thus run some fun game. (fatigue, dream far)
		All of them should be contagious memory generated, whether they failed or pass, this is to ensure so that the memory
		we generated but didn't run this phrase, they will not be treated as perfect as the default is prediction == result during
		Init. This will also diversify our tree greatly.
		Find a way to store those green and ban list during this tree generation, so they can know what had been tried, the tree is the
		most accurate representation after all.

		Hint: the green ban list should be stored at the failed, the passed or both of them after tree generation and PaRc?
		Store it only at the parent, the child has nothing to do with it, but if possible we want to make green ban list another vertex
		so it can make direct edges to each vertexes, and only have 1 parent.
		Thus we can check any exp whether they are preferred or not by the public domain.
		If the whole tree fails, the list belongs to the initiator. If the tree succeed, it belongs to the contagious exp.
		Being run on its own vs being integrated with other's precision rate is both 2 different semantic.
		run on its own should be its own precision rate, integrating with other shoulld be other's (their) precision rate.

		Conclucsion: no external green/ban edge needed, they will just clutter things up. And for each interested operation, you can
		always traerse to it and see its precision rate in regard to current, as when things get merged, it becomes quite unpredictable.

		Sleep will have no meaning as it cannot come up with good outcome, all sleep generated vertexes cannot be guaranteed,
		requires extra real life simulation to get it work. We can do that by making its precision rate as unknown (-1),
		so it MAY get elected during runtime, and thus do the simulation.
		This is mainly to diversify solutions.

		Should store the ban green list directly in exp or in session?
	*/

		//For each tree created during the given session, expand them all.
		//TODO: Parallelize it as each tree can be computed independently. But cummulative effort may be better (sequential) as it can make
		//use of those data on the fly.
		//After this iteration done, it is optional to continue, if continue, it will reparse the tree again but at the next layer.
		//Depletion is rare.
		for (int i=0; i<decisionTrees.size(); i++) {
			Vertex convergenceHead = decisionTrees.get(i);
			Vertex actualConvergenceMain = Util.traverseOnce(convergenceHead, Direction.OUT, DBCN.E.convergenceHead);

			//Recursively expand and generate solution for each branch using crawler code to expand next layer solution recursively.
			//Forward or create a new mainConvergence for each branch part to work.
			//By default we fetch the given tree, +1 to skip to next branch, then use that mainConvergence, then go next and begin.
			int nextOrder = (int)actualConvergenceMain.getProperty(LP.originalOrdering) + 1;
			int totalSolutionSize = actualConvergenceMain.getProperty(LP.totalSolutionSize);

			if (nextOrder + 1 == totalSolutionSize) {
				RSG rsg = new RSG(10, 10);
				rsg.execute(actualConvergenceMain, txGraph);
			}
		}
	}

	/**
	 * Merge similar experiences together to save space.
	 */
	private static void mergeSimilarExp() {

	}

	/**
	 * Call this to start the automated sleeping procedure.
	 * Receive the same input format of startupSoft, uses all the same credentials to login to servers.
	 * @param args
	 * @return
	 */
	public static int startService(String[] args) {
		return 0;
	}
}
