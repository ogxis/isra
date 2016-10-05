package stm;

import java.util.concurrent.ConcurrentLinkedQueue;

import startup.StartupSoft;
import utilities.Util;

/**
 * Execute update task in serial order with optional frame skipping to ensure the whole GCA system keeps up better with real time.
 * GCA-WM STM update tasks that are database reliant.
 * This updates WM STM, the short term memory which provide data for PaRc, the temporal latest reality.
 */
public class GCAWMExecutor implements Runnable {
	private ConcurrentLinkedQueue<Runnable> taskQueue;
	private int haltIndex;

	public GCAWMExecutor(int haltIndex) {
		taskQueue = new ConcurrentLinkedQueue<Runnable>();
		this.haltIndex = haltIndex;
	}

	public void addTask(Runnable task) {
		taskQueue.add(task);
	}

	/**
	 * Synchronized with STMServer halt so in case of hard shutdown this thread will halt with him.
	 * @return
	 */
	private boolean isHalt() {
		//currently halt index is maintained by startupsoft, it may change in the future.
		return StartupSoft.halt.get(haltIndex).get();
	}

	@Override
	public void run() {
		System.out.println("GCAWMExecutor for GCAMain started.");

		int frameSkipIfReached = 50;
		int maxFrameToSkipPerTask = 200;

		while (!isHalt()) {
			//Skip frames to keep up with real time. May cause some PaRc to fail.
			if (taskQueue.size() > frameSkipIfReached) {
				int neededToBeSkippedFrame = taskQueue.size() - frameSkipIfReached;
				int canBeSkippedFrame = neededToBeSkippedFrame < maxFrameToSkipPerTask ? neededToBeSkippedFrame : maxFrameToSkipPerTask;
				for (int i=0; i<canBeSkippedFrame; i++)
					taskQueue.poll();
				System.out.println("GCAWMExecutor for GCAMain frame skipped: " + canBeSkippedFrame);
			}

			//Get the next task and run it.
			Runnable task = taskQueue.poll();
			if (task != null) {
				Thread thread = new Thread(task);
				thread.start();

				while (thread.isAlive() && !isHalt())
					Util.sleep(1);

				if (!isHalt())
					System.out.println("GCAWMExecutor for GCAMain task completed.");
				else
					System.out.println("GCAWMExecutor for GCAMain task ignored as instructed to halt.");
			}
		}
		System.out.println("GCAWMExecutor for GCAMain halted.");
	}

}
