package analytic;

import java.util.ArrayList;

import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;

/**
 * Analytic functions that analyze performance, health, particular interest and states of the system, 'hot' or 'cold'.
 * All of them must have access to database inherited from the caller.
 * Note: Currently not deployed.
 */
public class Analytic {
	/*
	 * HOT, usable while the system is running, has built in concurrent protection that make least interference against running system.
	 * COLD, usable only when system is down, no concurrent guarantee, should run one by one.
	 * NEUTRAL, usable on both HOT and COLD, concurrent guaranteed.
	 */
	public static class HOT {

	}
	public static class COLD {
		/**
		 * Calculate precision rate for any startup session by getting metadata from the given startup register vertex,
		 * fetch all the trees within that time frame then calculate whether they passed PaRc or not,
		 * return the % of passed tree.
		 */
		public static double calculatePrecisionRateForStartupSession(Graph txGraph, Vertex startupVertex) {
			//Check whether the rate had been calculated before already, if so, return that rate.
			if ((long)startupVertex.getProperty(LP.startupPrecisionRate) >= 0l)
				return (long)startupVertex.getProperty(LP.startupPrecisionRate);

			long startTime = startupVertex.getProperty(LP.startupStartTime);
			long endTime = startupVertex.getProperty(LP.startupEndTime);

			//Get all the tree within boundary (created during the given startup session).
			ArrayList<Vertex> decisionTrees = txGraph.directQueryExpectVertex("select from " + DBCN.V.general.convergenceHead.cn +
					" where " + LP.timeStamp.toString() + " between " + startTime + " and " + endTime);
			int passedSize = 0;

			for (Vertex v : decisionTrees) {
				//Check whether he is a pass or fail.
				boolean PaRcPassed = v.getProperty(LP.PaRcPassed);
				if (PaRcPassed)
					passedSize++;
			}

			if (decisionTrees.isEmpty())
				return 0d;
			else
				return (double)passedSize / (double)decisionTrees.size();
		}
	}
	public static class NEUTRAL {

	}
}
