package logger;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Throwables;

import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import startup.StartupSoft;
import stm.DBCN;
import utilities.Util;

/**
 * Custom logger class that forward logs to DB and to STDOUT.
 * NOTE: Must call the run() method via a thread.
		Thread thread = new Thread(this);
		thread.start();
	in order to start the service loop.
 */
public class Logger implements Runnable {
	public int commitCount;
	public long waitMilliBeforeCommit;
	public Graph txGraph;
	public String consoleFeedbackAddr;
	public AtomicBoolean serverReady;
	public AtomicBoolean halt;
	public AtomicBoolean haltFlushComplete;
	public AtomicBoolean verbose;
	public ConcurrentLinkedQueue <LogData> logPool;
	public long lastTime;

	public static class Credential {
		public final String UID;
		public String threadName;
		public String identifier;
		public String parentIdentifier;
		public String taskList;

		public Credential() {
			UID = "null";
			threadName = "null";
			identifier = "null";
			parentIdentifier = "null";
			taskList = "null";
		}

		public Credential(String threadName, String identifier, String parentIdentifier, String taskList) {
			if (threadName == null)
				threadName = "null";
			if (identifier == null)
				identifier = "null";
			if (parentIdentifier == null)
				parentIdentifier = "null";
			if (taskList == null)
				taskList = "null";
			this.UID = System.currentTimeMillis() + threadName + parentIdentifier;
			this.threadName = threadName;
			this.identifier = identifier;
			this.parentIdentifier = parentIdentifier;
			this.taskList = taskList;
		}
	}

	/**
	 * Level.
	 */
	public static final class LVL {
		public static final int UNDEFINED = -1;
		public static final int TRACE = 0;
		public static final int DEBUG = 1;
		public static final int INFO = 2;
		public static final int WARN = 3;
		public static final int ERROR = 4;
		public static final int FATAL = 5;
	}

	/**
	 * Clarification.
	 */
	public static final class CLA {
		public static final int UNDEFINED = -1;
		public static final int EXCEPTION = 0;
		public static final int NORM = 1;
		public static final int INTERNAL = 2;
		public static final int REPLY = 3;
	}

	/**
	 * Used by DBHierarchy setup to setup the property of the log data structure. This is for general log.
	 */
	public enum LPLOG {
		credentialUid("credentialUid"),
		LVL("LVL"),
		CLA("CLA"),
		message("message"),
		exception("exception"),
		timeStamp("timeStamp");

		//Function to convert enum to string representations.
		private final String text;

		private LPLOG(final String text) {
	        this.text = text;
	    }

	    @Override
	    public String toString() {
	        return text;
	    }
	}

	/**
	 * Used by DBHierarchy setup to setup the property of the log data structure. This is for log credential.
	 */
	public static final class LPCREDENTIAL {
		public static final String UID = "UID";
		public static final String threadId = "threadId";
		public static final String threadName = "threadName";
		public static final String parentName = "parentName";
		public static final String taskList = "taskList";
		public static final String timeStamp = "timeStamp";
	}

	/**
	 * Store log message and states.
	 */
	private static class LogData {
		public Credential credential;
		public int lvl;
		public int cla;
		public String message;
		public Throwable t;

		public LogData(Credential credential, int lvl, int cla, String message, Throwable t) {
			if (credential == null)
				this.credential = new Credential(null, null, null, null);
			if (message == null)
				message = "null";
			//Throwable doesn't have to check as if it is null, means we doens't want to use it.
			this.credential = credential;
			this.lvl = lvl;
			this.cla = cla;
			this.message = message;
			this.t = t;
		}
	}

	/**
	 * Only invoke this if StartUpSoft has done connecting to DB to setup all required states.
	 * @param commitCount
	 */
	public Logger(int commitCount, long waitMilliBeforeCommit, String consoleFeedbackAddr) {
		this.commitCount = commitCount;
		this.waitMilliBeforeCommit = waitMilliBeforeCommit;
		this.consoleFeedbackAddr = consoleFeedbackAddr;
		this.lastTime = -1l;
		this.txGraph = null;
		this.logPool = new ConcurrentLinkedQueue <LogData>();
		this.serverReady = new AtomicBoolean(false);
		this.halt = new AtomicBoolean(false);
		this.haltFlushComplete = new AtomicBoolean(false);
		this.verbose = new AtomicBoolean(true);
	}

	/**
	 * Add log message to the logger.
	 * @param credential Every thread should have their own credentail created during startup.
	 * @param lvl
	 * @param cla Clarification of the message type, summary.
	 * @param message
	 */
	public void log(Credential credential, int lvl, int cla, String message) {
		this.logPool.offer(new LogData(credential, lvl , cla, message, null) );
	}
	public void log(Credential credential, int lvl, int cla, String message, Throwable t) {
		this.logPool.offer(new LogData(credential, lvl , cla, message, t) );
	}

