package storageRegistrar;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Iterator;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.esotericsoftware.yamlbeans.YamlWriter;
import com.orientechnologies.orient.client.remote.OServerAdmin;

import isradatabase.Graph;
import isradatabase.GraphFactory;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import utilities.Util;
import ymlDefine.YmlDefine.DBCredentialConfig;
import ymlDefine.YmlDefine.StartUpSoftConfig;
import ymlDefine.YmlDefine.StorageRegistrarConfig;
import ymlDefine.YmlDefine.WorkerConfig;

/**
 * Manage worker storage assignment. Give each registered worker thread a storage space to receive command and tasks.
 */
public class StorageRegistrar {
	//2 type, 1 is WorkerConfig, 2 is StartupSoftConfig.
	//NOTE: Client are responsible to close the sockets.
	//Add 1 means WorkerConfig, just to differentiate it.
	public static String getStorage(WorkerConfig config, Graph txGraph) {
		//Send the data to the Main using socket.
		Vertex registrarDetail = txGraph.getFirstVertexOfClass(DBCN.V.worker.registrar.cn);
		String hostName = registrarDetail.getProperty(LP.hostName);
		int port = registrarDetail.getProperty(LP.port);
		String assignedStorageId = "";

		try {
			Socket clientSocket = new Socket(hostName, port);

			DataOutputStream requestStream = new DataOutputStream(clientSocket.getOutputStream());
			requestStream.writeUTF("add1" + Util.objectToYml(config));

			DataInputStream inStream = new DataInputStream(clientSocket.getInputStream());
			assignedStorageId = inStream.readUTF();

			requestStream.close();
			inStream.close();
			clientSocket.close();
		} catch (IOException e) {
			throw new IllegalStateException("Cannot proceed without a valid work storage, original message:" + e);
		}
		return assignedStorageId;
	}

	//Add 2 means StartupSoftConfig, just to differentiate it.
	public static String getStorage(StartUpSoftConfig config, Graph txGraph) {
		//Send the data to the Main using socket.
		Vertex registrarDetail = txGraph.getFirstVertexOfClass(DBCN.V.worker.registrar.cn);
		String hostName = registrarDetail.getProperty(LP.hostName);
		int port = registrarDetail.getProperty(LP.port);
		String assignedStorageId = "";

		try {
			Socket clientSocket = new Socket(hostName, port);

			DataOutputStream requestStream = new DataOutputStream(clientSocket.getOutputStream());
			requestStream.writeUTF("add2" + Util.objectToYml(config));

			DataInputStream inStream = new DataInputStream(clientSocket.getInputStream());
			assignedStorageId = inStream.readUTF();

			requestStream.close();
			inStream.close();
			clientSocket.close();
		} catch (IOException e) {
			throw new IllegalStateException("Cannot proceed without a valid work storage, original message:" + e + ";"
					+ " Check the ip of the storageRegistrar to see if he is correct as advertised in db and reachable (LAN or WAN ip). "
					+ "HostName: " + hostName + "; Port: " + port);
		}
		return assignedStorageId;
	}

	public static void unregister(String storageId, Graph txGraph) {
		//Send the data to the Main using socket.
		Vertex registrarDetail = txGraph.getFirstVertexOfClass(DBCN.V.worker.registrar.cn);
		String hostName = registrarDetail.getProperty(LP.hostName);
		int port = registrarDetail.getProperty(LP.port);

		try {
			Socket clientSocket = new Socket(hostName, port);

			DataOutputStream requestStream = new DataOutputStream(clientSocket.getOutputStream());
			requestStream.writeUTF("remove" + storageId);
			requestStream.close();

			clientSocket.close();
		} catch (IOException e) {
			throw new IllegalStateException("Cannot proceed without a valid work storage, original message:" + e);
		}
	}

	/*
	 * Halt the storage registrar thread.
	 */
	public static void halt(Graph txGraph) {
		//Send the data to the Main using socket.
		Vertex registrarDetail = txGraph.getFirstVertexOfClass(DBCN.V.worker.registrar.cn);
		String hostName = registrarDetail.getProperty(LP.hostName);
		int port = registrarDetail.getProperty(LP.port);

		try {
			Socket clientSocket = new Socket(hostName, port);

			DataOutputStream requestStream = new DataOutputStream(clientSocket.getOutputStream());
			requestStream.writeUTF("halt");
			requestStream.close();

			clientSocket.close();
		} catch (UnknownHostException e) {
			throw new IllegalStateException("Most probably the registrar is down, registrar detail hostName:" + hostName + "; port:" + port, e);
		} catch (IOException e) {
			throw new IllegalStateException("Most probably the registrar is down, registrar detail hostName:" + hostName + "; port:" + port, e);
		}
	}

