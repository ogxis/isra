package crawler;

import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import utilities.Util;

/*
 * Crawler Function Convention is:
 * Without special modifier: stateless calculation. Modifies only given parameter and do not make any transaction to database.
 * TxL: Transaction in code, modify only the parameter fed in. Basically stateless. (LOCAL)
 * TxE: Transaction in code that modify local and expand to external interface. (EXTERNAL)
 * TxF: Transaction in code and lead to next phrase of work, that post states. (FORWARDING)
 */
/**
 * Recursive Execute Route And Update Prediction (RERAUP).
 * Forward solution tree to the WM to execute the tree's and monitor its execution status.
 *
 * Doesn't extract the forwarding portion out as this operation is not expected to be reused like other crawler operations.
 */
public class RERAUP {
	private long dbErrMaxRetryCount;
	private long dbErrRetrySleepTime;

	public RERAUP(long dbErrMaxRetryCount, long dbErrRetrySleepTime) {
		this.dbErrMaxRetryCount = dbErrMaxRetryCount;
		this.dbErrRetrySleepTime = dbErrRetrySleepTime;
	}

	public void execute(Vertex generalVertex, Vertex taskDetailVertex, Graph txGraph) {
		//-generalVertex is a mainConvergence vertex, the absolute beginning of the solution tree.
		if (!generalVertex.getCName().equals(DBCN.V.general.convergenceMain.cn))
			throw new IllegalArgumentException("At RERAUP: Expecting input type: "+ DBCN.V.general.convergenceMain.cn + " but get: " + generalVertex);

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

			Vertex session = Util.traverseOnce(taskDetailVertex, Direction.OUT, DBCN.E.session);

			//Add the beginning of the solution tree to WM.
			//There may be multiple WM instances, thus they will manage when the addition is done on their own using count.
			Vertex newPredictionVertex = txGraph.addVertex(DBCN.V.WM.timeline.addPrediction.cn, DBCN.V.WM.timeline.addPrediction.cn);
			newPredictionVertex.addEdge(DBCN.E.source, generalVertex);
			newPredictionVertex.setProperty(LP.sessionRid, session.getRid());

			txError = txGraph.finalizeTask(true);
		}
	}
}
