package stm;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.IOUtils;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;

import ICL.ICL;
import crawler.CRAWLERTASK;
import crawler.CRAWLER_TASK_ASSIGNMENT;
import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;
import pointerchange.POInterchange;
import startup.StartupSoft;
import utilities.Util;
import ymlDefine.YmlDefine.ExternalIOConfig;
import ymlDefine.YmlDefine.TaskDetail;
import ymlDefine.YmlDefine.WorkerConfig;

/**
 * STMServer, do all management work.
 */
public class STMServer implements Runnable {
	private WorkerConfig config;

	private Credential logCredential;

	//Setup executor for GCAMain tasks to ensure GCAFrames consistency in number as it is the main timer of the whole protocol.
	private GCAWMExecutor gcaWMExecutor = null;

	//GCA might lock too many resources during its binding to other data, thus configure this variable to allow premature commit, so all the other
	//process can process smoother without having to wait for the GCA to unlock the resources he is using. -1 to disable.
	//NOTE: If disabled, GCA is known to be NOT functional if the system memory is low, last occurrence is at 6000 vertexes, 2gb ram laptop.
	private static final int GCAPrematureCommitCount = -1;
	private static final int generalPrematureCommitCount = -1;
	public static final long GCAPerFrameMilli = 10;
	private static final long GCAPrematureFinalizeMilli = GCAPerFrameMilli / 5;		//May commit 20% earlier.

	//Enable warning message for GCA.
	private static final boolean GCAFrameSkipWarn = false;
	private static final boolean GCATimeExceedWarn = false;

	//Used by globalDistUpdate for accumulating polyVals and execute them only at particular timing.
	//Synchronized wait, wait for particular number of frames for the polyVals to accumulate, then process it at once.
	//5 frame per bin means 5x GCA frame time per bin.
	//Then 5 bin per polyVal calculation means integrated polyVal calculation, 5x5 means total 25 GCA frames.
	//Frame here we calculate using its absolute timing instead of relative GCA real time frame count.
	private static final int framePerPolyValBin = 5;
	private static final int maxPolyValBinCount = 5;
	private long polyValLastFrame = -1;
	private Queue<Double> polyValBin = new LinkedList<Double>();

	//Url to connect to external physical devices.
	private String visualUrl = "";
	private String audioUrl = "";
	private String rpiUrl = "";

	private boolean rawDataFetchStarted = false;

	//Used by fetch raw audio data. Start the stream and never closes it until the end in order to keep inbound data in sync.
	private InputStream audioInputStream = null;
	private DataInputStream audioDataInputStream = null;

	/**
	 * Call this to initialize crawler manager, then call run() will start the service loop (receive commands, auto push tasks to worker nodes, maintenance)
	 * @param workerConfig Configuration object for this worker.
	 * @param txGraph The accessor that we will use to communicate with the main DB.
	 */
	public STMServer(WorkerConfig workerConfig) {
		this.config = workerConfig;
	}

	/**
	 * Check whether given role is part of our preference, which we had subscribed to it during registration.
	 * @param role The work that STM Server has posted.
	 * @return True if this is your role, false otherwise.
	 */
	private boolean checkRole(String role) {
		for (int i=0; i<config.preference.size(); i++) {
			if (config.preference.get(i).equals(role))
				return true;
		}
		return false;
	}

	/**
	 * Check whether external management system has set us to halt.
	 * @return Halt or not status.
	 */
	private boolean isHalt() {
		//Currently halt index is maintained by startupsoft, it may change in the future.
		return StartupSoft.halt.get(config.haltIndex).get();
	}

	public void startService() {
		/*
		 * Note: All STMServer task doesn't have the processing and completed clause as all of its task are completed instantly,
		 * unlike crawler where its task can spread across multiple processes and map reduce back into another form where tracing is necessary.
		 *
		 * One STMServer can have multiple roles.
		 */

		String identifier = config.uid + "-" + config.storageId;
		logCredential = new Credential(Thread.currentThread().getName(), identifier, config.parentUid, config.preference.toString());
		StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "STMServer online. Thread name: " + Thread.currentThread().getName()
				+ "Task are:" + config.preference);

		//Used to store the index of the last frame that the GCA had computed.
		long curIndexRawDataGCA = -1;
		long curIndexRawDataICLGCA = -1;
		long curIndexPOFeedbackGCA = -1;
		long curIndexexpGCA = -1;
		long curIndexGCAMainGCA = -1;

		Graph txGraph = null;
		//For consistency, each operation are obligated to finish even if the isHalt() flag is on, but will stop forwarding the task
		//to the next phrase. If amid task passing encounter isHalt(), finish the passing anyway, next phrase will stop the calculation.
		//--NOTE: Each task here manage the begin() and finalize() of data on their own.
		boolean firstTimeGraphShutdownExceptionDone = false;
		while (!isHalt()) {
			try {
				txGraph.shutdown();
			}
			catch (OConcurrentModificationException cone) {
				throw new IllegalStateException("txGraph shutdown error.", cone);
			}
			catch (Exception e) {
				if (firstTimeGraphShutdownExceptionDone)
					throw new IllegalStateException("Txgraph shutdown error cannot be thrown more than once (after the first initial expected fail)"
							+ ", you failed to close the graph appropriately this time.");
				firstTimeGraphShutdownExceptionDone = true;
			}
			txGraph = StartupSoft.factory.getTx();
			//Set the logger in order to use shorthand version of finalizeTask().
			txGraph.loggerSet(StartupSoft.logger, logCredential);

			if (checkRole(STMTASK.globalDistUpdate)) {
				//Calculate current frame via absolute timing, copied from GCA timing logic below.
				boolean executeNow = false;
				long curTime = System.currentTimeMillis();
				long curFrame = curTime / StartupSoft.milliPerGCAFrame;

				//If first time OR the frame differences had reached the threshold count.
				if (polyValLastFrame == -1 || curFrame - polyValLastFrame >= framePerPolyValBin) {
					polyValLastFrame = curFrame;
					executeNow = true;
				}

				if (executeNow) {
					//Get all the inputs of global dist.
					ArrayList<Vertex> allData = txGraph.getVerticesOfClass(DBCN.V.globalDist.in.cn);

					if (allData.isEmpty())
						continue;

					double accumulatedSum = 0;
					int totalCount = 0;
					int errCount = 0;

					double lastTemporaryPolyVal = 0d;
					for (Vertex dataVertex : allData) {
						totalCount++;

						//Commit retry model.
						boolean txError = true;
						int txRetried = 0;
						while (txError) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0) {
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
									//Undo the changes as it failed to commit.
									accumulatedSum -= lastTemporaryPolyVal;
								}
								txRetried++;
							}
							txGraph.begin();

							dataVertex = Util.vReload(dataVertex, txGraph);
							Double newData = dataVertex.getProperty(LP.data);
							if (newData == null) {
								StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL
										, "DB inconsistent error at STMServer globalDistUpdate, data is null. Ignored.");
								errCount++;
							}
							else if (newData.isNaN()) {
								StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL
										, "DB inconsistent error at STMServer globalDistUpdate, data is NaN! Ignored.");
								errCount++;
							}
							else {
								accumulatedSum += newData;
								lastTemporaryPolyVal = newData;
							}