	public static void main(String[] args) {
		System.out.println("ISRA Worker Storage Registrar");

		long startTime = System.currentTimeMillis();

		//Load the config file then load DBCredential data file.
		YamlReader reader = null;
		StorageRegistrarConfig config = null;
		DBCredentialConfig DBLoginCredential = null;

		//Fetch configuration details.
		try {
			if (args.length > 1) {
				throw new IllegalArgumentException("Too many argument!\n"
						+ "Usage: programName CustomConfigFile");
			}
			else if (args.length == 1) {
				reader = new YamlReader(new FileReader(args[0]));
				config = reader.read(StorageRegistrarConfig.class);
				DBLoginCredential = Util.loadDBCredentialFromFile(config.DBCredentialConfigFilePath);
			}
			else if (args.length == 0)
				throw new IllegalArgumentException("Usage: programName CustomConfigFile");
		}
		catch (FileNotFoundException e) {
			throw new IllegalArgumentException("Given config file not found:" + args[0]);
		}
		catch (YamlException e) {
			throw new IllegalArgumentException("Corrupted config file:" + args[0] + "/nOriginal message" + e.getMessage());
		}

		//Try to connect to database.
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
				GraphFactory factory = new GraphFactory(DBLoginCredential.dbMode + DBLoginCredential.dbPath.get(successIndex));
				factory.setAutoStartTx(false);
				factory.setupPool(0, 10);
				Graph txGraph = factory.getTx();

				//Update the address space of the db.
				txGraph.begin();
				Vertex registrarDetail = txGraph.getFirstVertexOfClass(DBCN.V.worker.registrar.cn);
				registrarDetail.setProperty(LP.hostName, config.hostName);
				registrarDetail.setProperty(LP.port, config.port);
				txGraph.commit();
				txGraph.shutdown();

				//Accept connection from getStorage.
				ServerSocket serverSocket = null;
				try {
					serverSocket = new ServerSocket(config.port, 50, Util.getEthIp().get(0));
					serverSocket.setSoTimeout( (int)config.backUpAfterMilli);
				} catch (IOException e) {
					throw new IllegalStateException("Failed to open server socket on port:" + config.port + "; " + e);
				}

				System.out.println("Current Highest Storage Id:" + config.currentStorageCount);
				System.out.println("HostName:" + config.hostName + " ; Port:" + config.port);
				System.out.println("BackupAfterMilli:" + config.backUpAfterMilli);
				System.out.println("Version: " + config.version);
				System.out.println("Ready");

				long nextBackupTime = System.currentTimeMillis() + config.backUpAfterMilli;
				//Do the active recycling, accept request from workers and listen for halt command.
				boolean firstTimeGraphShutdownExceptionDone = false;
				while(true) {
					//Update the txGraph instance to avoid mysterious bug that things never gets committed, shutting it down guarantees it must commit.
					try {
						txGraph.shutdown();
					}
					catch (Exception e) {
						if (firstTimeGraphShutdownExceptionDone)
							throw new IllegalStateException("Txgraph shutdown error cannot be thrown more than once (after the first initial expected fail)"
									+ ", you failed to close the graph appropriately this time.");
						firstTimeGraphShutdownExceptionDone = true;
					}
					txGraph = factory.getTx();

					Socket clientSocket = null;
					try {
						//Keep blocking until a new connection is established.
						//http://stackoverflow.com/questions/2983835/how-can-i-interrupt-a-serversocket-accept-method
						clientSocket = serverSocket.accept();
					}
					catch (SocketTimeoutException ste) {
						//Catch the exception to temporary bail out from listening socket so we can back up data as needed.
					}
					catch (IOException e) {
						e.printStackTrace();
						break;
					}

					if (clientSocket != null) {
						DataInputStream inStream = new DataInputStream(clientSocket.getInputStream());
						String data = inStream.readUTF();

						//If the sender is calling us to die, then ok.
						if (data.equals("halt")) {
							break;
						}

						//Else it should be a normal request.
						else if (data.substring(0, 4).equals("add1") || data.substring(0, 4).equals("add2")) {
							//Strip out the command.
							String command = data.substring(0, 4);
							data = data.substring(4, data.length());

							WorkerConfig registrantData = null;
							StartUpSoftConfig registrantData2 = null;
							reader = new YamlReader(data);

							if (command.equals("add1")) {
								try {
									registrantData = reader.read(WorkerConfig.class);
								} catch (YamlException e1) {
									e1.printStackTrace();
								}
							}
							else if (command.equals("add2")) {
								try {
									registrantData2 = reader.read(StartUpSoftConfig.class);
								} catch (YamlException e1) {
									e1.printStackTrace();
								}
							}

							//Find a free slot for him from recycled first.
							//Storage initials are W00001 ~ W30000
							String selectedStorageId = "";
							if (config.recycledStorageIdList.size() != 0) {
								for (Iterator<String> i = config.recycledStorageIdList.iterator(); i.hasNext();) {
									selectedStorageId += i.next();
									i.remove();
									break;
								}
							}

							//Without recycled storage, use new storage space then.
							else {
								//http://stackoverflow.com/questions/2705096/how-do-i-add-left-padded-zeros-to-a-number-in-java
								//Max 30k workers, thus 5 digit max.
								NumberFormat formatter = new DecimalFormat("00000");
								//Add its initial 'W', stands for worker.
								selectedStorageId += "W";
								selectedStorageId += formatter.format(config.currentStorageCount);
								config.currentStorageCount += 1;
								System.out.println("Current Highest Storage Id:" + config.currentStorageCount);
							}

							//Register it at the global worker registry record before handling over the storage.
							//http://orient-database.narkive.com/PW8GqNA3/orientdb-getting-records-by-index-with-java-api
							//http://stackoverflow.com/questions/29474506/orientdb-getvertices-not-working-with-java-generated-class-index
							String registrantName = "";
							if (command.equals("add1"))
								registrantName = registrantData.uid;
							else if (command.equals("add2"))
								registrantName = registrantData2.nodeId;

							txGraph.begin();
							//From the registration storage list, using index, find the vertex that its storageId is the specified storage id.
							Vertex targetStorage = txGraph.getVertices(DBCN.V.worker.storageRegister.cn, new String[] {LP.storageId.toString()}, new Object[] {selectedStorageId}).get(0);
							targetStorage.setProperty(LP.isRegistered, true);
							targetStorage.setProperty(LP.registrant, registrantName);
							targetStorage.setProperty(LP.lastPingTime, System.currentTimeMillis());
							targetStorage.setProperty(LP.pingLatency, 0);
							targetStorage.setProperty(LP.timeStamp, System.currentTimeMillis());

							//Clean up the storage to avoid previously unprocessed trash.
							Util.removeAllVertexFromClass(selectedStorageId, txGraph);

							txGraph.commit();
							System.out.println("Assigned Storage:'" + selectedStorageId + "' to " + registrantName);
							//Return the storage name to the socket and close.
							DataOutputStream feedbackStream = new DataOutputStream(clientSocket.getOutputStream());
							feedbackStream.writeUTF(selectedStorageId);
						}

						else if (data.substring(0, 6).equals("remove")) {
							data = data.substring(6, data.length());
							//Set the worker storage as unregistered and add it to our recycle list.
							txGraph.begin();
							Vertex targetStorage = txGraph.getVertices(DBCN.V.worker.storageRegister.cn, new String[] {LP.storageId.toString()}, new Object[] {data}).get(0);
							targetStorage.setProperty(LP.isRegistered, false);
							txGraph.commit();
							config.recycledStorageIdList.add(data);
							System.out.println("Recycled storage:" + data);
							//TODO: For security, you might want to pair the actual user of the storage against the request to identify feud request.
						}

						//Unknown gibberish command.
						else {
							System.out.println("Bad bad request:" + data);
							System.out.println("Perhaps you are drunk or someone is messing with your system by using bad request.");
						}
					}
					//If it is time to backup.
					if (System.currentTimeMillis() > nextBackupTime) {
						//Save all the data for future reference. args[0] is the original given file, we will overwrite it.
						YamlWriter ywriter = new YamlWriter(new FileWriter(args[0]));
						ywriter.write(config);
						ywriter.close();
						nextBackupTime = System.currentTimeMillis() + config.backUpAfterMilli;
					}

					//TODO: Periodically ping registrant(user thread) to ensure they are still online,
					//if offline then call exception and reclaim place.
				}
				serverSocket.close();
				//Implement a tracer, trace dead storage owner, then if they are not main worker, return their work back to relative work
				//storage and continue.

				//Save once more.
				//Save all the data for future reference. args[0] is the original given file, we will overwrite it.
				YamlWriter ywriter = new YamlWriter(new FileWriter(args[0]));
				ywriter.write(config);
				ywriter.close();
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}

		serverAdmin.close();

		System.out.println("Storage Registrar Halted Successfully. Time:"
				+ Util.epochMilliToReadable(System.currentTimeMillis())
				+ ", Duration in milli: " + (System.currentTimeMillis() - startTime) );
	}
}
