package isradatabase;

import java.util.ArrayList;
import java.util.Iterator;

import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.tinkerpop.blueprints.impls.orient.OrientDynaElementIterable;
import com.tinkerpop.blueprints.impls.orient.OrientEdgeType;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;
import com.tinkerpop.blueprints.impls.orient.OrientVertexType;

import logger.Logger;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;

/**
 * Graph instance, generated and provided by GraphFactory, the interface to database.
 */
public class Graph {
	private OrientGraph txGraph;
	protected Graph(OrientGraph givenTxGraph) {
		txGraph = givenTxGraph;
	}
	//These logger values can be set explicitly, after setting, we can use the shorthand version of finalizeTask. Set using loggerSet().
	private boolean loggerSet = false;
	private Logger pLogger = null;
	private Credential pLogCredential = null;

	/**
	 * Optionally setup logging system, if set, can use the shorthand version of finalizeTask (the one without having to specify logging param).
	 * @param logger
	 * @param logCredential
	 */
	public void loggerSet(Logger logger, Credential logCredential) {
		loggerSet = true;
		pLogger = logger;
		pLogCredential = logCredential;
	}

	public ArrayList<Vertex> getVertices(String arg0, String[] arg1, Object[] arg2) {
		Iterable<com.tinkerpop.blueprints.Vertex> vertexes = txGraph.getVertices(arg0, arg1, arg2);
		return DBUtil.backendIterableVtoIsraArraylistV(vertexes);
	}
	public ArrayList<Vertex> getVerticesOfClass(String iClassName) {
		Iterable<com.tinkerpop.blueprints.Vertex> vertexes = txGraph.getVerticesOfClass(iClassName, false);
		return DBUtil.backendIterableVtoIsraArraylistV(vertexes);
	}
	public Iterator<Vertex> getVerticesItr(String arg0, String[] arg1, Object[] arg2) {
		return getVertices(arg0, arg1, arg2).iterator();
	}
	public Iterator<Vertex> getVerticesOfClassItr(String iClassName) {
		return getVerticesOfClass(iClassName).iterator();
	}
	/**
	 * Get the latest vertex of class. Null if not available.
	 * @param className
	 * @return
	 */
	public Vertex getLatestVertexOfClass(String className) {
		/*
		 * http://orientdb.com/docs/2.0/orientdb.wiki/SQL-Query.html
		 * Order by record creation. Starting from 1.7.7, using the expression "order by @rid desc",
		 * allows OrientDB to open an Inverse cursor against clusters.
		 * This is extremely fast and doesn't require classic ordering resources (RAM and CPU):
		 * select from Profile order by @rid desc
		 */
		ArrayList<Vertex> vertexes = directQueryExpectVertex("select from " + className + " order by @rid desc limit 1");
		if (!vertexes.isEmpty())
			return vertexes.get(0);
		else
			return null;
	}
	public Vertex getFirstVertexOfClass(String className) {
		ArrayList<Vertex> vertexes = directQueryExpectVertex("select from " + className + " order by @rid limit 1");
		if (!vertexes.isEmpty())
			return vertexes.get(0);
		else
			return null;
	}
	public Vertex getSecondVertexOfClass(String className) {
		ArrayList<Vertex> vertexes = directQueryExpectVertex("select from " + className + " order by @rid limit 2");
		return vertexes.get(1);
	}
	public void begin() {
		txGraph.begin();
	}
	public void commit() {
		txGraph.commit();
	}
	public void rollback() {
		txGraph.rollback();
	}
	public void shutdown() {
		txGraph.shutdown();
	}
	public void setAutoStartTx(boolean autoStartTx) {
		txGraph.setAutoStartTx(autoStartTx);
	}
	public Vertex addVertex(String iClassName, String iClusterName) {
		return DBUtil.backendVtoIsraV(txGraph.addVertex(iClassName, iClusterName));
	}

	/**
	 * Make a direct SQL query to the database and expect result in terms of vertex.
	 * Has to be separated as we need to convert backend type to wrapper type.
	 * @param queryCommand Full SQL command.
	 * @return A list of vertexes.
	 */
	public ArrayList<Vertex> directQueryExpectVertex(String queryCommand) {
		OSQLSynchQuery<OrientVertex> query = new OSQLSynchQuery<OrientVertex>(queryCommand);
		OrientDynaElementIterable odeIterable = txGraph.command(query).execute();
		Iterator<Object> odeIterator = odeIterable.iterator();
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		while (odeIterator.hasNext())
			result.add( DBUtil.backendVtoIsraV((OrientVertex)odeIterator.next()) );
		return result;
	}
	/**
	 * Make a direct SQL query to the database and expect nothing in return.
	 * @param queryCommand Full SQL command.
	 */
	public void directQueryExpectVoid(String queryCommand) {
		//https://groups.google.com/forum/#!topic/orient-database/-VBvFU2TfIw
		//Use direct command instead of query else it will raise 'Cannot execute non idempotent command' error.
		txGraph.command(new OCommandSQL(queryCommand)).execute();
	}

	public long countVertexOfClass(String className) {
		return txGraph.countVertices(className);
	}

	public void dropVertexType(String typeName) {
		txGraph.dropVertexType(typeName);
	}

	/**
	 * Finalize the current task by committing changes, if error occur rollback and ignore it.
	 * This is for use with expected failure.
	 * @param messageSuppress Suppress the error message and stack trace.
	 * @return True is has error, False no error. True means commit() failure.
	 */
	public boolean finalizeTask(boolean errorMessageSuppress, Logger logger, Credential logCredential) {
		try {
			txGraph.commit();
			return false;
		}
		catch (OConcurrentModificationException e) {
			if (!errorMessageSuppress) {
				//Only log if user supply us with the logger, this is to allow better modularization.
				if (logger != null && logCredential != null) {
					logger.log(logCredential, LVL.WARN, CLA.EXCEPTION,
							"OConcurrentModificationException during finalizeTask with supression. (ignored)", e);
				}
			}
			txGraph.rollback();
			return true;
		}
	}
	/**
	 * Finalize the current task by committing changes, this will throw exception if data are being concurrently modified.
	 * @param txGraph
	 */
	public void finalizeTask(Logger logger, Credential logCredential) {
		try {
			txGraph.commit();
		}
		catch (OConcurrentModificationException e) {
			throw new IllegalStateException("OConcurrentModificationException during finalizeTask, "
					+ "thread will be killed. Original Stack Trace:", e);
		}
	}
	/**
	 * Shorthand version of finalizeTask, with expected concurrent error (will not throw).
	 */
	public boolean finalizeTask(boolean errorMessageSuppress) {
		if (!loggerSet)
			throw new UnsupportedOperationException("Please call loggerSet() and setup the logging framework before using this function.");
		return finalizeTask(errorMessageSuppress, pLogger, pLogCredential);
	}
	/**
	 * Shorthand version of finalizeTask, will/may throw concurrent modification error.
	 */
	public void finalizeTask() {
		if (!loggerSet)
			throw new UnsupportedOperationException("Please call loggerSet() and setup the logging framework before using this function.");
		finalizeTask(pLogger, pLogCredential);
	}

	public VertexType createVertexType(String className, String superClassName) {
		OrientVertexType type = txGraph.createVertexType(className, superClassName);
		return new VertexType(type);
	}
	public EdgeType createEdgeType(String className, String superClassName) {
		OrientEdgeType type = txGraph.createEdgeType(className, superClassName);
		return new EdgeType(type);
	}
}