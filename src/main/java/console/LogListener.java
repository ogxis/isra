package console;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import isradatabase.Graph;
import isradatabase.GraphFactory;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;

/**
 * Fetch async real time log result from DB and print them out to the invoker console.
 * Used by ISRA CLI console.
 */
public class LogListener implements Runnable {
	private Graph txGraph;
	public String dbDataPath;
	private AtomicBoolean halt;
	private GraphFactory factory;
	private final boolean embedded;
	private ConcurrentLinkedQueue<String> output = new ConcurrentLinkedQueue<String>();

	/**
	 * Return empty string if no new log is available.
	 * @return
	 */
	public String getNextLog() {
		String result = output.poll();
		return result == null ? "" : result;
	}

	public LogListener(String dbDataPath, GraphFactory factory, boolean embedded) {
		this.txGraph = null;
		this.dbDataPath = dbDataPath;
		this.halt = new AtomicBoolean(false);
		this.factory = factory;
		this.embedded = embedded;

		if (embedded)
			output = new ConcurrentLinkedQueue<String>();
	}

	public void halt() {
		halt.set(true);
	}

	public void run() {
		while (!halt.get()) {
			//If the console had gone to hell, this will die too as the transaction and communication to DB will fail and raise error.
			txGraph = factory.getTx();
			ArrayList<Vertex> logs = txGraph.getVerticesOfClass(dbDataPath);

			txGraph.begin();
			for (Vertex v : logs) {
				//Get and output the message.
				String finalOutput = v.getProperty(LP.data);
				if (embedded)
					output.offer(finalOutput);
				else
					System.out.println(finalOutput);

				v.remove();
			}
			txGraph.commit();
		}
		System.out.println("LogListener halted successfully.");
	}
}
