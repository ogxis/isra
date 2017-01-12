package startup;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.output.TeeOutputStream;
import org.opencv.core.Core;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.orientechnologies.orient.client.remote.OServerAdmin;

import actionExecutor.ActionScheduler;
import isradatabase.Graph;
import isradatabase.GraphFactory;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import logger.Logger;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;
import stm.DBCN;
import stm.STMClient;
import storageRegistrar.StorageRegistrar;
import utilities.Util;
import ymlDefine.YmlDefine.DBCredentialConfig;
import ymlDefine.YmlDefine.MANAGEMENT_COMMAND_DEFINE;
import ymlDefine.YmlDefine.ManagementCommand;
import ymlDefine.YmlDefine.StartUpSoftConfig;
import ymlDefine.YmlDefine.WorkerConfig;
import ymlDefine.YmlDefine.WorkerList;;

/**
 * Startup ISRA internal software layer, run one instance for each machine.
 * This will be the manager of all running threads within this node. It can receive and respond to commands.
 * Note: should only run this once per node. More than one will result in heavy context switching overhead.
 * Here is the starting point for every ISRA computing node.
 */
public class StartupSoft {
	//Publicly shared factory instance.
	public static GraphFactory factory;

	private static Credential logCredential;
	//Globally shared logger.
	public static Logger logger;

	//Mock device by supplying virtual hardware address to the system so it don't complain when device is not available.
	//Mainly for debugging purposes, true to Enable.
	public static AtomicBoolean mockDevice;

	//Sleep for 10millisec for every db error, wait for it to become consistent again. And maximum retry count to avoid infinite waiting.
	//Thus 10 retry + 10 millisec sleep = 100ms max delay + 10~20 milli fetch delay.
	public static final long dbErrMaxRetryCount = 50;
	public static final long dbErrRetrySleepTime = 10;

	//GCA Frame timing, 10 ms means 100 frames per sec.
	public static final long milliPerGCAFrame = 10l;

	//Halt will be available for every worker node, so we can halt them individually. Worker count will be reseted once only when starting node.
	//It will be assigned to worker, this data will not be recorded as it changes overtime. Like certain worker being killed while other worker are added,
	//but we will not make the new worker re-take the old worker's count, instead we will give him a new count. workerCount is binded to halt.
	//NOTE: Our workerCount may overflow in the long run, but not really possible as we will restart machine from time to time.
	public static volatile ArrayList<AtomicBoolean> halt;
	public static volatile ArrayList<AtomicBoolean> haltAccepted;
	//Main halt is for this master node, if true will halt all operation on this node at once.
	private static boolean mainHalt;
	private static int workerCount;

	public static final String version = "MinV-0.0.0.0";

	//All setup complete and entered main loop.
	public static AtomicBoolean online;

