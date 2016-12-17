package ymlDefine;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Class structure for YML files.
 */
public abstract class YmlDefine {
	/**
	 * Universal DB Login Credentials, use this to login to ISRA network.
	 */
	public static class DBCredentialConfig {
		//A series of servers to access from. This connects to the Management database instead of the Operation database.
		public ArrayList<String> dbPath;
		public ArrayList<String> userName;
		public ArrayList<String> password;
		public String dbMode;		//remote, plocal.

		public DBCredentialConfig() {
			dbPath = new ArrayList<String>();
			userName = new ArrayList<String>();
			password = new ArrayList<String>();
			dbMode = "";
		}
	}

	/**
	 * Configuration class usable for this startup phrase only, define all the required configuration fields.
	 */
	public static class StartUpSoftConfig {
		public String nodeId;		//Unique identity, will never be changed.
		public String opencvJarPath;
		public boolean shutdownTimeoutEnable;
		public long shutdownTimeoutMilli;
		public String DBCredentialConfigFilePath;

		public StartUpSoftConfig() {
			nodeId = "";
			opencvJarPath = "";
			shutdownTimeoutEnable = true;
			shutdownTimeoutMilli = 300000;
			DBCredentialConfigFilePath = "";
		}
	}

	/**
	 * Startup config for console, with all the credential for you to connect to the server and also your preferences.
	 */
	public static class ConsoleConfig {
		public String defaultTargetNodeId;		//Target node. Those nodes individual computing node, this is default 1.
		public String opencvJarPath;
		public int WMRequestListenerPort;	//The WM's personal external request listening thread for unblock main WM thread purposes.
		public String DBCredentialConfigFilePath;

		public ConsoleConfig() {
			defaultTargetNodeId = "";
			opencvJarPath = "";
			WMRequestListenerPort = -1;
			DBCredentialConfigFilePath = "";
		}
	}

	/**
	 * Startup config for storage registrar.
	 */
	public static class StorageRegistrarConfig {
		public String hostName;
		public int port;
		public int currentStorageCount;	//The highest amount of storage reached.
		public HashSet<String> recycledStorageIdList;
		public String version;
		public long backUpAfterMilli;
		public String DBCredentialConfigFilePath;

		public StorageRegistrarConfig() {
			hostName = "";
			port = -1;
			recycledStorageIdList = new HashSet<String>();
			version = "";
			backUpAfterMilli = 0;
			DBCredentialConfigFilePath = "";
		}
	}

	public static class WorkerList {
		public ArrayList<WorkerConfig> data;
		public WorkerList() {
			data = new ArrayList<WorkerConfig>();
		}
	}

	/**
	 * Configuration file for individual worker.
	 */
	public static class WorkerConfig {
		public boolean isCrawler;
		public boolean isSTMWorker;
		public boolean isWMWorker;

		//For connecting directly to the WM instance to access its data.
		public String hostname;
		public int port;

		//This count is relative to halt array maintained by main parent, a temporary identifier assigned according to the order of invocation.
		//Note: this data is meaningless in storage, it will be reassigned to a new value everytime it is invoked.
		public int haltIndex;
		//Storage id is the storage his reserved. Where all the task vertex will go into it. 1 sub worker can only register 1 storage.
		//Main worker node can also register storage, to store commands from console.
		public String storageId;
		//To return operation logs to parent, then parent will accumulate them and send forward to Management layer.
		public String parentUid;
		//Optional identifier to identify this crawler.
		public String uid;
		public ArrayList<String> preference;	//Preference be in class name based on STMDefine, full class path excluding noidUid.

		public WorkerConfig() {
			isCrawler = false;
			isSTMWorker = false;
			isWMWorker = false;
			hostname = "";
			port = -1;
			haltIndex = -1;
			storageId = "";
			parentUid = "";
			uid = "";
			preference = new ArrayList<String>();
		}
	}

	/**
	 * Structure of command callable by user.
	 */
	public static class ManagementCommand {
		public String command;
		public ArrayList<String> param;
		public ArrayList<String> returnAddr;

		public ManagementCommand() {
			command = "";
			param = new ArrayList<String>();
			returnAddr = new ArrayList<String>();
		}
	}

	/**
	 * All possible commands.
	 */
	public static class MANAGEMENT_COMMAND_DEFINE {
		public static final String ping = "ping";
		public static final String addCrawler = "addCrawler";
		public static final String addSTMWorker = "addSTMWorker";
		public static final String addWMWorker = "addWMWorker";
		public static final String haltCrawler = "haltCrawler";
		public static final String haltSTMWorker = "haltSTMWorker";
		public static final String haltWMWorker = "haltWMWorker";
		public static final String setWorker = "setWorker";
		public static final String halt = "halt";
		public static final String forceRemoveCrawler = "forceRemoveCrawler";
		public static final String forceRemoveSTMWorker = "forceRemoveSTMWorker";
		public static final String forceRemoveWMWorker = "forceRemoveWMWorker";
	}

	/**
	 * Details for map reduce task for individual child workers.
	 */
	public static class TaskDetail {
		public String jobId;
		public String jobType;	//data reference from STMTASK
		public String source;
		public String processingAddr;
		public String completedAddr;
		public String replyAddr;

		//start and end index.
		public long start;
		public long end;
	}

	/**
	 * External hardware configuration path, to setup the system external interfaces.
	 */
	public class ExternalIOConfig {
		public String visualInURL;
		public String audioInURL;
		public String audioOutPath;
		public String motor1InURL;
		public String motor2InURL;
		public String motor3InURL;
		public String motor4InURL;
		public String motor1OutPath;
		public String motor2OutPath;
		public String motor3OutPath;
		public String motor4OutPath;
	}
}
