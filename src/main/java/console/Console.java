package console;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.orientechnologies.orient.client.remote.OServerAdmin;

import crawler.CRAWLERTASK;
import crawler.CRAWLER_TASK_ASSIGNMENT;
import isradatabase.Graph;
import isradatabase.GraphFactory;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import stm.STMTASK;
import storageRegistrar.StorageRegistrar;
import utilities.Util;
import wm.WMTASK;
import ymlDefine.YmlDefine.ConsoleConfig;
import ymlDefine.YmlDefine.DBCredentialConfig;
import ymlDefine.YmlDefine.ExternalIOConfig;
import ymlDefine.YmlDefine.MANAGEMENT_COMMAND_DEFINE;
import ymlDefine.YmlDefine.ManagementCommand;
import ymlDefine.YmlDefine.WorkerConfig;

/**
 * A CLI console unit to control and manage ISRA computing clusters on local and external machines.
 */
public class Console implements Runnable {
	public static final String consoleVersion = "ISRA Server Based Async Console For ISRA-MINIMAL-V0.0.0.0";
	public static final String consoleInitial = "#:";

	private AtomicBoolean online = new AtomicBoolean(false);
	public boolean isOnline() {
		return online.get();
	}
	private AtomicBoolean halt = new AtomicBoolean(false);
	public void halt() {
		halt.set(true);
	}
	//Command line argument, and whether it is embedded or not (embeddable by GUI console), if embedded its IO will be handled by the host.
	private String[] programArgument;
	private boolean embedded = false;
	//Only used if it is embedded, to simulate input output stream, log listener output not included, only the default built in output of
	//console is included.
	//Input, blocks if command is sending or processing and only release when it is done.
	private final ReentrantLock inputLock = new ReentrantLock();
	private String inputCommand = "";
	private AtomicBoolean inputCommandAvailable = new AtomicBoolean(false);
	private AtomicBoolean commandProcessed = new AtomicBoolean(false);
	//Store concurrent command output in queue for simple re-output to embedding GUI console.
	private ConcurrentLinkedQueue<String> commandOutput = new ConcurrentLinkedQueue<String>();
	public LogListener logListener = null;

	//The console initial for embedding host to use.
	private String embeddingHostConsoleInitial = consoleInitial;
	private final ReentrantLock consoleInitialLock = new ReentrantLock();