	@Override
	public void run() {
		//Spawn a new thread and periodically commit data to database.
		ArrayList<LogData> polledLog = new ArrayList<LogData>();
		while (!halt.get()) {
			long currentTime = System.currentTimeMillis();
			if (lastTime == -1l)
				lastTime = currentTime;

			//Fetch data until the queue is empty.
			while (true) {
				LogData ld = logPool.poll();
				if (ld != null) {
					polledLog.add(ld);
					if (verbose.get()) {
						//For user we output the identifier as it is more readable.
						if (ld.t == null)
							System.out.println(Util.epochMilliToReadable( System.currentTimeMillis() ) + " " + ld.credential.identifier + " "
									+ ld.lvl + " "  + ld.cla + " " + ld.message);
						else
							System.out.println(Util.epochMilliToReadable( System.currentTimeMillis() ) + " " + ld.credential.identifier + " "
									+ ld.lvl + " "  + ld.cla + " " + ld.message + Throwables.getStackTraceAsString(ld.t));
					}
				}
				else
					break;
			}

			//If it is a normal halt, flush all data and exit.
			if (halt.get() && serverReady.get()) {
				txGraph = StartupSoft.factory.getTx();
				txGraph.begin();
				for (LogData ld : polledLog) {
					//For record purposes store the uuid as it leads to the credential, to avoid duplication across logs.
					Vertex logVertex = txGraph.addVertex(DBCN.V.log.cn, DBCN.V.log.cn);
					logVertex.setProperty(LPLOG.credentialUid, ld.credential.UID);
					logVertex.setProperty(LPLOG.LVL, ld.lvl);
					logVertex.setProperty(LPLOG.CLA, ld.cla);
					logVertex.setProperty(LPLOG.message, ld.message);
					logVertex.setProperty(LPLOG.timeStamp, currentTime);

					Vertex feedBackVertex = txGraph.addVertex(consoleFeedbackAddr, consoleFeedbackAddr);

					if (ld.t != null) {
						logVertex.setProperty(LPLOG.exception, Throwables.getStackTraceAsString(ld.t));
					}

					//Convert it to readable format so user can just view it without having to decode and rearrange it.
					if (ld.t == null)
						feedBackVertex.setProperty(LP.data, Util.epochMilliToReadable( System.currentTimeMillis() ) + " " + ld.credential.identifier + " "
								+ ld.lvl + " "  + ld.cla + " " + ld.message);
					else
						feedBackVertex.setProperty(LP.data, Util.epochMilliToReadable( System.currentTimeMillis() ) + " " + ld.credential.identifier + " "
								+ ld.lvl + " "  + ld.cla + " " + ld.message + Throwables.getStackTraceAsString(ld.t));
				}
				txGraph.commit();
				txGraph.shutdown();

				break;
			}
			//If it is halt and the server is not ready yet, means the server crashed, log them to a temporary file as currently cannot
			//log to server now, it is offline.
			else if (halt.get() && !serverReady.get()) {
				//Save the arraylist to file.
				break;
			}
			//If time had reached OR had reached the required number for committing, commit all those data to db.
			else if ( (currentTime - lastTime > waitMilliBeforeCommit
					|| polledLog.size() > commitCount ) && serverReady.get()) {
				lastTime = currentTime;

				txGraph = StartupSoft.factory.getTx();
				txGraph.begin();
				for (LogData ld : polledLog) {
					Vertex logVertex = txGraph.addVertex(DBCN.V.log.cn, DBCN.V.log.cn);
					logVertex.setProperty(LPLOG.credentialUid, ld.credential.UID);
					logVertex.setProperty(LPLOG.LVL, ld.lvl);
					logVertex.setProperty(LPLOG.CLA, ld.cla);
					logVertex.setProperty(LPLOG.message, ld.message);
					logVertex.setProperty(LPLOG.timeStamp, currentTime);

					Vertex feedBackVertex = txGraph.addVertex(consoleFeedbackAddr, consoleFeedbackAddr);

					if (ld.t != null) {
						logVertex.setProperty(LPLOG.exception, Throwables.getStackTraceAsString(ld.t));
					}

					//Convert it to readable format so user can just view it without having to decode and rearrange it.
					if (ld.t == null)
						feedBackVertex.setProperty(LP.data, Util.epochMilliToReadable( System.currentTimeMillis() ) + " " + ld.credential.identifier + " "
								+ ld.lvl + " "  + ld.cla + " " + ld.message);
					else
						feedBackVertex.setProperty(LP.data, Util.epochMilliToReadable( System.currentTimeMillis() ) + " " + ld.credential.identifier + " "
								+ ld.lvl + " "  + ld.cla + " " + ld.message + Throwables.getStackTraceAsString(ld.t));
				}
				txGraph.commit();
				txGraph.shutdown();

				polledLog = new ArrayList<LogData>();
			}
		}
		haltFlushComplete.set(true);
	}
}