	/**
	 * Startup ISRA protocol Software layer.
	 * @param args If no argument, will use default file 'config/startupSoftConfig.yml', else will use user provided file.
	 */
	public static int startService(String[] args) {
		System.out.println("ISRA Node Manager");
		halt = new ArrayList<AtomicBoolean>();
		haltAccepted = new ArrayList<AtomicBoolean>();
		mainHalt = false;
		workerCount = 0;
		online = new AtomicBoolean(false);

		YamlReader reader = null;
		StartUpSoftConfig config = null;
		DBCredentialConfig DBLoginCredential = null;

		String defaultPath = "config/startupSoftConfig.yml";

		//Load the config file then load DBCredential data file.
		//If user specify specific file path, we will use that instead and ignore default path.
		try {
			if (args.length > 1) {
				throw new IllegalArgumentException("Too many argument!\n"
						+ "Usage: progName | programName [CustomConfigFile]\nFirst will use default file 'config/startupSoftConfig.yml'");
			}
			else if (args.length == 1) {
				reader = new YamlReader(new FileReader(args[0]));
				config = reader.read(StartUpSoftConfig.class);
				DBLoginCredential = Util.loadDBCredentialFromFile(config.DBCredentialConfigFilePath);
			}
			else {
				reader = new YamlReader(new FileReader(defaultPath));
				config = reader.read(StartUpSoftConfig.class);
				DBLoginCredential = Util.loadDBCredentialFromFile(config.DBCredentialConfigFilePath);
			}
		}
		catch (FileNotFoundException e) {
			throw new IllegalArgumentException("Given config file not found:" + args[0]);
		}
		catch (YamlException e) {
			throw new IllegalArgumentException("Corrupted config file:" + args[0] + "/nOriginal message" + e.getMessage());
		}

		//2 are equivalent. Must run this else it will yield unsatisfied link error for all operation regarding opencv.
		//http://stackoverflow.com/questions/21541324/can-the-package-org-opencv-core-mat-be-used-for-a-simple-java-program
		System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

		//Connect to any server specified in config file.
		//http://stackoverflow.com/questions/35053001/orientdb-cant-get-graph-instance-using-remote-from-java
		System.out.println("Connecting to DB Server...");
		OServerAdmin serverAdmin = null;
		try {
			boolean connectSuccess = false;
			int successIndex = -1;
			for (int i=0; i<DBLoginCredential.dbPath.size(); i++) {
				try {
					serverAdmin = new OServerAdmin(DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(i)).connect(DBLoginCredential.userName.get(i), DBLoginCredential.password.get(i));
					if (serverAdmin.existsDatabase()) {
						connectSuccess = true;
						successIndex = i;
						break;
					}
				}
				catch (Exception ee) {
					System.out.println("Cannot connect to: " + DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(i) + " by username: " + DBLoginCredential.userName.get(i));
				}
			}

			if (!connectSuccess) {
				String serverList = "";
				for (String s : DBLoginCredential.dbPath) {
					serverList += s;
					serverList += " ";
				}
				throw new IllegalStateException("Unable to connect to any specified servers, servers are:" + serverList);
			}

			if (serverAdmin.existsDatabase()) {
				System.out.println("Sucessfully connected to: " + DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(successIndex) + " by username: " +
						DBLoginCredential.userName.get(successIndex));
				StartupSoft.factory = new GraphFactory(DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(successIndex));
				factory.setAutoStartTx(false);
				factory.setupPool(0, 250);
				Graph txGraph = factory.getTx();

				//Initialize all these function once only at here centralized. These are static init and can only be called after you
				//had setup the graph factory pool.
				ActionScheduler.init();
				ICL.ICL.init();

				//Factory is online, set logger to online too so it can start working.
				//In the future you might want to read user specified return address.
				logCredential = new Credential(Thread.currentThread().getName(), config.nodeId, "", "");

				logger = new Logger(100, 100l, DBCN.V.consoleFeedback.cn);
				//Start the logger's service loop.
				Thread thread = new Thread(logger);
				thread.start();
				logger.consoleFeedbackAddr = DBCN.V.consoleFeedback.cn;
				logger.serverReady.set(true);

				//Copy to AtomicBoolean to share by all thread, only initialized once.
				mockDevice = new AtomicBoolean(config.mockDevice);

				if (mockDevice.get())
					logger.log(logCredential, LVL.INFO, CLA.NORM, "Device mocking enabled, can be changed at StartupSoft config file (restart required)");

				//DB connected successfully. Assume db structure is already created.
				//Get node configurations from the database. We use remote database to send and receive command/feedback from this node.
				//Stores worker list on remote db so we can monitor its statistic even if the main node goes down, and do offline task assignment as well.
				//If exception occurs, means this is the first time this node is introduced to the global system network. We will create its credential.
				Vertex configVertex = null;
				boolean firstTimeRun = false;
				//Get the config vertex, if not available, it might mean this is the first time we startup this node OR the config vertex gone missing.
				//http://orient-database.narkive.com/PW8GqNA3/orientdb-getting-records-by-index-with-java-api
				//http://stackoverflow.com/questions/29474506/orientdb-getvertices-not-working-with-java-generated-class-index
				Iterator<Vertex> itr = txGraph.getVerticesItr(DBCN.V.worker.cn, new String[] {LP.nodeUID.toString()}, new Object[] {config.nodeId});
				if (itr.hasNext()) {
					configVertex = itr.next();
					firstTimeRun = false;
					logger.log(logCredential, LVL.INFO, CLA.INTERNAL, "Node:" + config.nodeId + " worker config loaded successfully from DB.");
				}
				else
					firstTimeRun = true;

				//If it the first time we run this thing, the worker entry class may(bad unregistration last time) or may not(first launch) exist in the db.
				if (firstTimeRun) {
					//Register it at the global worker registry.
					txGraph.begin();
					configVertex = txGraph.addVertex(DBCN.V.worker.cn, DBCN.V.worker.cn);
					configVertex.setProperty(LP.nodeUID, config.nodeId);
					configVertex.setProperty(LP.workerList, Util.objectToYml(new WorkerList()) );
					txGraph.commit();
					logger.log(logCredential, LVL.INFO, CLA.NORM, "Registered new node:" + config.nodeId + " into the global node registry.");
				}

				txGraph.begin();
				//Must register now as the console is waiting.
				String commandStorageId = StorageRegistrar.getStorage(config, txGraph);
				configVertex.setProperty(LP.storageId, commandStorageId);
				txGraph.commit();
				logger.log(logCredential, LVL.INFO, CLA.NORM, "Work Storage for command successfully registered. storageId:" + commandStorageId);

				reader = new YamlReader(configVertex.getProperty(LP.workerList).toString());

				//Worker threads store all the currently running threads. Set to NULL if it is terminated.
				ArrayList<Thread> workerThreads = new ArrayList<Thread>();
				//Stores all the configuration of workers that are active, null if removed.
				ArrayList<WorkerConfig> workerRecord = new ArrayList<WorkerConfig>();

				//NOTE: USER ARE REQUIRED TO MAKE SURE THERE IS NO DUPLICATE UID IN WORKERS!!! Use tools to prevent such things from happening.
				//Revive previous workers.
				WorkerList previousWorkerList = reader.read(WorkerList.class);
				for (int i=0; i<previousWorkerList.data.size(); i++) {
					WorkerConfig workerConfig = previousWorkerList.data.get(i);
					workerConfig = reader.read(WorkerConfig.class);
					workerConfig.storageId = StorageRegistrar.getStorage(workerConfig, txGraph);		//Must register a new id again.
					if (workerConfig.isCrawler) {
						workerConfig.haltIndex = workerCount;
						//registration phrase is resposible to start the thread for you.
						workerThreads.add( STMClient.crawlerRegister(workerConfig, txGraph) );
					}
					else if (workerConfig.isSTMWorker) {
						workerConfig.haltIndex = workerCount;
						workerThreads.add( STMClient.STMWorkerRegister(workerConfig, txGraph) );
					}
					else if (workerConfig.isWMWorker) {
						workerConfig.haltIndex = workerCount;
						workerThreads.add( STMClient.WMWorkerRegister(workerConfig, txGraph) );
					}
					else
						throw new IllegalStateException("Impossible error: Unknown work type.");

					workerCount++;
					halt.add(new AtomicBoolean(false));
					haltAccepted.add(new AtomicBoolean(false));
					workerRecord.add(workerConfig);
					logger.log(logCredential, LVL.INFO, CLA.NORM, "Worker:" + workerConfig.uid + " restarted successfully.");
				}

				logger.log(logCredential, LVL.INFO, CLA.NORM, "ISRA Individual Computing Node Manager " + version + " ID:" + config.nodeId + "-"
						+ commandStorageId + "  online. " + Util.epochMilliToReadable(System.currentTimeMillis()) );
				long startTime = System.currentTimeMillis();

				String identifier = config.nodeId + "-" + commandStorageId;
				//Setup the identifier here after the init is done.
				logCredential.identifier = identifier;

				//Infinite service loop that will accumulate and store log files, receive command and provide feedback to Management server.
				ArrayList<String> haltAddr = null;		//Used to make interactive halting progress for each node.
				//Store all the work storage that should be explicitly unregistered after all the worker nodes had been halted as we
				//cannot unregister the storage while the worker is still alive, it might cause data corruption.
				ArrayList<Integer> toBeUnregisteredWorkStorageIndex = new ArrayList<Integer>();
				online.set(true);

				//--NOTE: Each task here manage the begin() and commit() of data on their own.
				while (!mainHalt) {
					try {
						txGraph.shutdown();
					}
					catch (Exception e) {
						throw new IllegalStateException("Txgraph shutdown error cannot be thrown more than once (after the first initial expected fail)"
								+ ", you failed to close the graph appropriately this time.");
					}
					txGraph = factory.getTx();

					//Check have received any command.
					ArrayList<Vertex> commandVertexList = txGraph.getVerticesOfClass(commandStorageId);
					//Externalize the remove method outside of iterator to avoid concurrent modification error.
					ArrayList<Vertex> toBeRemovedVertexList = new ArrayList<Vertex>();

					for (Vertex commandVertex : commandVertexList) {
						String ymlData = commandVertex.getProperty(LP.data);
						reader = new YamlReader(ymlData);
						ManagementCommand command = reader.read(ManagementCommand.class);

						/*
						 * Simple ping command to see if node is alive or not.
						 */
						if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.ping)) {
							logger.log(logCredential, LVL.INFO, CLA.REPLY, "alive");
						}

						/*
						 * Ordered to add new crawler instance, spawn it directly here and updates the main config file to include these data.
						 * Param 0 is the yml data containing the to be added worker's configuration.
						 */
						else if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.addCrawler)
								|| command.command.equals(MANAGEMENT_COMMAND_DEFINE.addSTMWorker)
								|| command.command.equals(MANAGEMENT_COMMAND_DEFINE.addWMWorker)) {
							String workerYml = command.param.get(0);
							reader = new YamlReader(workerYml);
							WorkerConfig newWorker = reader.read(WorkerConfig.class);

							boolean alreadyExist = false;
							//Check whether the specified worker already exist.
							for (WorkerConfig cfg : workerRecord) {
								if (cfg.uid.equals(newWorker.uid)) {
									alreadyExist = true;
									break;
								}
							}

							if (alreadyExist) {
								//Return error  message.
								logger.log(logCredential, LVL.INFO, CLA.REPLY, "Worker:" + newWorker.uid + " already exist.");
								toBeRemovedVertexList.add(commandVertex);
								continue;
							}

							//its uid, isCrawler, isSTMWorker and preference should had been set by user.
							newWorker.storageId = StorageRegistrar.getStorage(newWorker, txGraph);

							//Copied from above.
							try {
								txGraph.begin();
								//Add the halt index first so when the worker thread starts before this function returns, he can access the halt index we
								//set up for him, thus avoiding index out of bound error.
								halt.add(new AtomicBoolean(false));
								haltAccepted.add(new AtomicBoolean(false));
								if (newWorker.isCrawler) {
									newWorker.haltIndex = workerCount;
									workerThreads.add( STMClient.crawlerRegister(newWorker, txGraph) );
								}
								else if (newWorker.isSTMWorker) {
									newWorker.haltIndex = workerCount;
									workerThreads.add( STMClient.STMWorkerRegister(newWorker, txGraph) );
								}
								else if (newWorker.isWMWorker) {
									newWorker.haltIndex = workerCount;
									workerThreads.add( STMClient.WMWorkerRegister(newWorker, txGraph) );
								}
								else  {
									halt.remove(halt.size()-1);
									haltAccepted.remove(haltAccepted.size()-1);
									throw new IllegalStateException("Impossible error: Unknown work type.");
								}
								txGraph.commit();
							}
							catch (IllegalStateException e) {
								//commit() the last transaction first that started in the try block.
								txGraph.commit();

								logger.log(logCredential, LVL.INFO, CLA.REPLY, "At addWorker: " + e.getMessage());
								//Each command vertex will only be used once.
								toBeRemovedVertexList.add(commandVertex);
								halt.remove(halt.size()-1);
								haltAccepted.remove(haltAccepted.size()-1);

								continue;
							}
							workerCount++;
							workerRecord.add(newWorker);

							//Setup the reply message on success.
							String workerType = null;
							if (newWorker.isCrawler)
								workerType = "Crawler";
							else if (newWorker.isSTMWorker)
								workerType = "STMWorker";
							else if (newWorker.isWMWorker)
								workerType = "WMWorker";
							else
								workerType = "Unknown";

							logger.log(logCredential, LVL.INFO, CLA.REPLY, "New worker:" + newWorker.uid + "-"
									+ newWorker.storageId + " at parent:" + newWorker.parentUid + " of type:" + workerType + " setup success.");
						}

						/*
						 * Halt individual worker. Set him to halt, then unregister him. If he is running an operation, that operation is disposed and migrated.
						 * Param 0 is the UID of the worker.
						 */
						else if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.haltCrawler)
								|| command.command.equals(MANAGEMENT_COMMAND_DEFINE.haltSTMWorker)
								|| command.command.equals(MANAGEMENT_COMMAND_DEFINE.haltWMWorker)) {
							//Get the corresponding worker record from our local record of deployed worker.
							//For user they only need to provide the UID of the worker as param.
							//Then we will fetch the halt index, worker config and other thing locally which had been setup during
							//addWorker or startup.
							WorkerConfig toBeHaltedWorker = null;
							for (int i=0; i<workerRecord.size(); i++) {
								WorkerConfig w = workerRecord.get(i);
								if ( w.uid.equals(command.param.get(0)) ) {
									toBeHaltedWorker = w;
								}
							}

							//If failed to find the corresponding worker in this computing node. Return a error log.
							if (toBeHaltedWorker == null) {
								logger.log(logCredential, LVL.INFO, CLA.REPLY, "Requested operation halt on node:"
										+ command.param.get(0) + " failed." + " Node doesn't exist.");
							}

							else {
								//Set flag to call the worker to halt, then wait for his response. haltAccepted will be set. That is the response.
								int toBeHaltedIndex = toBeHaltedWorker.haltIndex;
								halt.set(toBeHaltedIndex, new AtomicBoolean(true));

								long timeout = System.currentTimeMillis() + 10000;
								while (timeout > System.currentTimeMillis() && haltAccepted.get(toBeHaltedIndex).get() == false) {
									;	//do nothing and wait.
								}

								//If it is timeout, but not normally halted, we will post a warning, and unregister him anyway.
								//Terminate abnormal means treat him as non-existence, as he might had gone into some sort of infinite loop.
								if (!haltAccepted.get(toBeHaltedIndex).get()) {
									logger.log(logCredential, LVL.INFO, CLA.REPLY, "Requested operation halt on node:"
											+ command.param.get(0) + " warning:" + " Timeout reached no response received, will continue unregister him.");
								}

								//Unregister his work storage.
								StorageRegistrar.unregister(toBeHaltedWorker.storageId, txGraph);

								//Worker is assumed to be halted as he has raised his flag. So we move on to unregister him from the workers network.
								//All of his remaining task will be migrated and any uncommitted progress will be disposed.
								String commandType = null;
								txGraph.begin();
								if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.haltCrawler)) {
									STMClient.crawlerUnregister(toBeHaltedWorker, txGraph);
									commandType = "haltCrawler";
								}

								else if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.haltSTMWorker)) {
									STMClient.STMWorkerUnregister(toBeHaltedWorker, txGraph);
									commandType = "haltSTMWorker";
								}

								else if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.haltWMWorker)) {
									STMClient.WMWorkerUnregister(toBeHaltedWorker, txGraph);
									commandType = "haltWMWorker";
								}

								logger.log(logCredential, LVL.INFO, CLA.REPLY, "Worker:" + toBeHaltedWorker.uid + " at parent:"
								+ config.nodeId + " of type:" + commandType + " halt success.");
							}
						}

						/*
						 * Reset its preference to allow him to process other data. Un-subscribe him from job center, and re-subscribe.
						 * Its currently running task progress will be disposed.
						 * param 0 is worker's uid.
						 * param 1 is the new configuration.
						 */
						else if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.setWorker)) {
							//Get the corresponding worker record from our local record of deployed worker.
							WorkerConfig toBeResetedWorker = null;
							for (int i=0; i<workerRecord.size(); i++) {
								WorkerConfig w = workerRecord.get(i);
								if ( w.storageId.equals(command.param.get(0)) ) {
									toBeResetedWorker = w;
								}
							}

							//If failed to find the corresponding worker in this computing node. Return a error log.
							if (toBeResetedWorker == null) {
								logger.log(logCredential, LVL.INFO, CLA.REPLY, "Requested operation set on node:" + command.param.get(0)
								+ " failed. Node doesn't exist.");
							}

							//We unregister and re-register them, this will reset their subscription target thus completing preference update.
							//Its currently running task will be disposed.
							else {
								//Setup the new worker's configuration and replace the old one.
								//NOTE: User can change the worker type from crawler to stm worker and vice versa. UID will remain the same.
								String workerYml = command.param.get(1);
								reader = new YamlReader(workerYml);
								WorkerConfig newWorker = reader.read(WorkerConfig.class);
								newWorker.haltIndex = toBeResetedWorker.haltIndex;

								//Unregister first.
								txGraph.begin();
								if (toBeResetedWorker.isCrawler) {
									STMClient.crawlerUnregister(toBeResetedWorker, txGraph);
								}
								else if (toBeResetedWorker.isSTMWorker) {
									STMClient.STMWorkerUnregister(toBeResetedWorker, txGraph);
								}
								else if (toBeResetedWorker.isWMWorker) {
									STMClient.WMWorkerUnregister(toBeResetedWorker, txGraph);
								}
								else
									throw new IllegalStateException("Impossible error: Unknown work type.");

								//Re-register with new setting.
								Thread updatedThread = null;
								if (newWorker.isCrawler) {
									updatedThread = STMClient.crawlerRegister(newWorker, txGraph);
								}
								else if (newWorker.isSTMWorker) {
									updatedThread = STMClient.STMWorkerRegister(newWorker, txGraph);
								}
								else if (newWorker.isWMWorker) {
									updatedThread = STMClient.WMWorkerRegister(newWorker, txGraph);
								}
								else
									throw new IllegalStateException("Impossible error: Unknown work type.");

								txGraph.commit();

								//Replace the old thread with the new thread.
								workerThreads.set(toBeResetedWorker.haltIndex, updatedThread);

								//Update worker record to reflect the new configuration.
								workerRecord.set(toBeResetedWorker.haltIndex, newWorker);

								logger.log(logCredential, LVL.INFO, CLA.REPLY, "Worker node:" + newWorker.storageId + " setting updated.");
							}
						}

						//Halt all the child node and this startupSoft thread itself, a full halt.
						else if (command.command.equals(MANAGEMENT_COMMAND_DEFINE.halt)) {
							mainHalt = true;
							online.set(false);

							//Store all the worker storage to be unregistered later.
							for (int i=0; i<halt.size(); i++) {
								//If the worker is not halted at the moment we check it, means he is still alive, thus his storage should
								//be eligible to be recycled later.
								if (!halt.get(i).get())
									toBeUnregisteredWorkStorageIndex.add(i);
							}

							for (AtomicBoolean nodeHalt : halt) {
								nodeHalt.set(true);
							}

							logger.log(logCredential, LVL.INFO, CLA.REPLY, "Halting in progress...");

							haltAddr = command.returnAddr;
						}

						/*
						 * Unknown command, return it back and log it.
						 */
						else {
							logger.log(logCredential, LVL.INFO, CLA.REPLY, "Unknown command:" + command.command);
						}

						//If things end normally, then the command vertex will be disposed at here, else it should be explicitly disposed at the site of error.
						toBeRemovedVertexList.add(commandVertex);
					}

					//If there is no vertex to be removed, sleep.
					if (!toBeRemovedVertexList.isEmpty()) {
						txGraph.begin();
						for (int i=0; i<toBeRemovedVertexList.size(); i++) {
							toBeRemovedVertexList.get(i).remove();
						}
						txGraph.commit();
					}
					else
						Util.sleep(250l);
				}

				//Halt in progress, keep flushing progress to Management layer. Eventually all nodes will be halted.
				int remainingNode = -1;
				long timeout = System.currentTimeMillis() + config.shutdownTimeoutMilli;
				boolean isTimeoutIgnore = false;
				if (config.shutdownTimeoutEnable)
					System.out.println("Shutdown timeout ACTIVE, timeout is (milli): " + config.shutdownTimeoutMilli);
				else
				System.out.println("Shutdown timeout DISABLED, will wait indefinitely until all worker threads report halt success");
				do {
					String aliveNodeList = "";
					int currentRemainingNode = 0;
					for (int i=0; i<workerThreads.size(); i++) {
						Thread t = workerThreads.get(i);
						if (t.isAlive()) {
							currentRemainingNode++;
							aliveNodeList += workerRecord.get(i).uid;
							aliveNodeList += "[";
							aliveNodeList += workerRecord.get(i).preference.get(0);
							aliveNodeList += "]";
							//If it is not the last node, we will append a comma.
							if (workerThreads.size() - i != 1)
								aliveNodeList += ", ";
						}
					}

					//If it is the first time OR remaining node has decreased, we will prompt to user about our progress.
					if (remainingNode == -1 || currentRemainingNode < remainingNode) {
						try {
							txGraph.shutdown();
						}
						catch (Exception e) {
							e.printStackTrace();
						}
						txGraph = factory.getTx();
						logger.log(logCredential, LVL.INFO, CLA.REPLY, "Remaining thread to halt: " + currentRemainingNode
								+ " ; They are:" + aliveNodeList);

						remainingNode = currentRemainingNode;
					}

					//Only check timeout if user enabled it.
					if (config.shutdownTimeoutEnable) {
						if (System.currentTimeMillis() > timeout) {
							logger.log(logCredential, LVL.INFO, CLA.REPLY, "Timeout reached, will ignore remaining thread.");
							isTimeoutIgnore = true;
							break;
						}
					}
				}
				while (remainingNode != 0);
				//http://stackoverflow.com/questions/5175728/how-to-get-the-current-date-time-in-java
				try {
					txGraph.shutdown();
				}
				catch (Exception e) {
					e.printStackTrace();
				}
				txGraph = factory.getTx();
				//Unregister the command storage.
				StorageRegistrar.unregister(commandStorageId, txGraph);

				//Unregister all the sub workers' storages.
				for (Integer index : toBeUnregisteredWorkStorageIndex) {
					//Get the corresponding worker record from our local record of deployed worker.
					//As we already have the index of the worker, we can fetch it directly and unregister the worker. The worker current state should be already halted.
					WorkerConfig haltedWorker = workerRecord.get(index);
					StorageRegistrar.unregister(haltedWorker.storageId, txGraph);

					txGraph.begin();
					if (haltedWorker.isCrawler)
						STMClient.crawlerUnregister(haltedWorker, txGraph);
					else if (haltedWorker.isSTMWorker)
						STMClient.STMWorkerUnregister(haltedWorker, txGraph);
					else if (haltedWorker.isWMWorker)
						STMClient.WMWorkerUnregister(haltedWorker, txGraph);
					txGraph.commit();
				}

				//Unregister all the worker preference (registered task).


				txGraph.begin();
				//Set its storageId to invalid to indicate he is down.
				configVertex.setProperty(LP.storageId, "DOWN");

				//Reply to all the subscribed listener including the initiating console.
				if (!isTimeoutIgnore) {
					logger.log(logCredential, LVL.INFO, CLA.REPLY, "Node halted successfully. Time:"
							+ Util.epochMilliToReadable(System.currentTimeMillis())
							+ ", Duration in milli: " + (System.currentTimeMillis() - startTime) );
				}
				else {
					logger.log(logCredential, LVL.INFO, CLA.REPLY, "TIMEOUT REACHED, ignored remaining thread. Node halted successfully. Time:"
							+ Util.epochMilliToReadable(System.currentTimeMillis())
							+ ", Duration in milli: " + (System.currentTimeMillis() - startTime) );
				}
				txGraph.commit();
				txGraph.shutdown();

				//Set logger to halt and wait for him to flush complete before closing the DB factory connection.
				logger.halt.set(true);
				while (!logger.haltFlushComplete.get())
					;

				factory.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return 0;
	}

	public static void main(String [] args) {
		System.out.println("ISRA Node Manager");
		try {
			String logFileName = "log/startupSoft/" + System.currentTimeMillis();
			System.out.println("All output will be logged to file: " + logFileName);

			//Set both the stdout and stderr stream to file and console screen.
			//http://stackoverflow.com/questions/16237546/writing-to-console-and-text-file
		    FileOutputStream fos = new FileOutputStream(logFileName);
		    TeeOutputStream stdout = new TeeOutputStream(System.out, fos);
		    TeeOutputStream stderr = new TeeOutputStream(System.err, fos);
		    PrintStream outStream = new PrintStream(stdout);
		    PrintStream errStream = new PrintStream(stderr);
		    System.setOut(outStream);
		    System.setErr(errStream);
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		}
		try {
			startService(args);
		}
		catch (Error | Exception e) {
			try {
				logger.log(logCredential, LVL.FATAL, CLA.EXCEPTION, "", e);
				logger.halt.set(true);
				throw e;
			}
			catch (Exception innerE) {
				System.out.println("Logger error, logger exception:");
				innerE.printStackTrace();
				System.out.println("Original exception:");
				e.printStackTrace();
			}
		}
		logger.halt.set(true);
	}
}