	/**
	 * Used during embedded mode, to send command to the internal console logic.
	 * @param command
	 */
	public void sendCommand(String command) {
		//Make sure only 1 command be sending/processing at any time.
		inputLock.lock();
		if (commandProcessed.get() == true)
			throw new IllegalStateException("commandProcessed cannot be true. Must be some code accidentally modified it somewhere.");

		inputCommand = command;
		inputCommandAvailable.set(true);
		commandProcessed.set(false);

		//Block until the command has done processing, noticeable as it will set the flag to true.
		while (!commandProcessed.get() && !halt.get() && online.get()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		//Reset it to false, so we can know if it is true at the beginning of this clause, someplace has accidentally modified it.
		commandProcessed.set(false);
		inputCommandAvailable.set(false);

		inputLock.unlock();
	}

	/**
	 * Used during embedded mode, to receive output from the internal console logic. Keep call this to make yourself up to date.
	 * @return Null if no more output available, else just return the output string.
	 */
	public String getOutput() {
		return commandOutput.poll();
	}

	/**
	 * Get the latest generated console initial to the host.
	 * @return
	 */
	public String getHostConsoleInitial() {
		consoleInitialLock.lock();
		String result = embeddingHostConsoleInitial;
		consoleInitialLock.unlock();
		return result;
	}

	/**
	 * Set the latest console initial by the internal console logic.
	 * @param currentConsoleInitial
	 */
	private void setHostConsoleInitial(String currentConsoleInitial) {
		consoleInitialLock.lock();
		embeddingHostConsoleInitial = currentConsoleInitial;
		consoleInitialLock.unlock();
	}

	/**
	 * Wrapper for System out and embedded host out. It will determine where to output it here.
	 */
	private void outputTo(String outputString) {
		if (embedded)
			commandOutput.offer(outputString);
		else
			System.out.println(outputString);
	}

	public Console(String[] args, boolean embedded) {
		this.programArgument = args;
		this.embedded = embedded;
	}

	//Make it runnable to allow it to be called by external wrapper class to extend it, eg GUI console.
	public void run() {
		System.out.println("ISRA Async Console");

		//A list of goodbye messages, will randomly pick 1 when user exits.
		ArrayList<String> goodByeMsg = new ArrayList<String>();
		goodByeMsg.add("再会。");
		goodByeMsg.add("Good Bye.");
		goodByeMsg.add("Hastala vista babe.");
		goodByeMsg.add("Sayonara.");
		goodByeMsg.add("I will be back.");
		goodByeMsg.add("Bon voyage");

		//Load the config file then load DBCredential data file.
		//If user specify specific file path, we will use that instead and ignore default path.
		YamlReader reader = null;
		ConsoleConfig config = null;
		DBCredentialConfig DBLoginCredential = null;

		try {
			if (programArgument.length != 1) {
				throw new IllegalArgumentException("Usage: ProgName configFile.yml");
			}
			else if (programArgument.length == 1) {
				reader = new YamlReader(new FileReader(programArgument[0]));
				config = reader.read(ConsoleConfig.class);
				DBLoginCredential = Util.loadDBCredentialFromFile(config.DBCredentialConfigFilePath);
			}
		}
		catch (FileNotFoundException e) {
			throw new IllegalArgumentException("Given config file not found:" + programArgument[0]);
		}
		catch (YamlException e) {
			throw new IllegalArgumentException("Corrupted config file:" + programArgument[0] + "/nOriginal message" + e.getMessage());
		}

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
					outputTo("Cannot connect to: " + DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(i) + " by username: " + DBLoginCredential.userName.get(i));
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
				outputTo("Sucessfully connected to: " + DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(successIndex) + " by username: " +
						DBLoginCredential.userName.get(successIndex));
				GraphFactory factory = new GraphFactory(DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(successIndex));
				factory.setAutoStartTx(false);
				Graph txGraph = factory.getTx();

				outputTo("Version:" + consoleVersion);
				outputTo("Type 'HELP' for more usage info.");

				//Target node is the target machine running ISRA.
				String targetNodeUid = config.defaultTargetNodeId;

				//Check whether the default node exist and is online by querying the registration record in db.
				outputTo("Fetching available nodes and check if default target node is valid...");
				String targetNodeCommandStorageId = "";
				ArrayList<String> availableTargetNodeList = new ArrayList<String>();
				ArrayList<String> availableTargetNodeStorageList = new ArrayList<String>();
				boolean foundRegistration = false;
				try {
					ArrayList<Vertex> allRegisteredNode = txGraph.getVerticesOfClass(DBCN.V.worker.cn);
					for (Vertex registry : allRegisteredNode) {
						String nodeId = registry.getProperty(LP.nodeUID);
						if (nodeId.equals(targetNodeUid)) {
							foundRegistration = true;
							//At the moment if he is online, he should have posted the latest, working and registered command storage id, guaranteed.
							//TODO: Add some checking here in the future to make sure the node is alive as bad shutdown will not update this data.
							targetNodeCommandStorageId = registry.getProperty(LP.storageId);
						}

						availableTargetNodeStorageList.add((String) registry.getProperty(LP.storageId));
						availableTargetNodeList.add(nodeId);
					}
				}
				//If the global record is empty, of course it cannot found the registry he made.
				catch (Exception e) {
					foundRegistration = false;
				}

				if (!foundRegistration) {
					outputTo("Cannot find default target node:" + targetNodeUid + ". Please select a valid node from list.");
					//Set it to empty so it will be treated as not connected.
					targetNodeUid = "";
				}

				//Execute bulk command from file, if true, it will skip asking user for input until all the bulk command succeed.
				boolean executingBulk = false;
				boolean executeBulkContinueOnFailure = false;
				boolean commandErr = false;
				long executeBulkCommandTimeSpacing = 0;
				int executeBulkCurrentIndex = 0;
				ArrayList<String> bulkCommandList = new ArrayList<String>();

				//Start the helper thread log reader and bind them to our stdout by starting their thread under us.
				//This will fetch outputs from external ISRA worker processes.
				logListener = new LogListener(DBCN.V.consoleFeedback.cn, factory, embedded);
				Thread thread = new Thread(logListener);
				thread.start();

				//Store ALL command happened during this session.
				ArrayList<String> history = new ArrayList<String>();

				//Only connect to stdin if not in embedded mode. For embedded mode input will be fed in via the GUI console.
				Scanner scanner = null;
				if (!embedded)
					scanner = new Scanner(System.in);

				online.set(true);

				while (!halt.get()) {
					try {
						txGraph.shutdown();
					}
					catch (Exception e) {
						e.printStackTrace();
					}
					txGraph = factory.getTx();

					Scanner tokenizer = null;

					if (executingBulk && executeBulkCurrentIndex == bulkCommandList.size()) {
						executingBulk = false;
						outputTo("Bulk commands successfully executed.");

						if (embedded)
							commandProcessed.set(true);
						//1 of 3 breakpoint. Treat as completed if bulk command success in order to free the host console blocking wait.
						continue;
					}

					//Check whether we had encountered error during executing bulk, if so, see either we should stop or continue.
					if (executingBulk && commandErr) {
						commandErr = false;

						if (!executeBulkContinueOnFailure) {
							executingBulk = false;

							if (embedded)
								commandProcessed.set(true);
							//2 of 3 breakpoint. Treat as completed if bulk command fail in order to free the host console blocking wait.
						}
						//-1 as we want to report last command's error, we increment command after we completed a command.
						outputTo("During execute bulk: Encountered error at command:" + bulkCommandList.get(executeBulkCurrentIndex - 1));
						//After continue the error flag will be reset thus if wanted to continue on after failure it will.
						continue;
					}

					//If in normal mode, we will print out initial and fetch user input as command.
					if (!executingBulk) {
						//3 of 3 breakpoint. Treat as completed if it is the beginning of new command cycle in order to free the host console
						//blocking wait. It must also check for inputCommandAvailable to be true as if it is the first time it run a command,
						//the system will be expecting the inputCommandAvailable to be false and wait for the command to come in, so we should
						//not treat command as processed as no command had came in at that moment of time.
						if (embedded && inputCommandAvailable.get())
							commandProcessed.set(true);

						//If they are empty string or down, set them with proper value and prohibit user from executing task that requires a target node.
						String targetNodeCommandStorageIdPreprocess = targetNodeCommandStorageId == null || targetNodeCommandStorageId.equals("")
								? "DOWN" : targetNodeCommandStorageId;
						String targetNodeUidPreprocess = targetNodeUid.equals("") ? "NULL" : targetNodeUid;
						String finalConsoleIntial = targetNodeUidPreprocess + "-" + targetNodeCommandStorageIdPreprocess + consoleInitial;

						//Set the latest console initial for the host to use.
						//This is the only direct output, as it make no sense to print the console initial twice if we use outputTo() for
						//printing this at host site. And we also have to make sure that we did print the console initial if the user
						//is in CLI mode (not embedded).
						if (embedded)
							setHostConsoleInitial(finalConsoleIntial);
						else
							System.out.print(finalConsoleIntial);

						String userInput = null;
						//Only accept command from scanner if not in embedded mode (the default non GUI mode). Else wait for command from the
						//embedding host.
						if (!embedded)
							userInput = scanner.nextLine();
						else {
							//Block until command from embedding host is available. Must check halt as embedding host may had killed us
							//before any command arrive. Also wait if command is available but already processed.
							while (( !inputCommandAvailable.get() || (inputCommandAvailable.get() && commandProcessed.get()) ) && !halt.get()) {
								try {
									Thread.sleep(100);
								} catch (InterruptedException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
							}
							userInput = inputCommand;
						}

						tokenizer = new Scanner(userInput);

						//Filter out user's spamming enter key from entering the history record.
						if (!userInput.equals(""))
							history.add(userInput);
					}

					//If in execute bulk mode, we will keep on execute until we ran out of commands.
					else {
						//Reset those parameter as it is a new iteration already.
						commandErr = false;
						tokenizer = new Scanner(bulkCommandList.get(executeBulkCurrentIndex));
						//Increment to next command only after current command is completed.
						executeBulkCurrentIndex += 1;

						//Sleep for the specified amount of spacing time for each command.
						try {
							Thread.sleep(executeBulkCommandTimeSpacing);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}

					ArrayList<String> tokens = new ArrayList<String>();

					while (tokenizer.hasNext())
						tokens.add(tokenizer.next());
					tokenizer.close();

					if (tokens.isEmpty())
						continue;

					String command = tokens.get(0).toLowerCase();

					//Halt the console only.
					if ( (command.equals("halt") || command.equals("h")) && tokens.size() == 1) {
						//http://stackoverflow.com/questions/5034370/retrieving-a-random-item-from-arraylist
						//Print a random goodbye message :)
						String selectedMsg = goodByeMsg.get(new Random().nextInt(goodByeMsg.size()));
						outputTo(selectedMsg);

						break;
					}

					/*
					 * NOTE: We will set the commandErr to true if error occurs, all command are required to implement this.
					 * This is to allow execute bulk to know that error had occurred.
					 * This only applies to expected error, for unexpected error it will just crash.
					 */

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.addCrawler.toLowerCase()) || command.equals("ac") ) {
						if (tokens.size() < 2) {
							outputTo("Usage: addCrawler newCrawlerUid crawlerTask....");
							commandErr = true;
							continue;
						}
						//Must have a target node to send command to.
						if (targetNodeCommandStorageId.equals("") || targetNodeCommandStorageId.equals("DOWN")) {
							commandErr = true;
							outputTo("No targetNode selected. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}

						//Validate to be assigned tasks.
						boolean taskExist = true;
						ArrayList<String> workerPreference = new ArrayList<String>();
						for (int i=2; i<tokens.size(); i++) {
							boolean validTask = false;
							for (int j=0; j<CRAWLERTASK.arrayForm.length; j++) {
								if (CRAWLERTASK.arrayForm[j].toLowerCase().equals(tokens.get(i).toLowerCase())) {
									workerPreference.add(CRAWLERTASK.arrayForm[j]);
									validTask = true;
								}
							}
							if (!validTask) {
								outputTo("Requested task:" + tokens.get(i) + " is not valid.");
								taskExist = false;
								break;
							}
						}
						if (!taskExist) {
							commandErr = true;
							continue;
						}

			    		txGraph.begin();
			    		Vertex addCrawler = txGraph.addVertex(targetNodeCommandStorageId, targetNodeCommandStorageId);
			    		ManagementCommand addCrawlerCommand = new ManagementCommand();
			    		addCrawlerCommand.command = MANAGEMENT_COMMAND_DEFINE.addCrawler;
			    		//Can be set to any db class, the output will be redirected to there.
			    		addCrawlerCommand.returnAddr.add(DBCN.V.devnull.cn);

						WorkerConfig addCrawlerConfig = new WorkerConfig();
						addCrawlerConfig.isCrawler = true;
						addCrawlerConfig.uid = targetNodeUid + tokens.get(1);
						addCrawlerConfig.parentUid = targetNodeUid;
						addCrawlerConfig.port = config.WMRequestListenerPort;

						for (int i=0; i<workerPreference.size(); i++) {
							addCrawlerConfig.preference.add(workerPreference.get(i));
						}
						addCrawlerCommand.param.add(Util.objectToYml(addCrawlerConfig));

						addCrawler.setProperty(LP.data, Util.objectToYml(addCrawlerCommand));
			    		txGraph.commit();
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.addSTMWorker.toLowerCase()) || command.equals("asw")) {
						if (tokens.size() < 2) {
							outputTo("Usage: addSTMWorker newSTMWorkerUid STMTask....");
							commandErr = true;
							continue;
						}
						//Must have a target node to send command to.
						if (targetNodeCommandStorageId.equals("") || targetNodeCommandStorageId.equals("DOWN")) {
							commandErr = true;
							outputTo("No targetNode selected. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}

						//validate to be assigned tasks.
						boolean taskExist = true;
						ArrayList<String> workerPreference = new ArrayList<String>();
						for (int i=2; i<tokens.size(); i++) {
							boolean validTask = false;
							for (int j=0; j<STMTASK.arrayForm.length; j++) {
								if (STMTASK.arrayForm[j].toLowerCase().equals(tokens.get(i).toLowerCase())) {
									validTask = true;
									workerPreference.add(STMTASK.arrayForm[j]);
								}
							}
							if (!validTask) {
								outputTo("Requested task:" + tokens.get(i) + " is not valid.");
								taskExist = false;
								break;
							}
						}
						if (!taskExist) {
							commandErr = true;
							continue;
						}

			    		txGraph.begin();
			    		Vertex addSTMWorker = txGraph.addVertex(targetNodeCommandStorageId, targetNodeCommandStorageId);
			    		ManagementCommand addSTMWorkerCommand = new ManagementCommand();
			    		addSTMWorkerCommand.command = MANAGEMENT_COMMAND_DEFINE.addSTMWorker;
			    		//Can be set to any db class, the output will be redirected to there.
			    		addSTMWorkerCommand.returnAddr.add(DBCN.V.devnull.cn);

						WorkerConfig addSTMWorkerConfig = new WorkerConfig();
						addSTMWorkerConfig.isSTMWorker = true;
						addSTMWorkerConfig.uid = targetNodeUid + tokens.get(1);
						addSTMWorkerConfig.parentUid = targetNodeUid;

						for (int i=0; i<workerPreference.size(); i++) {
							addSTMWorkerConfig.preference.add(workerPreference.get(i));
						}
						addSTMWorkerCommand.param.add(Util.objectToYml(addSTMWorkerConfig));

						addSTMWorker.setProperty(LP.data, Util.objectToYml(addSTMWorkerCommand));
			    		txGraph.commit();
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.addWMWorker.toLowerCase()) || command.equals("aww")) {
						if (tokens.size() < 2) {
							outputTo("Usage: addWMWorker newWMWorkerUid WMTask....");
							commandErr = true;
							continue;
						}
						//Must have a target node to send command to.
						if (targetNodeCommandStorageId.equals("") || targetNodeCommandStorageId.equals("DOWN")) {
							commandErr = true;
							outputTo("No targetNode selected. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}

						//validate to be assigned tasks.
						boolean taskExist = true;
						ArrayList<String> workerPreference = new ArrayList<String>();
						for (int i=2; i<tokens.size(); i++) {
							boolean validTask = false;
							for (int j=0; j<WMTASK.arrayForm.length; j++) {
								if (WMTASK.arrayForm[j].toLowerCase().equals(tokens.get(i).toLowerCase())) {
									workerPreference.add(WMTASK.arrayForm[j]);
									validTask = true;
								}
							}
							if (!validTask) {
								outputTo("Requested task:" + tokens.get(i) + " is not valid.");
								taskExist = false;
								break;
							}
						}
						if (!taskExist) {
							commandErr = true;
							continue;
						}

			    		txGraph.begin();
			    		Vertex addWMWorker = txGraph.addVertex(targetNodeCommandStorageId, targetNodeCommandStorageId);
			    		ManagementCommand WMWorkerCommand = new ManagementCommand();
			    		WMWorkerCommand.command = MANAGEMENT_COMMAND_DEFINE.addWMWorker;
			    		//Can be set to any db class, the output will be redirected to there.
			    		WMWorkerCommand.returnAddr.add(DBCN.V.devnull.cn);

						WorkerConfig addWMWorkerConfig = new WorkerConfig();
						addWMWorkerConfig.isWMWorker = true;
						addWMWorkerConfig.uid = targetNodeUid + tokens.get(1);
						addWMWorkerConfig.parentUid = targetNodeUid;
						addWMWorkerConfig.hostname = "localhost";
						addWMWorkerConfig.port = config.WMRequestListenerPort;


						for (int i=0; i<workerPreference.size(); i++) {
							addWMWorkerConfig.preference.add(workerPreference.get(i));
						}
						WMWorkerCommand.param.add(Util.objectToYml(addWMWorkerConfig));

						addWMWorker.setProperty(LP.data, Util.objectToYml(WMWorkerCommand));
			    		txGraph.commit();
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.haltCrawler.toLowerCase()) || command.equals("hc")) {
						if (tokens.size() < 2) {
							outputTo("Usage: haltCrawler crawlerUid");
							commandErr = true;
							continue;
						}
						//Must have a target node to send command to.
						if (targetNodeCommandStorageId.equals("") || targetNodeCommandStorageId.equals("DOWN")) {
							commandErr = true;
							outputTo("No targetNode selected. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}

			    		txGraph.begin();
			    		Vertex haltCrawler = txGraph.addVertex(targetNodeCommandStorageId, targetNodeCommandStorageId);
			    		ManagementCommand haltCrawlerCommand = new ManagementCommand();
			    		haltCrawlerCommand.command = MANAGEMENT_COMMAND_DEFINE.haltCrawler;
						haltCrawlerCommand.param.add(targetNodeUid + tokens.get(1));
			    		//Can be set to any db class, the output will be redirected to there.
			    		haltCrawlerCommand.returnAddr.add(DBCN.V.devnull.cn);

						haltCrawler.setProperty(LP.data, Util.objectToYml(haltCrawlerCommand));
			    		txGraph.commit();
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.haltSTMWorker.toLowerCase()) || command.equals("hsw")) {
						if (tokens.size() < 2) {
							outputTo("Usage: haltSTMWorker STMWorkerUid");
							commandErr = true;
							continue;
						}
						//Must have a target node to send command to.
						if (targetNodeCommandStorageId.equals("") || targetNodeCommandStorageId.equals("DOWN")) {
							commandErr = true;
							outputTo("No targetNode selected. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}

			    		txGraph.begin();
			    		Vertex haltSTMWorker = txGraph.addVertex(targetNodeCommandStorageId, targetNodeCommandStorageId);
			    		ManagementCommand haltSTMWorkerCommand = new ManagementCommand();
			    		haltSTMWorkerCommand.command = MANAGEMENT_COMMAND_DEFINE.haltSTMWorker;
						haltSTMWorkerCommand.param.add(targetNodeUid + tokens.get(1));
			    		//Can be set to any db class, the output will be redirected to there.
			    		haltSTMWorkerCommand.returnAddr.add(DBCN.V.devnull.cn);

						haltSTMWorker.setProperty(LP.data, Util.objectToYml(haltSTMWorkerCommand));
			    		txGraph.commit();
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.haltWMWorker.toLowerCase()) || command.equals("hww")) {
						if (tokens.size() < 2) {
							outputTo("Usage: haltWMWorker WMWorkerUid");
							commandErr = true;
							continue;
						}
						//Must have a target node to send command to.
						if (targetNodeCommandStorageId.equals("") || targetNodeCommandStorageId.equals("DOWN")) {
							commandErr = true;
							outputTo("No targetNode selected. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}

			    		txGraph.begin();
			    		Vertex haltWMWorker = txGraph.addVertex(targetNodeCommandStorageId, targetNodeCommandStorageId);
			    		ManagementCommand WMWorkerCommand = new ManagementCommand();
			    		WMWorkerCommand.command = MANAGEMENT_COMMAND_DEFINE.haltWMWorker;
						WMWorkerCommand.param.add(targetNodeUid + tokens.get(1));
			    		//Can be set to any db class, the output will be redirected to there.
			    		WMWorkerCommand.returnAddr.add(DBCN.V.devnull.cn);

						haltWMWorker.setProperty(LP.data, Util.objectToYml(WMWorkerCommand));
			    		txGraph.commit();
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.forceRemoveCrawler.toLowerCase()) || command.equals("frc")) {
						if (tokens.size() < 2) {
							outputTo("Usage: forceRemoveCrawler creatorNodeUidAndcrawlerUid");
							commandErr = true;
							continue;
						}

						outputTo("WARNING: May result in corruption in crawler registry system if it is still owned"
								+ " by other manager and is still online, use this only to cleanup upon crash.");
						txGraph.begin();
						//Find the affected work storage from the subscribed work. Every subscripted work may share or not share a same work storage.
						//We here will make sure we will not unregister them twice. If the node have no work, then he will not have any sub worker
						//that register work storage, then it means no operation.
						ArrayList<String> potentialRegisteredWorkStorageId = new ArrayList<>();
						//Unsubscribe from jobcenter.
						for (int i=0; i<CRAWLER_TASK_ASSIGNMENT.workerList.length; i++) {
							ArrayList<Vertex> potentiallySubscripted = txGraph.getVerticesOfClass(CRAWLER_TASK_ASSIGNMENT.workerList[i]);
							for (Vertex v : potentiallySubscripted) {
								String uid = v.getProperty(LP.nodeUID);
								if (uid.toLowerCase().equals(tokens.get(1).toLowerCase())) {
									potentialRegisteredWorkStorageId.add((String) v.getProperty(LP.data));
									v.remove();
									break;
								}
							}
						}

						//http://stackoverflow.com/questions/203984/how-do-i-remove-repeated-elements-from-arraylist
						Set<String> hs = new HashSet<>();
						hs.addAll(potentialRegisteredWorkStorageId);
						potentialRegisteredWorkStorageId.clear();
						potentialRegisteredWorkStorageId.addAll(hs);

						//Unregister work storages.
						for (String storageId : potentialRegisteredWorkStorageId) {
							StorageRegistrar.unregister(storageId, txGraph);
						}

						txGraph.commit();
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.forceRemoveSTMWorker.toLowerCase()) || command.equals("frsw")) {
						if (tokens.size() < 2) {
							outputTo("Usage: forceRemoveSTMWorker creatorNodeUidAndSTMWorkerUid");
							commandErr = true;
							continue;
						}

						outputTo("WARNING: May result in corruption in STMWorker registry system if it is still owned"
								+ " by other manager and is still online, use this only to cleanup upon crash.");
						outputTo("This actually does nothing after you changed worker semantic.");
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.forceRemoveWMWorker.toLowerCase()) || command.equals("frww")) {
						if (tokens.size() < 2) {
							outputTo("Usage: forceRemoveWMWorker creatorNodeUidAndWMWorkerUid");
							commandErr = true;
							continue;
						}

						outputTo("WARNING: May result in corruption in WMWorker registry system if it is still owned"
								+ " by other manager and is still online, use this only to cleanup upon crash.");
						outputTo("This actually does nothing after you changed worker semantic.");
					}

					//Halt the service (external machines running ISRA worker) that consume the command you sent.
					else if (command.equals("haltcommandserv") || command.equals("hcs")) {
						//Must have a target node to send command to.
						if (targetNodeCommandStorageId.equals("")) {
							commandErr = true;
							outputTo("No targetNode selected. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}
						else if (targetNodeCommandStorageId.equals("DOWN")) {
							commandErr = true;
							outputTo("Node is already DOWN. Run 'updateTargetNodeList' - 'listTargetNode' - 'selectTargetNode'");
							continue;
						}

			    		txGraph.begin();
			    		Vertex haltCommandServ = txGraph.addVertex(targetNodeCommandStorageId, targetNodeCommandStorageId);
			    		ManagementCommand haltCommand = new ManagementCommand();
			    		haltCommand.command = MANAGEMENT_COMMAND_DEFINE.halt;
			    		//Can be set to any db class, the output will be redirected to there.
			    		haltCommand.returnAddr.add(DBCN.V.devnull.cn);

			    		haltCommandServ.setProperty(LP.data, Util.objectToYml(haltCommand));
			    		txGraph.commit();
			    		//Reset the current selected node to empty, to forces user to select a new node as most operation will not work without it.
			    		targetNodeCommandStorageId = "";
			    		targetNodeUid = "";
					}

					//Halt storage space assigner.
					else if (command.equals("haltstorageregistrar") || command.equals("hsr")) {
						try {
							StorageRegistrar.halt(txGraph);
						}
						catch (IllegalStateException e) {
							commandErr = true;
							outputTo("Failed to halt storage registrar. " + e.getMessage());
						}
					}

					else if (command.equals("updatetargetnodelist") || command.equals("utnl")) {
						availableTargetNodeList = new ArrayList<String>();
						availableTargetNodeStorageList = new ArrayList<String>();
						try {
							ArrayList<Vertex> allRegisteredNode = txGraph.getVerticesOfClass(DBCN.V.worker.cn);
							for (Vertex registry : allRegisteredNode) {
								String nodeId = registry.getProperty(LP.nodeUID);
								String storageId = registry.getProperty(LP.storageId);

								availableTargetNodeList.add(nodeId);
								availableTargetNodeStorageList.add(storageId);
							}
						}
						//Exception thrown if the list is empty.
						catch (Exception e) {
						}
						if (availableTargetNodeList.size() == 0)
							outputTo("No target node is available. The global worker list is empty or doesn't exist.");
					}

					else if (command.equals("listtask") || command.equals("lt")) {
						outputTo("-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-");
						outputTo("---Crawler");
						for (int i=0; i<CRAWLERTASK.arrayForm.length; i++)
							outputTo(CRAWLERTASK.arrayForm[i]);
						outputTo("---STM");
						for (int i=0; i<STMTASK.arrayForm.length; i++)
							outputTo(STMTASK.arrayForm[i]);
						outputTo("---WMTASK");
						for (int i=0; i<WMTASK.arrayForm.length; i++)
							outputTo(WMTASK.arrayForm[i]);
						outputTo("-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-");
					}

					else if (command.equals("listtargetnode") || command.equals("ltn")) {
						outputTo("-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-");
						for (int i=0; i<availableTargetNodeList.size(); i++)
							outputTo("[" + i + "] " + availableTargetNodeList.get(i) + "-" + availableTargetNodeStorageList.get(i) );
						if (availableTargetNodeList.size() == 0)
							outputTo("No node available.");
						outputTo("Call 'updateTargetNodeList' to renew this list.");
						outputTo("-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-");
					}

					//Select the target node you want, it will replace the current target node.
					else if (command.equals("selecttargetnode") || command.equals("stn")) {
						if (tokens.size() < 2) {
							outputTo("Usage: selectTargetNode targetNodeIndex (fetch from 'listTargetNode')");
							commandErr = true;
							continue;
						}
						//Check whether the selected index is parse-able.
						try {
							int selectedIndex = Integer.parseInt(tokens.get(1));

							//Check whether it is a valid index or not.  Ex we have 3 item, max index is 2.
							//if you specify 2, then 2 < 3 is valid. If you specify 3, then 3 < 3 is false.
							if (selectedIndex < availableTargetNodeList.size()) {
								//Update its command storage id from the global list, so it should be fresh and valid.
								boolean foundTarget = false;
								try {
									ArrayList<Vertex> allRegisteredNode = txGraph.getVerticesOfClass(DBCN.V.worker.cn);
									for (Vertex registry : allRegisteredNode) {
										String nodeId = registry.getProperty(LP.nodeUID);
										String selectedTargetNodeId = availableTargetNodeList.get(selectedIndex);
										if (nodeId.equals(selectedTargetNodeId)) {
											foundTarget = true;
											//At the moment if he is online, he should have posted the latest, working and registered command storage id, guaranteed.
											//TODO: Add some checking here in the future to make sure the node is alive.
											targetNodeCommandStorageId = registry.getProperty(LP.storageId);
											targetNodeUid = selectedTargetNodeId;
										}
									}
								}
								//If the global record is empty, of course it cannot found the registry he made.
								catch (Exception e) {
									foundTarget = false;
								}

								if (!foundTarget) {
									outputTo("Invalid target node, cannot find its storage id and registration at DBCN.V.Worker.");
									commandErr = true;
									continue;
								}
							}
							else {
								outputTo("Given index:" + tokens.get(1) + " is out of bound. Size of list is:" + availableTargetNodeList.size());
								commandErr = true;
								continue;
							}
						}
						catch (NumberFormatException e) {
							outputTo("Argument 1:" + tokens.get(1) + " is not a number. Use the targetNode index "
									+ "fetchable from 'listTargetNode' instead of the node's full name.");
							commandErr = true;
						}
					}

					else if (command.equals("executebulk") || command.equals("eb")) {
						if (tokens.size() < 2) {
							outputTo("Usage: executeBulk filePathContainingCommands [spacingForEachCommandInMilli] [continueOnFailure-true1 OR false0]");
							outputTo("NOTE: The command list cannot contain this command 'executeBulk' recursively.");
							commandErr = true;
							continue;
						}

						//Reset all to default. Replace and ignore previous setting.
						executingBulk = false;
						executeBulkContinueOnFailure = false;
						commandErr = false;		//This is compulsory so if last execute bulk command is erroneous, it will not be carried forward.
						executeBulkCommandTimeSpacing = 0;
						executeBulkCurrentIndex = 0;

						if (tokens.size() > 2) {
							if (tokens.size() == 4) {
								String continueOnFailure = tokens.get(3).toLowerCase();
								if (continueOnFailure.equals("true") || continueOnFailure.equals("1"))
									executeBulkContinueOnFailure = true;
								else if (continueOnFailure.equals("false") || continueOnFailure.equals("0"))
									executeBulkContinueOnFailure = false;
								else {
									outputTo("Bad argument 3 (continueOnFailure)" + ". Given:" + tokens.get(3));
								}
							}

							try {
								executeBulkCommandTimeSpacing = Integer.parseInt(tokens.get(2));
							}
							catch (NumberFormatException e) {
								outputTo("Argument 2 (spacingForEachCommandInMilli):" + tokens.get(2) + " is not a number OR"
										+ " overflown int.");
								executeBulkCommandTimeSpacing = 0;
								executeBulkContinueOnFailure = false;		//default is false, reset it to be safe.
								commandErr = true;
								continue;
							}
						}

				        bulkCommandList = new ArrayList<String>();
				        try {
				        	BufferedReader in = new BufferedReader(new FileReader(tokens.get(1)));
				        	String str;
				        	while((str = in.readLine()) != null){
				        		bulkCommandList.add(str);
				        	}
				        	in.close();
				        }
				        catch (FileNotFoundException e) {
				        	outputTo(e.getMessage());
				        	continue;
				        }

				        //Start the command.
						executingBulk = true;
					}

					else if (command.equals("history")) {
						if (tokens.size() < 2) {
							outputTo("Usage: history totalHistoryToBeDisplayed( 0 to show all). Current Size:" + history.size()
								+ "(inclusive of this history command).");
							commandErr = true;
							continue;
						}

						try {
							int selectedCount = Integer.parseInt(tokens.get(1));

							if (selectedCount < 0 || selectedCount >= history.size()) {
								if (selectedCount < 0)
									outputTo("Index cannot be negative.");
								else
									outputTo(selectedCount + " is Out Of Bound. Total history size is:" + history.size()
										+ " (inclusive of this history command).");
								commandErr = true;
								continue;
							}
							//-1 to trim the tail (the last history entry which is the history command itself).
							else if (selectedCount == 0) {
								for (int i=0; i<history.size() - 1; i++) {
									outputTo(history.get(i));
								}
							}
							//If history size is 1 means there is no history, the size 1 is the history command itself.
							else if (history.size() == 1)
								continue;

							else {
								//Print the history in order of insertion and exclude the latest history entry (the history command itself).
								//-1 to trim the array for 2 element, 1 head and 1 tail.
								for (int i=history.size() - selectedCount - 1; i<history.size() - 1; i++) {
									outputTo(history.get(i));
								}
							}
						}
						catch (NumberFormatException e) {
							outputTo("Argument 1 (totalHistoryToBeDisplayed):" + tokens.get(1) + " is not a number OR overflown int.");
							commandErr = true;
							continue;
						}
					}

					else if (command.equals(MANAGEMENT_COMMAND_DEFINE.updateExtHardwarePath.toLowerCase()) || command.equals("uehp") ) {
						try {
							ExternalIOConfig hardwareConfig = new ExternalIOConfig();
							YamlReader hardwareConfigReader = new YamlReader(new FileReader(config.extHardwareConfigFilePath));
							hardwareConfig = hardwareConfigReader.read(ExternalIOConfig.class);

							txGraph.begin();
							//Replace all previous path with new path.
							Util.removeAllVertexFromClass(DBCN.V.extInterface.hw.camera.cam1.cn, txGraph);
							Util.removeAllVertexFromClass(DBCN.V.extInterface.hw.mic.mic1.cn, txGraph);

							Vertex V_extInterface_hw_camera_cam1 = txGraph.addVertex(DBCN.V.extInterface.hw.camera.cam1.cn, DBCN.V.extInterface.hw.camera.cam1.cn);
							V_extInterface_hw_camera_cam1.setProperty(LP.data, hardwareConfig.visualInURL);

							Vertex V_extInterface_hw_mic_mic1 = txGraph.addVertex(DBCN.V.extInterface.hw.mic.mic1.cn, DBCN.V.extInterface.hw.mic.mic1.cn);
							V_extInterface_hw_mic_mic1.setProperty(LP.data, hardwareConfig.audioInURL);

							Vertex V_extInterface_hw_controller_rpi = txGraph.addVertex(DBCN.V.extInterface.hw.controller.rpi.cn, DBCN.V.extInterface.hw.controller.rpi.cn);
							V_extInterface_hw_controller_rpi.setProperty(LP.data, hardwareConfig.rpiURL);

							txGraph.finalizeTask();
						} catch (YamlException | FileNotFoundException e) {
							e.printStackTrace();
							txGraph.rollback();
							continue;
						}
					}

					else if (command.equals("help")) {
						outputTo("-*-*-*-*-*-*-*-S.O.S*-*-*-*-*-*-*-*-*-");
						outputTo("Available commands:");
						outputTo("halt ~h ");
						outputTo("addCrawler ~ac");
						outputTo("addSTMWorker ~asw");
						outputTo("addWMWorker ~aww");
						outputTo("haltCrawler ~hc");
						outputTo("haltSTMWorker ~hsw");
						outputTo("haltWMWorker ~hww");
						outputTo("forceRemoveCrawler ~frc");
						outputTo("forceRemoveSTMWorker ~frsw");
						outputTo("forceRemoveWMWorker ~frww");
						outputTo("haltCommandServ ~hcs");
						outputTo("haltStorageRegistrar ~hsr");
						outputTo("updateTargetNodeList ~utnl");
						outputTo("listTask ~lt");
						outputTo("listTargetNode ~ltn");
						outputTo("selectTargetNode ~stn");
						outputTo("executeBulk ~eb");
						outputTo("history");
						outputTo("NOTE: Only commands are case insensitive, argument is case sensitive!");
						outputTo("Type command name with no argument to get is usage guide. [] bracketed argument means optional. ~shorthand");
						outputTo("-*-*-*-*-*-*-*-S.O.S*-*-*-*-*-*-*-*-*-");
					}

					else
						outputTo("Unknown command.");
				}

				//Shutdown the logListener.
				logListener.halt();

				txGraph.shutdown();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		online.set(false);

		//Free the embedding host from waiting for output.
		if (embedded)
			commandProcessed.set(true);
	}

	/**
	 * Start the console in non embedded mode.
	 * @param args
	 */
	public static void main(String[] args) {
		Thread thread = new Thread(new Console(args, false));
		thread.start();
	}
}