							//For some reason the remove() operation is immune to transaction.
							try {
								dataVertex.remove();
							}
							catch (OConcurrentModificationException ocme) {
								txError = true;
								txGraph.rollback();
								//Undo the changes as it failed to commit.
								accumulatedSum -= lastTemporaryPolyVal;
								continue;
							}
							catch (ORecordNotFoundException ornfe) {
								StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
										"ORecordNotFoundException for vertex " + dataVertex + "; Ignored.");
								txGraph.rollback();
								errCount++;
								break;
							}
							txError = txGraph.finalizeTask(true);
						}
					}

					//Calculate its average and replace the old globalDist data.
					double currentAverage = accumulatedSum / (double) (totalCount - errCount);

					//The queue had already been init at its declaration statement for once and only once.
					//If the queue has not reached the max bin count yet, only possible during initial startup, just insert the data into it.
					if (polyValBin.size() < maxPolyValBinCount)
						polyValBin.offer(currentAverage);
					else {
						//Else we remove the first element and replaces it with new average value at the back.
						polyValBin.poll();
						polyValBin.offer(currentAverage);
					}

					//Calculate the final average of all bin.
					double finalSum = 0d;
					for (Double d : polyValBin)
						finalSum += d.doubleValue();
					//Divide by polyValBin size instead of the starting size for fairness as it might not had reached the max count yet when
					//we are trying to calculate it.
					double finalAverage = finalSum / polyValBin.size();

					//Commit retry model.
					boolean txError2 = true;
					int txRetried2 = 0;
					while (txError2) {
						if (txRetried2 > StartupSoft.dbErrMaxRetryCount) {
							throw new IllegalStateException("Failed to complete transaction after number of retry:"
									+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
						}
						else if (txError2) {
							if (txRetried2 != 0)
								Util.sleep(StartupSoft.dbErrRetrySleepTime);
							txRetried2++;
						}
						txGraph.begin();

						Util.removeAllVertexFromClass(DBCN.V.globalDist.out.cn, txGraph);
						Vertex result = txGraph.addVertex(DBCN.V.globalDist.out.cn, DBCN.V.globalDist.out.cn);
						result.setProperty(LP.data, finalAverage);

						txError2 = txGraph.finalizeTask(true);
					}
					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM,
							"GlobalDist update:" + finalAverage + "; Errorneous vertex count: " + errCount);
				}
			}

			/*
			 * Get next task for crawler by posting jobs to registered crawler. Specific crawler might have specific task to be posted to.
			 * TODO: Use map reduce to assign task to other nodes. By work type then by size.
			 * TODO:make sure you do load balancing based on power of the computing node and task size, if power is low or overloaded, give less task.
			 * Thread Allowed : 1
			 */
			if (checkRole(STMTASK.crawlerTaskAssign)) {
				//Switch to next type of task 1 by 1.
				for (int i=0; i<CRAWLER_TASK_ASSIGNMENT.taskList.length; i++) {
					//Get all the tasks and workers from particular class.
					ArrayList<Vertex> taskList = txGraph.getVerticesOfClass(CRAWLER_TASK_ASSIGNMENT.taskList[i]);
					ArrayList<Vertex> workerList = txGraph.getVerticesOfClass(CRAWLER_TASK_ASSIGNMENT.workerList[i]);

					int taskSize = taskList.size();
					int workerSize = workerList.size();

					//Calculate how many task can a worker evenly get and how many is excess.
					//Integer precision loss during these 2 calculation is expected and required to work.
					int excessTask = 0;
					int evenTask = 0;
					if (workerSize != 0) {
						excessTask = taskSize % workerSize;
						evenTask = taskSize / workerSize;
					}
					else
						evenTask = taskSize;

					//A list to record how many work should a worker execute.
					int[] taskForEachWorker = new int[workerSize];
					for (int f=0; f<taskForEachWorker.length; f++) {
						taskForEachWorker[f] = evenTask;
					}
					//http://stackoverflow.com/questions/363681/generating-random-integers-in-a-specific-range
					//Randomly add one more task to worker for each iteration.
					while (excessTask > 0) {
						int seed = ThreadLocalRandom.current().nextInt(0, workerSize);
						taskForEachWorker[seed]++;
						excessTask--;
					}

					int assignedTaskCount = 0;
					//worker's data field is its uid, setup during registration (subscription) to this work.
					for (int j=0; j<workerSize; j++) {
						Vertex worker = workerList.get(j);
						String storageId = worker.getProperty(LP.data);

						//add the task to the corresponding worker's local storage, then set the task state to processing.
						for (int k=0; k<taskForEachWorker[j]; k++) {
							//Crawler expect taskVertex -source> taskDetailVertex (data: taskDetail) -source> actualDataGeneralVertex
							//User input format: create 2 vertex, task and task detail. task contain data field: worker uid.
							//task detail vertex contain data field: serialized string task detail, edge 'source' from task vertex.
							//taskVertex created by user will be removed, then create another taskVertex at the selected crawler
							//where he will also remove that vertex to treat it as processed, and add to completed storage, remove
							//temporary processing vertex, so we will not forward the task to other crawler as the crawler had
							//responded and tell us he had completed.
							txGraph.begin();
							Vertex taskVertex = taskList.get(assignedTaskCount);
							Vertex taskDetailVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);
							TaskDetail taskDetail = Util.kryoDeserialize( (byte[])taskDetailVertex.getProperty(LP.data), TaskDetail.class);
							taskVertex.remove();

							//update to processing state, then remove the original task, meaning that he now have been adopted by a worker.
							Vertex processingVertex = txGraph.addVertex(taskDetail.processingAddr, taskDetail.processingAddr);
							processingVertex.addEdge(DBCN.E.source, taskDetailVertex);

							//Set this task to worker by adding a new vertex and edge to the job.
							Vertex workerNewTask = txGraph.addVertex(storageId, storageId);
							workerNewTask.addEdge(DBCN.E.source, taskDetailVertex);
							workerNewTask.addEdge(DBCN.E.processing, processingVertex);

							assignedTaskCount++;

							txGraph.finalizeTask();
						}
					}
				}
			}

			/*
			 * Fetch raw data from devices into database and setup them for their next operation phrase.
			 * Only 1 thread should do this. This will be an infinite loop.
			 * NOTE: THIS INCLUDE PO feedback data!
			 * TODO: We doesn't consider dynamically adding new devices, make it possible in the future by removing this clause, contract is simple:
			 * make sure data arrives to raw data ICL phrase in timely fashion, how you get the data in here, is up to your implementation.
			 *
			 * NOTE: This fetch include PO Feedback from the real device.
			 */
			if (checkRole(STMTASK.rawDataFetchFromDevice)) {
				/*
				 * Add more new device input method here, their url, their fetch method.
				 * Contract is you MUST put the relevant data into the custom made relevant vertex, and add the custom made vertex class
				 * entry in the LTM.java in order to use the usual processing route during the class creation.
				 * It doesn't matter where you get the data from, you build your custom fetch policy here.
				 */
				//Startup all data fetching thread once, separate thread for each device to improve performance and avoid intermittent latency.
				if (!rawDataFetchStarted) {
					rawDataFetchStarted = true;

					new Thread(new Runnable() {
						@Override
						public void run() {
							while (!isHalt()) {
								if (StartupSoft.mockDevice.get()) {
									//Mocked sleep time latency, 1000ms / 33ms = 30.3frames. For real application synchronization purposes.
									Util.sleep(50l);
								}

								Graph txGraph = StartupSoft.factory.getTx();
								//Set the logger in order to use shorthand version of finalizeTask().
								txGraph.loggerSet(StartupSoft.logger, logCredential);

								byte[] imgData = new byte[4096];
								if (StartupSoft.mockDevice.get()) {
									Path pathImg = Paths.get("resources/mockedData/mockedImg.jpg");
									try {
										imgData = Files.readAllBytes(pathImg);
									} catch (IOException e) {
										throw new IllegalStateException("Visual Input Device Error.", e);
									}
								}
								else {
									//Setup all the connection to device to fetch data.
									InputStream visualInput = null;
									if (visualUrl.equals(""))
										visualUrl = txGraph.getFirstVertexOfClass(DBCN.V.extInterface.hw.camera.cam1.cn).getProperty(LP.data);
									URL actualVisualUrlConnection = null;
									try {
										actualVisualUrlConnection = new URL(visualUrl);
									} catch (MalformedURLException e1) {
										throw new IllegalStateException ("Unreachable visual URL:" + visualUrl);
										//TODO: perform recovery by keep updating the url until it becomes valid by keep fetching it from DB.
									}

									//Must reconnect to the server every time in order to get the latest frame, retaining the connection will not
									//work as it will always be the same image for unknown reason.
									try {
										visualInput = actualVisualUrlConnection.openStream();
									} catch (IOException e) {
										throw new IllegalStateException("Visual Input Device Error.", e);
									}

									//Fetch frame from camera using its hosted url.
									try {
										imgData = IOUtils.toByteArray(visualInput);
										visualInput.close();
									} catch (IOException e) {
										throw new IllegalStateException("Visual Input Device Error.", e);
									}
								}

								txGraph.begin();
								//Add data to LTM for permanent record.
								//Note: Upmost raw data general vertex has no parent. Means all LTM initial fetch from device into DB (operation here) has no parent.
								Vertex visualDataVertexCamera1 = txGraph.addVertex(DBCN.V.LTM.rawData.PI.dev.camera1.cn, DBCN.V.LTM.rawData.PI.dev.camera1.cn);
								Vertex visualGeneralVertexCamera1 = txGraph.addVertex(DBCN.V.general.rawData.PI.dev.camera1.cn, DBCN.V.general.rawData.PI.dev.camera1.cn);
								visualDataVertexCamera1.setProperty(LP.data, imgData);
								visualDataVertexCamera1.addEdge(DBCN.E.data, visualGeneralVertexCamera1);

								//All these functions are the next phrase of raw data.
								//Add to rawDataDistCacl. If source is not available, we will guarantee to give a data link.
								//This case only data link is feastable as it refers to an individual raw data, but source refer to a class of many data.
								//Look at crawlerTaskInput.policy for more detail.
								Vertex visualTaskDistCaclVertex = txGraph.addVertex(DBCN.V.jobCenter.crawler.rawDataDistCacl.task.cn, DBCN.V.jobCenter.crawler.rawDataDistCacl.task.cn);
								Vertex visualTaskDetailDistCaclVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
								visualTaskDistCaclVertex.addEdge(DBCN.E.source, visualTaskDetailDistCaclVertex);

								TaskDetail visualTaskDistCaclDetail = new TaskDetail();
								visualTaskDistCaclDetail.jobId = "-1";
								visualTaskDistCaclDetail.jobType = CRAWLERTASK.rawDataDistCacl;
								visualTaskDistCaclDetail.source = "";
								visualTaskDistCaclDetail.processingAddr = DBCN.V.jobCenter.crawler.rawDataDistCacl.processing.cn;
								visualTaskDistCaclDetail.completedAddr = DBCN.V.jobCenter.crawler.rawDataDistCacl.completed.cn;
								visualTaskDistCaclDetail.replyAddr = DBCN.V.devnull.cn;
								visualTaskDistCaclDetail.start = -1;
								visualTaskDistCaclDetail.end = -1;
								visualTaskDetailDistCaclVertex.addEdge(DBCN.E.source, visualGeneralVertexCamera1);
								visualTaskDetailDistCaclVertex.setProperty(LP.data, Util.kryoSerialize(visualTaskDistCaclDetail) );
								txGraph.finalizeTask();
							}
						}
					}).start();

					new Thread(new Runnable() {
						@Override
						public void run() {
							while (!isHalt()) {
								if (StartupSoft.mockDevice.get()) {
									//Mocked sleep time latency, 1000ms / 10 (44100 / 10 = 4410hz, 100ms) = 10frames. For real application synchronization purposes.
									Util.sleep(100l);
								}

								Graph txGraph = StartupSoft.factory.getTx();
								//Set the logger in order to use shorthand version of finalizeTask().
								txGraph.loggerSet(StartupSoft.logger, logCredential);

								//4410 is 100ms precision + 46 byte of header.
								byte[] audioData = null;
								if (StartupSoft.mockDevice.get()) {
									Path pathAudio = Paths.get("resources/mockedData/mockedAudio.wav");
									try {
										audioData = Files.readAllBytes(pathAudio);
									} catch (IOException e) {
										throw new IllegalStateException("Audio Input Device Error.", e);
									}
								}
								else {
									//Connect to audio server if not yet connected before.
									if (audioInputStream == null && audioDataInputStream == null) {
										if (audioUrl.equals(""))
											audioUrl = txGraph.getFirstVertexOfClass(DBCN.V.extInterface.hw.mic.mic1.cn).getProperty(LP.data);
										URL actualAudioUrlConnection = null;
										try {
											actualAudioUrlConnection = new URL(audioUrl);
										} catch (MalformedURLException e1) {
											throw new IllegalStateException ("Unreachable audio URL:" + audioUrl);
											//TODO: perform recovery by keep updating the url until it becomes valid by keep fetching it from DB.
										}

										try {
											audioInputStream = actualAudioUrlConnection.openStream();
										} catch (IOException e) {
											throw new IllegalStateException("Audio Input Device Error.", e);
										}
										audioDataInputStream = new DataInputStream(audioInputStream);

										//Read and skip the first 46 byte header so further stream are pure data.
										byte[] audioHeaderSkip = new byte[46];
										try {
											audioDataInputStream.readFully(audioHeaderSkip);
										} catch (IOException e) {
											throw new IllegalStateException("Audio Input Device Error.", e);
										}

										//Set audio header.
										//Select the sample length to extract header from. Tenth means 100ms, thus 4410 data (4410 * 10 = 44100hz, 100ms * 10 = 1s)
										Path path = Paths.get("resources/audioICL/audioHeader/tenthSecSample.wav");
										try {
											byte[] data = Files.readAllBytes(path);
											ICL.Audio.setAudioDataHeader(data);
										} catch (IOException e) {
											e.printStackTrace();
										}
									}

									//Already connected, fetch data now.
									//Aka pure data.
									byte[] audioDataWithoutHeader = new byte[4410];
									//Fetch audio, it doesn't have header as it is a continuous stream.
									try {
										audioDataInputStream.readFully(audioDataWithoutHeader);
									} catch (IOException e) {
										throw new IllegalStateException("Audio Input Device Error.", e);
									}
									//Add in the header, final audio data is expected to be a self sustained audio file (playable by itself),
									//and also to ease bookkeeping as in the future there may be many audio source with different format.
									audioData = Util.concatByteArray(ICL.Audio.getAudioDataHeader(), audioDataWithoutHeader);
								}

								//Add data to LTM for permanent record.
								//Note: Upmost raw data general vertex has no parent.
								txGraph.begin();
								Vertex audioDataVertexMic1 = txGraph.addVertex(DBCN.V.LTM.rawData.PI.dev.mic1.cn, DBCN.V.LTM.rawData.PI.dev.mic1.cn);
								Vertex audioGeneralVertexMic1 = txGraph.addVertex(DBCN.V.general.rawData.PI.dev.mic1.cn, DBCN.V.general.rawData.PI.dev.mic1.cn);
								audioDataVertexMic1.setProperty(LP.data, audioData);
								audioDataVertexMic1.addEdge(DBCN.E.data, audioGeneralVertexMic1);

								//All these functions are the next phrase of raw data.
								//Add to rawDataDistCacl. If source is not available, we will guarantee to give a data link.
								//This case only data link is feastable as it refers to an individual raw data, but source refer to a class of many data.
								//Look at crawlerTaskInput.policy for more detail.
								Vertex audioTaskDistCaclVertex = txGraph.addVertex(DBCN.V.jobCenter.crawler.rawDataDistCacl.task.cn, DBCN.V.jobCenter.crawler.rawDataDistCacl.task.cn);
								Vertex audioTaskDetailDistCaclVertex = txGraph.addVertex(DBCN.V.taskDetail.cn, DBCN.V.taskDetail.cn);
								audioTaskDistCaclVertex.addEdge(DBCN.E.source, audioTaskDetailDistCaclVertex);

								TaskDetail audioTaskDistCaclDetail = new TaskDetail();
								audioTaskDistCaclDetail.jobId = "-1";
								audioTaskDistCaclDetail.jobType = CRAWLERTASK.rawDataDistCacl;
								audioTaskDistCaclDetail.source = "";
								audioTaskDistCaclDetail.processingAddr = DBCN.V.jobCenter.crawler.rawDataDistCacl.processing.cn;
								audioTaskDistCaclDetail.completedAddr = DBCN.V.jobCenter.crawler.rawDataDistCacl.completed.cn;
								audioTaskDistCaclDetail.replyAddr = DBCN.V.devnull.cn;
								audioTaskDistCaclDetail.start = -1;
								audioTaskDistCaclDetail.end = -1;
								audioTaskDetailDistCaclVertex.addEdge(DBCN.E.source, audioGeneralVertexMic1);
								audioTaskDetailDistCaclVertex.setProperty(LP.data, Util.kryoSerialize(audioTaskDistCaclDetail) );
								txGraph.finalizeTask();
							}
						}
					}).start();

					new Thread(new Runnable() {
						@Override
						public void run() {
							while (!isHalt()) {
								//TODO: NOTE Ignore rpi for now, uses direct path instead for simpler access.
								boolean rpiExist = false;

								//TODO: This is a shorthand version, should create a DB class to represent this, to make it functional
								//for other STMServer who doesn't have the local ext path files.
								//It is bad to use absolute path, should carry this forward from console via its initial args,
								//For both ConsoleConfig and from there get externalHardwareConfig's path.
								ExternalIOConfig hardwareConfig = new ExternalIOConfig();
								try {
									YamlReader hardwareConfigReader = new YamlReader(new FileReader("config/externalIOConfig.yml"));
									hardwareConfig = hardwareConfigReader.read(ExternalIOConfig.class);
								} catch (YamlException | FileNotFoundException e) {
									throw new IllegalStateException(e);
								}

								if (StartupSoft.mockDevice.get()) {
									//Mocked sleep time latency, 1000ms / 10ms = 100frames. For real application synchronization purposes.
									Util.sleep(10l);
								}

								Graph txGraph = StartupSoft.factory.getTx();
								//Set the logger in order to use shorthand version of finalizeTask().
								txGraph.loggerSet(StartupSoft.logger, logCredential);

								//Use the real address if not in mocking mode.
								if (!StartupSoft.mockDevice.get()) {
									rpiUrl = txGraph.getFirstVertexOfClass(DBCN.V.extInterface.hw.controller.rpi.cn).getProperty(LP.data);
								}

								POInterchange POFeedbackData = new POInterchange();
								if (StartupSoft.mockDevice.get()) {
									POFeedbackData.motor1 = 50d;
									POFeedbackData.motor2 = 50d;
									POFeedbackData.motor3 = 50d;
									POFeedbackData.motor4 = 50d;
									POFeedbackData.speaker1 = new byte[0];
								}
								else if (rpiExist) {
									//Fetch PO feedback data from the devices directly and post them to GCA.
									//Instruction on adding output (feedback) device, add it here at POFeedback, then go to STMServer raw data fetch from device section, add the
									//way how you get that data into the DB system, there are example there. Then add it to ActionScheduler to tell him about this new device,
									//and what data should be ported to that device if during data recreate.
									//Note: PO data doesn't need to be ICL again. Therefore it can will be ported to GCA directly unlike other data which has to pass
									//through distCacl then ICL then GCA in serialized order to avoid concurrent modification.
									String POFeedbackYml = "";
									try {
										Socket clientSocket = new Socket(rpiUrl, 40000);
										DataInputStream inStream = new DataInputStream(clientSocket.getInputStream());
										POFeedbackYml = inStream.readUTF();

										//No need to close stream, close only socket, close socket on the final receiver operation. We are final receiver
										//here, so close the socket.
										clientSocket.close();
									} catch (IOException e) {
										throw new IllegalStateException("RPI Device connect error.", e);
									}
									YamlReader reader = new YamlReader(POFeedbackYml);
									try {
										POFeedbackData = reader.read(POInterchange.class);
									} catch (YamlException e) {
										throw new IllegalStateException("RPI Device Input decode error.", e);
									}
								}
								//Note: this is the latest one, uses direct path to get data.
								//TODO: Speaker feedback not yet implemented.
								else {
									Scanner scan;
									try {
										//Get data from file, if not available, -1d to mark it as not available.
										if (!hardwareConfig.motor1InURL.equals("")) {
											scan = new Scanner(new File(hardwareConfig.motor1InURL));
											POFeedbackData.motor1 = scan.nextDouble();
											scan.close();
										}
										else
											POFeedbackData.motor1 = -1d;
										if (!hardwareConfig.motor2InURL.equals("")) {
											scan = new Scanner(new File(hardwareConfig.motor2InURL));
											POFeedbackData.motor2 = scan.nextDouble();
											scan.close();
										}
										else
											POFeedbackData.motor2 = -1d;
										if (!hardwareConfig.motor3InURL.equals("")) {
											scan = new Scanner(new File(hardwareConfig.motor3InURL));
											POFeedbackData.motor3 = scan.nextDouble();
											scan.close();
										}
										else
											POFeedbackData.motor3 = -1d;
										if (!hardwareConfig.motor4InURL.equals("")) {
											scan = new Scanner(new File(hardwareConfig.motor4InURL));
											POFeedbackData.motor4 = scan.nextDouble();
											scan.close();
										}
										else
											POFeedbackData.motor4 = -1d;
									} catch (FileNotFoundException e) {
										e.printStackTrace();
									}
								}

								//Start a new transaction to avoid retry induced data inconsistency at GCA site. To guarantee idempotent.
								txGraph.begin();
								//Store those data into permanent memory.
								Vertex generalMotor1 = txGraph.addVertex(DBCN.V.general.rawData.POFeedback.dev.motor1.cn, DBCN.V.general.rawData.POFeedback.dev.motor1.cn);
								Vertex generalMotor2 = txGraph.addVertex(DBCN.V.general.rawData.POFeedback.dev.motor2.cn, DBCN.V.general.rawData.POFeedback.dev.motor2.cn);
								Vertex generalMotor3 = txGraph.addVertex(DBCN.V.general.rawData.POFeedback.dev.motor3.cn, DBCN.V.general.rawData.POFeedback.dev.motor3.cn);
								Vertex generalMotor4 = txGraph.addVertex(DBCN.V.general.rawData.POFeedback.dev.motor4.cn, DBCN.V.general.rawData.POFeedback.dev.motor4.cn);

								Vertex dataMotor1 = txGraph.addVertex(DBCN.V.LTM.rawData.POFeedback.dev.motor1.cn, DBCN.V.LTM.rawData.POFeedback.dev.motor1.cn);
								Vertex dataMotor2 = txGraph.addVertex(DBCN.V.LTM.rawData.POFeedback.dev.motor2.cn, DBCN.V.LTM.rawData.POFeedback.dev.motor2.cn);
								Vertex dataMotor3 = txGraph.addVertex(DBCN.V.LTM.rawData.POFeedback.dev.motor3.cn, DBCN.V.LTM.rawData.POFeedback.dev.motor3.cn);
								Vertex dataMotor4 = txGraph.addVertex(DBCN.V.LTM.rawData.POFeedback.dev.motor4.cn, DBCN.V.LTM.rawData.POFeedback.dev.motor4.cn);

								dataMotor1.addEdge(DBCN.E.data, generalMotor1);
								dataMotor2.addEdge(DBCN.E.data, generalMotor2);
								dataMotor3.addEdge(DBCN.E.data, generalMotor3);
								dataMotor4.addEdge(DBCN.E.data, generalMotor4);

								dataMotor1.setProperty(LP.data, POFeedbackData.motor1);
								dataMotor2.setProperty(LP.data, POFeedbackData.motor2);
								dataMotor3.setProperty(LP.data, POFeedbackData.motor3);
								dataMotor4.setProperty(LP.data, POFeedbackData.motor4);
								//Only add it if it has the header and data, sometimes it may return nothing or some trash value indicating null.
								if (POFeedbackData.speaker1.length > 46) {
									Vertex generalSpeaker1 = txGraph.addVertex(DBCN.V.general.rawData.POFeedback.dev.speaker1.cn, DBCN.V.general.rawData.POFeedback.dev.speaker1.cn);
									Vertex dataSpeaker1 = txGraph.addVertex(DBCN.V.LTM.rawData.POFeedback.dev.speaker1.cn, DBCN.V.LTM.rawData.POFeedback.dev.speaker1.cn);
									dataSpeaker1.addEdge(DBCN.E.data, generalSpeaker1);

									dataSpeaker1.setProperty(LP.data, POFeedbackData.speaker1);

									//The scanDist is a relatively fast operation, need it right now as we currently didnt forward it to default scanDist and ICL operation at crawler.
									//It must be here after the data vertex had set edge to general vertex as the function expects that.
									double speaker1PolyVal = ICL.Audio.scanAudioDistribution(generalSpeaker1);
									generalSpeaker1.setProperty(LP.polyVal, speaker1PolyVal);

									STMClient.rawDataAddToGCAQueue(generalSpeaker1, txGraph);
								}

								//As the POFeedback movement data never gets to scanDist and ICL phrase (where they usually set polyVal at that time),
								//thus have to set it right here as after it pass through GCA, it must have its polyVal ready in its general vertex.
								generalMotor1.setProperty(LP.polyVal, POFeedbackData.motor1);
								generalMotor2.setProperty(LP.polyVal, POFeedbackData.motor2);
								generalMotor3.setProperty(LP.polyVal, POFeedbackData.motor3);
								generalMotor4.setProperty(LP.polyVal, POFeedbackData.motor4);
								txGraph.finalizeTask();

								//Commit retry model.
								boolean txError = true;
								int txRetried = 0;
								while (txError) {
									if (txRetried > StartupSoft.dbErrMaxRetryCount) {
										throw new IllegalStateException("Failed to complete transaction after number of retry:"
												+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
									}
									else if (txError) {
										if (txRetried != 0)
											Util.sleep(StartupSoft.dbErrRetrySleepTime);
										txRetried++;

										generalMotor1 = Util.vReload(generalMotor1, txGraph);
										generalMotor2 = Util.vReload(generalMotor2, txGraph);
										generalMotor3 = Util.vReload(generalMotor3, txGraph);
										generalMotor4 = Util.vReload(generalMotor4, txGraph);
									}
									txGraph.begin();

									//May be concurrently modified by direct query.
									//Add it to GCA.
									STMClient.rawDataAddToGCAQueue(generalMotor1, txGraph);
									STMClient.rawDataAddToGCAQueue(generalMotor2, txGraph);
									STMClient.rawDataAddToGCAQueue(generalMotor3, txGraph);
									STMClient.rawDataAddToGCAQueue(generalMotor4, txGraph);

									txError = txGraph.finalizeTask(true);
								}
								/*
								 * All the 'process' type general vertex must create an 'occurrence' edge to its previous frame, to indicate that they
								 * are recurring as the successor of the previous entry. If they are the first entry, we will skip this.
								 * This is required for WM's PaRc to pass for processes feedback guarantees.
								 * As processes may be elected as part of the exp requirement, its exp result should contain relevant entry to tell
								 * that what actually happened next.
								 *
								 * For analog data type (motor, speaker OUT), they are not ICL-ed thus they will not have an 'occurrence' edge
								 * but the edge is a mandatory requirement for PaRc to pass, thus we delegated that edge creation task to
								 * the moment when it is during PaRc check, he will check if current type is raw data analog type,
								 * if so they will try to create an 'occurrence' edge to it if it matches the specified requirements,
								 * detail at WM's checkRidExistInReality().
								 */
							}
						}
					}).start();
				}
				//This external thread serve no more purpose, just keep sleeping until the end of time.
				Util.sleep(1500l);
			}

			//TODO Synchronize GCA frames to certain threshold, as we allow premature commit, the data itself may be already in use
			//by other threads while it had not done commiting, thus we should mitigate those uncommited stuff to the next GCA frame instead
			//of dangling it.

			/*
			 * NOTE NOTE For some unknown reason, the loop based commit just doesn't work, some data will be randomly skipped or ignored.
			 * Thus making edges missing or wrong.
			 * Configured prematureCommitCount to -1 to disable that thus it should now work fine.
			 */
			/*
			 * Raw Data based GCA doesn't require any retries on commit() (finalizeTask()) as it is a serialized operation fetch->distCacl->ICL->GCA.
			 * Only after GCA then it is eligible for further DM and WM process.
			 * Group all latest raw data into a single vertex for future reference.
			 * Thread Allowed : 1
			 */
			if (checkRole(STMTASK.rawDataGCA)) {
				//Timing logic:  100frames per sec, 10ms per frame, frame index must be greater than us in order to indicate that it is time
				//to move forward. 0~6ms GCA normal, 6~9ms GCAMain, where the curMs hold this data. 2/3 for processing, 1/3 for finalization.
				boolean executeNow = false;
				long curTime = System.currentTimeMillis();
				long curFrame = curTime / StartupSoft.milliPerGCAFrame;
				long curMs = curTime % StartupSoft.milliPerGCAFrame;
				if (curIndexRawDataGCA == -1) {
					curIndexRawDataGCA = curFrame;
					executeNow = true;
				}
				else if (curFrame >= curIndexRawDataGCA) {
					if (curFrame - curIndexRawDataGCA > 1 && GCAFrameSkipWarn)
						StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"Frame skipped at GCA by: " + ( curFrame - curIndexRawDataGCA - 1) );
					if (curMs >= StartupSoft.milliPerGCAFrame * 2 / 3 && GCATimeExceedWarn)
						StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"GCA frame start is time late, current milli: " + curMs);

					curIndexRawDataGCA = curFrame;
					executeNow = true;
				}

				if (!isHalt() && executeNow) {
					//Task vertex is a temporary holding vertex that is created when the source vertex commits themselves to GCA, serves as a record, and he hold
					//another edge 'source' which links to the actual source's general vertex.
					ArrayList<Vertex> allTaskVertexArray = txGraph.getVerticesOfClass(DBCN.V.jobCenter.STM.GCAMain.rawData.task.cn);
					if (allTaskVertexArray.isEmpty()) {
						continue;
					}

					//Create 2 new vertex, general and data(group) vertex.
					txGraph.begin();
					Vertex groupVertex = txGraph.addVertex(DBCN.V.LTM.GCAMain.rawData.cn, DBCN.V.LTM.GCAMain.rawData.cn);
					Vertex generalVertex = txGraph.addVertex(DBCN.V.general.GCAMain.rawData.cn, DBCN.V.general.GCAMain.rawData.cn);
					groupVertex.addEdge(DBCN.E.data, generalVertex);
					txGraph.finalizeTask();

					//Premature commit, to avoid consuming too much memory and blocking too much data.
					boolean GCAPrematureCommit = GCAPrematureCommitCount == -1 ? false : true;

					//Disabled as we now run in one transaction per iteration, no more multiple iteration commit once.
					if (GCAPrematureCommit) {
						//Commit retry model with premature commit.
						int accumulatedCycle = 0;
						int indexThisRound = 0;
						boolean txError = true;
						int txRetried = 0;
						while (true) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;
							}
							txGraph.begin();

							//Reload it as it is going to be reused again in another transaction.
							groupVertex = Util.vReload(groupVertex, txGraph);

							//Only execute if it still has more vertex to go on and before reaching the premature commit limits.
							//IndexThisRound will be reset every round and incremented properly.
							for (indexThisRound = 0;
									(accumulatedCycle + indexThisRound < allTaskVertexArray.size() && indexThisRound < GCAPrematureCommitCount);
									indexThisRound++) {
								//Only append the indexThisRound into the accumulated one after this whole round is complete,
								//thus we can know where to continue in case of failure.
								Vertex taskVertex = allTaskVertexArray.get(accumulatedCycle + indexThisRound);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);

								String dataVertexClass = actualDataVertex.getCName();
								if (!Util.equalAny(dataVertexClass, LTM.VISUAL_RAW) && !Util.equalAny(dataVertexClass, LTM.AUDIO_RAW)
										&& !Util.equalAny(dataVertexClass, LTM.MOVEMENT))
									throw new IllegalArgumentException("Unsupported type at rawDataGCA: " + dataVertexClass + " RID: " + actualDataVertex);

								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();
							}
							txError = txGraph.finalizeTask(true);

							if (!txError) {
								accumulatedCycle += indexThisRound;
								//Reset the retry allowance as during large dataset, those allowance may not be enough.
								txRetried = 0;

								//If we had computed all the vertex, break.
								if (accumulatedCycle == allTaskVertexArray.size())
									break;
							}
						}
					}

					else {
						for (int i=0; i<allTaskVertexArray.size(); i++) {
							//Commit retry model.
							boolean txError = true;
							int txRetried = 0;
							while (txError) {
								if (txRetried > StartupSoft.dbErrMaxRetryCount) {
									throw new IllegalStateException("Failed to complete transaction after number of retry:"
											+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
								}
								else if (txError) {
									if (txRetried != 0)
										Util.sleep(StartupSoft.dbErrRetrySleepTime);
								}
								txGraph.begin();

								//Reload it as it is going to be reused again in another transaction.
								groupVertex = Util.vReload(groupVertex, txGraph);

								Vertex taskVertex = allTaskVertexArray.get(i);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);
								String dataVertexClass = actualDataVertex.getCName();
								//Note that POFeedback should be at its own dedicated GCA.
								if (!Util.equalAny(dataVertexClass, LTM.VISUAL_RAW) && !Util.equalAny(dataVertexClass, LTM.AUDIO_RAW)
										&& !Util.equalAny(dataVertexClass, LTM.MOVEMENT))
									throw new IllegalArgumentException("Unsupported type at rawDataGCA: " + dataVertexClass + " RID: " + actualDataVertex);

								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();

								txError = txGraph.finalizeTask(true);
							}
						}
					}

					txGraph.begin();

					generalVertex = Util.vReload(generalVertex, txGraph);

					//Add to GCAMain, so he can group all these GCA result together.
					Vertex addToGCAMain = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.task.cn, DBCN.V.jobCenter.STM.GCAMain.task.cn);
					addToGCAMain.addEdge(DBCN.E.source, generalVertex);

					//Add record to job center telling them you had completed the task. Just a basic record.
					Vertex completed = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.rawData.completed.cn, DBCN.V.jobCenter.STM.GCAMain.rawData.completed.cn);
					completed.setProperty(LP.nodeUID, config.parentUid);

					txGraph.finalizeTask();

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "rawDataGCA OK.");

				}
			}

			/*
			 * Raw Data ICL GCA doesn't require any retries on commit() (finalizeTask()) as it is a serialized operation fetch->distCacl->ICL->GCA.
			 * ICL GCA is part of the ICL->GCA part where it is serialized. Only after GCA then it is eligible for further DWDM and WM process.
			 * Group all latest raw data pattern identified or generated into a single vertex for future reference.
			 * Thread Allowed : 1
			 */
			if (checkRole(STMTASK.rawDataICLGCA)) {
				//Timing logic:  100frames per sec, 10ms per frame, frame index must be greater than us in order to indicate that it is time
				//to move forward. 0~6ms GCA normal, 6~9ms GCAMain, where the curMs hold this data. 2/3 for processing, 1/3 for finalization.
				boolean executeNow = false;
				long curTime = System.currentTimeMillis();
				long curFrame = curTime / StartupSoft.milliPerGCAFrame;
				long curMs = curTime % StartupSoft.milliPerGCAFrame;
				if (curIndexRawDataICLGCA == -1) {
					curIndexRawDataICLGCA = curFrame;
					executeNow = true;
				}
				else if (curFrame >= curIndexRawDataICLGCA) {
					if (curFrame - curIndexRawDataICLGCA > 1 && GCAFrameSkipWarn)
						StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"Frame skipped at GCA by: " + ( curFrame - curIndexRawDataICLGCA - 1) );
					if (curMs >= StartupSoft.milliPerGCAFrame * 2 / 3 && GCATimeExceedWarn)
						StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"GCA frame start is time late, current milli: " + curMs);

					curIndexRawDataICLGCA = curFrame;
					executeNow = true;
				}

				if (!isHalt() && executeNow) {
					//Task vertex is a temporary holding vertex that is created when the source vertex commits themselves to GCA, serves as a record, and he hold
					//another edge 'source' which links to the actual source's general vertex.
					ArrayList<Vertex> allTaskVertexArray = txGraph.getVerticesOfClass(DBCN.V.jobCenter.STM.GCAMain.rawDataICL.task.cn);
					if (allTaskVertexArray.isEmpty()) {
						continue;
					}

					//Create 2 new vertex, general and data(group) vertex.
					txGraph.begin();
					Vertex groupVertex = txGraph.addVertex(DBCN.V.LTM.GCAMain.rawDataICL.cn, DBCN.V.LTM.GCAMain.rawDataICL.cn);
					Vertex generalVertex = txGraph.addVertex(DBCN.V.general.GCAMain.rawDataICL.cn, DBCN.V.general.GCAMain.rawDataICL.cn);
					groupVertex.addEdge(DBCN.E.data, generalVertex);
					txGraph.finalizeTask();

					//Premature commit, to avoid consuming too much memory and blocking too much data.
					boolean GCAPrematureCommit = GCAPrematureCommitCount == -1 ? false : true;

					//Disabled as we now run in one transaction per iteration, no more multiple iteration commit once.
					if (GCAPrematureCommit) {
						//Commit retry model with premature commit.
						int accumulatedCycle = 0;
						int indexThisRound = 0;
						boolean txError = true;
						int txRetried = 0;
						while (true) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;
							}
							txGraph.begin();

							//Reload it as it is going to be reused again in another transaction.
							groupVertex = Util.vReload(groupVertex, txGraph);

							//Only execute if it still has more vertex to go on and before reaching the premature commit limits.
							//IndexThisRound will be reset every round and incremented properly.
							for (indexThisRound = 0;
									(accumulatedCycle + indexThisRound < allTaskVertexArray.size() && indexThisRound < GCAPrematureCommitCount);
									indexThisRound++) {
								//Only append the indexThisRound into the accumulated one after this whole round is complete,
								//thus we can know where to continue in case of failure.
								Vertex taskVertex = allTaskVertexArray.get(accumulatedCycle + indexThisRound);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);

								String dataVertexClass = actualDataVertex.getCName();
								//Note that POFeedback should be at its own dedicated GCA.
								if (!Util.equalAny(dataVertexClass, LTM.VISUAL_ICL) && !Util.equalAny(dataVertexClass, LTM.AUDIO_ICL))
									throw new IllegalArgumentException("Unsupported type at rawDataICLGCA: " + dataVertexClass + " RID: " + actualDataVertex);


								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();
							}
							txError = txGraph.finalizeTask(true);

							if (!txError) {
								accumulatedCycle += indexThisRound;
								//Reset the retry allowance as during large dataset, those allowance may not be enough.
								txRetried = 0;

								//If we had computed all the vertex, break.
								if (accumulatedCycle == allTaskVertexArray.size())
									break;
							}
						}
					}

					else {
						for (int i=0; i<allTaskVertexArray.size(); i++) {
							//Commit retry model.
							boolean txError = true;
							int txRetried = 0;
							while (txError) {
								if (txRetried > StartupSoft.dbErrMaxRetryCount) {
									throw new IllegalStateException("Failed to complete transaction after number of retry:"
											+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
								}
								else if (txError) {
									if (txRetried != 0)
										Util.sleep(StartupSoft.dbErrRetrySleepTime);
								}
								txGraph.begin();

								//Reload it as it is going to be reused again in another transaction.
								groupVertex = Util.vReload(groupVertex, txGraph);

								Vertex taskVertex = allTaskVertexArray.get(i);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);
								String dataVertexClass = actualDataVertex.getCName();
								//Note that POFeedback should be at its own dedicated GCA.
								if (!Util.equalAny(dataVertexClass, LTM.VISUAL_ICL) && !Util.equalAny(dataVertexClass, LTM.AUDIO_ICL))
									throw new IllegalArgumentException("Unsupported type at rawDataICLGCA: " + dataVertexClass + " RID: " + actualDataVertex);
								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();

								txError = txGraph.finalizeTask(true);
							}
						}
					}

					txGraph.begin();

					generalVertex = Util.vReload(generalVertex, txGraph);

					//Add to GCAMain, so he can group all these GCA result together.
					Vertex addToGCAMain = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.task.cn, DBCN.V.jobCenter.STM.GCAMain.task.cn);
					addToGCAMain.addEdge(DBCN.E.source, generalVertex);

					//Add record to job center telling them you had completed the task. Just a basic record.
					Vertex completed = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.rawDataICL.completed.cn, DBCN.V.jobCenter.STM.GCAMain.rawDataICL.completed.cn);
					completed.setProperty(LP.nodeUID, config.parentUid);

					txGraph.finalizeTask();

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "rawDataICLGCA OK.");
				}
			}

			/*
			 * Group all latest exp created into a single vertex for future reference.
			 * exp main general of all type should be concurrent safe until after GCA where other exp will only then have link to him.
			 * Thread Allowed : 1
			 */
			if (checkRole(STMTASK.expGCA)) {
				//Timing logic:  100frames per sec, 10ms per frame, frame index must be greater than us in order to indicate that it is time
				//to move forward. 0~6ms GCA normal, 6~9ms GCAMain, where the curMs hold this data. 2/3 for processing, 1/3 for finalization.
				boolean executeNow = false;
				long curTime = System.currentTimeMillis();
				long curFrame = curTime / StartupSoft.milliPerGCAFrame;
				long curMs = curTime % StartupSoft.milliPerGCAFrame;
				if (curIndexexpGCA == -1) {
					curIndexexpGCA = curFrame;
					executeNow = true;
				}
				else if (curFrame >= curIndexexpGCA) {
					if (curFrame - curIndexexpGCA > 1 && GCAFrameSkipWarn)
						StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"Frame skipped at GCA by: " + ( curFrame - curIndexexpGCA - 1) );
					if (curMs >= StartupSoft.milliPerGCAFrame * 2 / 3 && GCATimeExceedWarn)
						StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"GCA frame start is time late, current milli: " + curMs);

					curIndexexpGCA = curFrame;
					executeNow = true;
				}

				if (!isHalt() && executeNow) {
					//Task vertex is a temporary holding vertex that is created when the source vertex commits themselves to GCA, serves as a record, and he hold
					//another edge 'source' which links to the actual source's general vertex.
					ArrayList<Vertex> allTaskVertexArray = txGraph.getVerticesOfClass(DBCN.V.jobCenter.STM.GCAMain.exp.task.cn);
					if (allTaskVertexArray.isEmpty()) {
						continue;
					}

					//Create 2 new vertex, general and data(group) vertex.
					txGraph.begin();
					Vertex groupVertex = txGraph.addVertex(DBCN.V.LTM.GCAMain.exp.cn, DBCN.V.LTM.GCAMain.exp.cn);
					Vertex generalVertex = txGraph.addVertex(DBCN.V.general.GCAMain.exp.cn, DBCN.V.general.GCAMain.exp.cn);
					groupVertex.addEdge(DBCN.E.data, generalVertex);
					txGraph.finalizeTask();

					//Premature commit, to avoid consuming too much memory and blocking too much data.
					boolean GCAPrematureCommit = GCAPrematureCommitCount == -1 ? false : true;

					//Disabled as we now run in one transaction per iteration, no more multiple iteration commit once.
					if (GCAPrematureCommit) {
						//Commit retry model with premature commit.
						int accumulatedCycle = 0;
						int indexThisRound = 0;
						boolean txError = true;
						int txRetried = 0;
						while (true) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;
							}
							txGraph.begin();

							//Reload it as it is going to be reused again in another transaction.
							groupVertex = Util.vReload(groupVertex, txGraph);

							//Only execute if it still has more vertex to go on and before reaching the premature commit limits.
							//IndexThisRound will be reset every round and incremented properly.
							for (indexThisRound = 0;
									(accumulatedCycle + indexThisRound < allTaskVertexArray.size() && indexThisRound < GCAPrematureCommitCount);
									indexThisRound++) {
								//Only append the indexThisRound into the accumulated one after this whole round is complete,
								//thus we can know where to continue in case of failure.
								Vertex taskVertex = allTaskVertexArray.get(accumulatedCycle + indexThisRound);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);

								String dataVertexClass = actualDataVertex.getCName();
								if (!dataVertexClass.equals(DBCN.V.general.exp.cn))
									throw new IllegalArgumentException("Unsupported type at expGCA: " + dataVertexClass + " RID:" + actualDataVertex);

								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();
							}
							txError = txGraph.finalizeTask(true);

							if (!txError) {
								accumulatedCycle += indexThisRound;
								//Reset the retry allowance as during large dataset, those allowance may not be enough.
								txRetried = 0;

								//If we had computed all the vertex, break.
								if (accumulatedCycle == allTaskVertexArray.size())
									break;
							}
						}
					}

					else {
						for (int i=0; i<allTaskVertexArray.size(); i++) {
							//Commit retry model.
							boolean txError = true;
							int txRetried = 0;
							while (txError) {
								if (txRetried > StartupSoft.dbErrMaxRetryCount) {
									throw new IllegalStateException("Failed to complete transaction after number of retry:"
											+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
								}
								else if (txError) {
									if (txRetried != 0)
										Util.sleep(StartupSoft.dbErrRetrySleepTime);
								}
								txGraph.begin();

								//Reload it as it is going to be reused again in another transaction.
								groupVertex = Util.vReload(groupVertex, txGraph);

								Vertex taskVertex = allTaskVertexArray.get(i);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);
								String dataVertexClass = actualDataVertex.getCName();
								if (!dataVertexClass.equals(DBCN.V.general.exp.cn))
									throw new IllegalArgumentException("Unsupported type at expGCA: " + dataVertexClass + " RID:" + actualDataVertex);
								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();

								txError = txGraph.finalizeTask(true);
							}
						}
					}

					txGraph.begin();

					generalVertex = Util.vReload(generalVertex, txGraph);

					//Add to GCAMain, so he can group all these GCA result together.
					Vertex addToGCAMain = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.task.cn, DBCN.V.jobCenter.STM.GCAMain.task.cn);
					addToGCAMain.addEdge(DBCN.E.source, generalVertex);

					//Add record to job center telling them you had completed the task. Just a basic record.
					Vertex completed = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.exp.completed.cn, DBCN.V.jobCenter.STM.GCAMain.exp.completed.cn);
					completed.setProperty(LP.nodeUID, config.parentUid);

					txGraph.finalizeTask();

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "expGCA OK.");
				}
			}

			/*
			 * Group all GCA vertex that had just completed. Also group them into an experience. Last vertex's GCA Main data becomes exp's requirement, their
			 * operation(Physical output functions) becomes exp's process, and current GCA Main data become its result.
			 * GCAMain should be concurrent safe as the whole protocol can only allow 1 instance of GCAMain grouper at any time.
			 * Thread Allowed : 1
			 */
			if (checkRole(STMTASK.GCAMain)) {
				//Timing logic:  100frames per sec, 10ms per frame, frame index must be greater than us in order to indicate that it is time
				//to move forward. 0~6ms GCA normal, 6~9ms GCAMain, where the curMs hold this data. 2/3 for processing, 1/3 for finalization.
				boolean executeNow = false;
				long curTime = System.currentTimeMillis();
				long curFrame = curTime / StartupSoft.milliPerGCAFrame;
				long curMs = curTime % StartupSoft.milliPerGCAFrame;
				if (curIndexGCAMainGCA == -1) {
					curIndexGCAMainGCA = curFrame;
					executeNow = true;
				}
				else if (curFrame >= curIndexGCAMainGCA) {
					if (curFrame - curIndexGCAMainGCA > 1 && GCAFrameSkipWarn)
						StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
								"Frame skipped at GCA by: " + ( curFrame - curIndexGCAMainGCA - 1) );
					//There is no too late for GCAMain, but there is too early.
					while (curMs <= StartupSoft.milliPerGCAFrame * 2 / 3) {
						Util.sleep(1l);
						long newTime = System.currentTimeMillis();
						long newFrame = newTime / StartupSoft.milliPerGCAFrame;
						long newCurMs = newTime % StartupSoft.milliPerGCAFrame;
						//Check whether he overslept by checking if the latter time is earlier than current time, which means that it had
						//gone to the next frame (overslept) OR it had gone to the next frame.
						if (newCurMs < curMs || newFrame > curFrame && GCATimeExceedWarn) {
							curFrame = newFrame;
							StartupSoft.logger.log(logCredential, LVL.WARN, CLA.INTERNAL,
									"GCAMain overslept by: " + (StartupSoft.milliPerGCAFrame - curMs + newCurMs) + "ms.");
							break;
						}
						else
							curMs = newCurMs;
					}
					curIndexGCAMainGCA = curFrame;
					executeNow = true;
				}

				if (!isHalt() && executeNow) {
					//Task vertex is a temporary holding vertex that is created when the source vertex commits themselves to GCA, serves as a record, and he hold
					//another edge 'source' which links to the actual source's general vertex.
					ArrayList<Vertex> allTaskVertexArray = txGraph.getVerticesOfClass(DBCN.V.jobCenter.STM.GCAMain.task.cn);
					if (allTaskVertexArray.isEmpty()) {
						continue;
					}

					//Create 2 new vertex, general and data(group) vertex.
					txGraph.begin();
					Vertex groupVertex = txGraph.addVertex(DBCN.V.LTM.GCAMain.cn, DBCN.V.LTM.GCAMain.cn);
					Vertex generalVertex = txGraph.addVertex(DBCN.V.general.GCAMain.cn, DBCN.V.general.GCAMain.cn);
					groupVertex.addEdge(DBCN.E.data, generalVertex);
					//Timestamp and polyVal are exclusive to GCAMain only, other GCA doesn't have it.
					generalVertex.setProperty(LP.timeStamp, System.currentTimeMillis());
					//This polyVal is globalDist for the current moment, this value is never used, it is stored for archival purposes,
					//to ease future audit operation.
					generalVertex.setProperty(LP.polyVal, STMClient.getGlobalDist(txGraph));
					txGraph.finalizeTask();

					//Premature commit, to avoid consuming too much memory and blocking too much data.
					boolean GCAPrematureCommit = GCAPrematureCommitCount == -1 ? false : true;

					//Disabled as we now run in one transaction per iteration, no more multiple iteration commit once.
					if (GCAPrematureCommit) {
						//Commit retry model with premature commit.
						int accumulatedCycle = 0;
						int indexThisRound = 0;
						boolean txError = true;
						int txRetried = 0;
						while (true) {
							if (txRetried > StartupSoft.dbErrMaxRetryCount) {
								throw new IllegalStateException("Failed to complete transaction after number of retry:"
										+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
							}
							else if (txError) {
								if (txRetried != 0)
									Util.sleep(StartupSoft.dbErrRetrySleepTime);
								txRetried++;
							}
							txGraph.begin();

							//Reload it as it is going to be reused again in another transaction.
							groupVertex = Util.vReload(groupVertex, txGraph);

							//Only execute if it still has more vertex to go on and before reaching the premature commit limits.
							//IndexThisRound will be reset every round and incremented properly.
							for (indexThisRound = 0;
									(accumulatedCycle + indexThisRound < allTaskVertexArray.size() && indexThisRound < GCAPrematureCommitCount);
									indexThisRound++) {
								//Only append the indexThisRound into the accumulated one after this whole round is complete,
								//thus we can know where to continue in case of failure.
								Vertex taskVertex = allTaskVertexArray.get(accumulatedCycle + indexThisRound);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);

								String dataVertexClass = actualDataVertex.getCName();
								if (!dataVertexClass.equals(DBCN.V.general.GCAMain.rawData.cn) && !dataVertexClass.equals(DBCN.V.general.GCAMain.rawDataICL.cn)
										&& !dataVertexClass.equals(DBCN.V.general.GCAMain.exp.cn) && !dataVertexClass.equals(DBCN.V.general.GCAMain.POFeedbackGCA.cn))
									throw new IllegalArgumentException("Unsupported type at GCAMain: " + dataVertexClass + " RID: " + actualDataVertex);

								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();
							}
							txError = txGraph.finalizeTask(true);

							if (!txError) {
								accumulatedCycle += indexThisRound;
								//Reset the retry allowance as during large dataset, those allowance may not be enough.
								txRetried = 0;

								//If we had computed all the vertex, break.
								if (accumulatedCycle == allTaskVertexArray.size())
									break;
							}
						}
					}

					else {
						for (int i=0; i<allTaskVertexArray.size(); i++) {
							//Commit retry model.
							boolean txError = true;
							int txRetried = 0;
							while (txError) {
								if (txRetried > StartupSoft.dbErrMaxRetryCount) {
									throw new IllegalStateException("Failed to complete transaction after number of retry:"
											+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
								}
								else if (txError) {
									if (txRetried != 0)
										Util.sleep(StartupSoft.dbErrRetrySleepTime);
								}
								txGraph.begin();

								//Reload it as it is going to be reused again in another transaction.
								groupVertex = Util.vReload(groupVertex, txGraph);

								Vertex taskVertex = allTaskVertexArray.get(i);
								Vertex actualDataVertex = Util.traverseOnce(taskVertex, Direction.OUT, DBCN.E.source);
								String dataVertexClass = actualDataVertex.getCName();
								if (!dataVertexClass.equals(DBCN.V.general.GCAMain.rawData.cn) && !dataVertexClass.equals(DBCN.V.general.GCAMain.rawDataICL.cn)
										&& !dataVertexClass.equals(DBCN.V.general.GCAMain.exp.cn) && !dataVertexClass.equals(DBCN.V.general.GCAMain.POFeedbackGCA.cn))
									throw new IllegalArgumentException("Unsupported type at GCAMain: " + dataVertexClass + " RID: " + actualDataVertex);
								groupVertex.addEdge(DBCN.E.GCA, actualDataVertex);
								taskVertex.remove();

								txError = txGraph.finalizeTask(true);
							}
						}
					}

					//Must be concurrent proof as the previous GCA can be modified concurrently.
					//Commit retry model.
					boolean txError = true;
					int txRetried = 0;
					while (txError) {
						if (txRetried > StartupSoft.dbErrMaxRetryCount) {
							throw new IllegalStateException("Failed to complete transaction after number of retry:"
									+ StartupSoft.dbErrMaxRetryCount + " with sleep duration of each:" + StartupSoft.dbErrRetrySleepTime);
						}
						else if (txError) {
							if (txRetried != 0)
								Util.sleep(StartupSoft.dbErrRetrySleepTime);
						}

						txGraph.begin();

						generalVertex = Util.vReload(generalVertex, txGraph);

						Vertex previousCompleted = txGraph.getFirstVertexOfClass(DBCN.V.jobCenter.STM.GCAMain.previous.cn);

						//Add record to job center telling them you had completed the task. Just a basic record.
						Vertex completed = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.completed.cn, DBCN.V.jobCenter.STM.GCAMain.completed.cn);
						completed.setProperty(LP.nodeUID, config.parentUid);

						//Make edge to the previously completed GCA main vertex. Then remove it and replace it with us. We are now the last one who just completed the task.
						generalVertex.addEdge(DBCN.E.previous, Util.traverseOnce(previousCompleted, Direction.OUT, DBCN.E.source, DBCN.V.general.GCAMain.cn));

						previousCompleted.remove();

						txError = txGraph.finalizeTask(true);
					}

					//Separated the forwarding into another transaction as the transaction itself may fail and repeat, but at the blink
					//second interval the next stage had fetched it and attempt to process it, making the 'previous' edge data fail
					//to finalize, causing further logic that depends on it confused and raise error as the transaction is being
					//repeated, thus making the temporary vertex that receive invalid, causing myriad of hidden errors.
					txGraph.begin();

					Vertex newPreviousCompleted = txGraph.addVertex(DBCN.V.jobCenter.STM.GCAMain.previous.cn, DBCN.V.jobCenter.STM.GCAMain.previous.cn);
					newPreviousCompleted.addEdge(DBCN.E.source, generalVertex);

					txGraph.finalizeTask();

					//--Begin of GCA import to WM STM logic.
					/*
					 * TODO: Convert all these into SQL and run it at the database directly. Current overhead is double of the original
					 * import and manage. Read 22-9-16.
					 * Used by WM to record currently valid data in WM STM scope.
					 * Importation still uses code instead of SQL as it involves type deduction, which cannot be expressed in SQL.
					 * Update the WM index to add all these GCA elements into the global WM repository where WM's select new attention
					 * logic can see and access these indexed data.
					 * The insertion index cluster selection function will manage the cluster switching, deletion and rebuild of the indexes.
					 */
					//Setup the background executor thread to process the GCA import to WM STM logics.
					if (gcaWMExecutor == null) {
						gcaWMExecutor = new GCAWMExecutor(config.haltIndex);
						Thread thread = new Thread(gcaWMExecutor);
						thread.start();
					}

					final String generalVertexRid = generalVertex.getRid();
					//Add the task to executor.
					gcaWMExecutor.addTask(new Runnable() {
						@Override
						public void run() {
							Graph txGraph = StartupSoft.factory.getTx();
							txGraph.loggerSet(StartupSoft.logger, logCredential);

							ArrayList<Vertex> originalVertexList = new ArrayList<Vertex>();
							Vertex generalVertex = Util.ridToVertex(generalVertexRid, txGraph);

							//Traverse and store all of their actual GCA data RIDs into a list.
							Vertex GCADataVertex = Util.traverseOnce(generalVertex, Direction.IN, DBCN.E.data, DBCN.V.LTM.GCAMain.cn);
							ArrayList<Vertex> specificGCAGeneralVertexList = Util.traverse(GCADataVertex, Direction.OUT, DBCN.E.GCA);
							for (Vertex specificGCAGeneral : specificGCAGeneralVertexList) {
								Vertex specificGCADataVertex = Util.traverseOnce(specificGCAGeneral, Direction.IN, DBCN.E.data);
								ArrayList<Vertex> specificGCAAcutalDataList = Util.traverse(specificGCADataVertex, Direction.OUT, DBCN.E.GCA);

								originalVertexList.addAll(specificGCAAcutalDataList);
							}

							//Update the GCA count, so it will handle the cluster switching logic correctly for each index cluster.
							Util.WMSTMInsertIncrementGCACount(DBCN.index.WMPrecisionRateIndex.cn, txGraph);
							Util.WMSTMInsertIncrementGCACount(DBCN.index.WMTimeRanIndex.cn, txGraph);

							for (Vertex v : originalVertexList) {
								String className = v.getCName();

								//Only support exp and LTM, all the other subsidiary stuff will not be allowed to enter the WM stream.
								if (className.equals(DBCN.V.general.exp.requirement.cn) || className.equals(DBCN.V.general.exp.result.cn)
										|| className.equals(DBCN.V.general.exp.cn)) {
									//All the data we want lies in the expMainGeneral, we need to traverse to their if they are not default expMainGeneral.
									Vertex expMainGeneral = null;

									if (className.equals(DBCN.V.general.exp.requirement.cn)) {
										Vertex expMainData = Util.traverseOnce(v, Direction.OUT, DBCN.E.requirement, DBCN.V.LTM.exp.cn);
										expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
									}
									else if (className.equals(DBCN.V.general.exp.result.cn)) {
										Vertex expMainData = Util.traverseOnce(v, Direction.OUT, DBCN.E.result, DBCN.V.LTM.exp.cn);
										expMainGeneral = Util.traverseOnce(expMainData, Direction.OUT, DBCN.E.data, DBCN.V.general.exp.cn);
									}
									else {
										expMainGeneral = v;
									}

									/*
									 * Traverse to expMainGeneral, then get its occurrences, which will be the size of the occurrence array returned.
									 * Will not get to their parent scale to get its parent's occurrence, that would be imprecise as parent has
									 * many child, and when any child grow, parent grow as well as we create separate exp for each of them down all
									 * the depth for record purposes, this way if you add in parent scale, you will include all other child's
									 * occurrence as well, which will be imprecise. As this child in the future might be a parent as well, thus
									 * mixing with other siblings is not logical.
									 */
									double precisionRate = expMainGeneral.getProperty(LP.precisionRate);

									//Need to add a trailing 'd' to signify it is double, else it will treat it as float and throw error.
									txGraph.directQueryExpectVoid("INSERT INTO INDEX:" +
											Util.WMSTMInsertGetClusterName(DBCN.index.WMPrecisionRateIndex.cn, txGraph) +
											" (key, rid) VALUES (" + precisionRate + "d, " + v.getRid() + ")");
								}
								//LTM type general vertexes. Only ANALOG type is allowed, eg motors, analog sensors. AUDIO IS NOT!
								/*
								 * Rationale:
								 * Analog sensor type can no longer be ICLed, thus they are final.
								 * They have limited pattern type and variation.
								 * Audio signals on the other hand, although is analog as well, can have too many type of variation,
								 * and as it passes through ICL, patterns will be generated, and only those pattern can be recognized,
								 * it means if you plug in raw analog data, its scope will be too big to realize, patterns generated
								 * by the ICL on the other hand, can be recognized more easily and contain more meaning from the past,
								 * which aids in data recognition at larger scale and categorization.
								 */
								else if (Util.equalAny(className, LTM.MOVEMENT)) {
									/*
									 * LTM only have polyVal and timeRan. Unlike exp which have dedicated occurrencePR that represent timeRan.
									 * TimeRan count is special here, it goes through multiple hierarchy and converge all those result together,
									 * this is to ensure that LTM based low depth action gets their deserved timeRan count even if they had never
									 * been executed, but just flashes through mind, without actually executing it, the count will always be
									 * at its sentinel value -1, this function convert it to 0 for better representation.
									 * If they do have timeRan, then return that timeRan plus multiple hierarchy of data' timeRan if available.
									 *
									 * TimeRan is by counting its occurrence, not the amount of exp edges he have, as each operation will create a
									 * new similar data entry, therefore that is the most accurate count in my opinion.
									 * TODO: I might be wrong, maybe exp req res edge counting is the right choice, it is just a matter of choice.
									 */
									long timeRanCount = Util.traverse(v, Direction.IN, DBCN.E.occurrence).size();

									txGraph.directQueryExpectVoid("INSERT INTO INDEX:" +
											Util.WMSTMInsertGetClusterName(DBCN.index.WMTimeRanIndex.cn, txGraph) +
											" (key, rid) VALUES (" + timeRanCount + ", " + v.getRid() + ")");
								}
							}
							//--End of GCA import to WM STM logic.
						}
					});

					StartupSoft.logger.log(logCredential, LVL.INFO, CLA.NORM, "GCAMAIN OK. " + generalVertex);
				}
			}
		}	//isHalt()
		StartupSoft.haltAccepted.set(config.haltIndex, new AtomicBoolean(true));
	}	//run()

	//Extract it from startService in order to log the errors.
	@Override
	public void run() {
		try {
			startService();
		}
		catch(Error | Exception e) {
			StartupSoft.logger.log(logCredential, LVL.FATAL, CLA.EXCEPTION, "", e);
		}
	}
}
