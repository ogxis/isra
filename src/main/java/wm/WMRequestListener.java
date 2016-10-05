package wm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import startup.StartupSoft;
import stm.DBCN;
import utilities.Util;

/**
 * Listen request from any other workers, do PaRc then return.
 */
public class WMRequestListener implements Runnable {
	public static class PACKET {
		public static final String checkRidExistInWorkingMemory = "1";
		public static final String eof = "eof";
	}

	private int haltIndex;
	private String nodeUID;
	private ServerSocket serverSocket;
	private static final int timeout = 25000;

	public WMRequestListener (int givenHaltIndex, int givenPort, String givenNodeUID, Graph txGraph) {
		//Setup port and update the configuration specifically for this WMRequestListener.
		try {
			serverSocket = new ServerSocket(givenPort, 50, Util.getEthIp().get(0));
			serverSocket.setSoTimeout(timeout);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to openn server socket on port:" + givenPort + "; " + e);
		}
		this.haltIndex = givenHaltIndex;
		this.nodeUID = givenNodeUID;
		String hostName = serverSocket.getInetAddress().getHostName();

		txGraph.begin();
		Vertex config = txGraph.getLatestVertexOfClass(DBCN.V.worker.WMRequestListener.cn);
		config.setProperty(LP.nodeUID, nodeUID);
		config.setProperty(LP.hostName, hostName);
		config.setProperty(LP.port, givenPort);
		txGraph.commit();
	}

	/**
	 * Add request to the WMListener, which will manage all the IO transparently and return the result.
	 * @param rid The rid of the vertex to be matched.
	 * @param txGraph
	 * @return Empty string if not found, RID string if found.
	 */
	public static String addRequest (String rid, Graph txGraph) {
		/*
		 * NOTE THAT network semantic is port client close, stream receiver close.
		 */
		Vertex config = txGraph.getFirstVertexOfClass(DBCN.V.worker.WMRequestListener.cn);
		String hostName = config.getProperty(LP.hostName);
		int port = config.getProperty(LP.port);

		//Connect to the WM server.
		try {
			//http://stackoverflow.com/questions/4969760/set-timeout-for-socket
			//http://stackoverflow.com/questions/5632279/how-to-set-timeout-on-client-socket-connection
			Socket socket = new Socket();
			//Connection time out.
			socket.connect(new InetSocketAddress(hostName, port), timeout);
			//connected but no response, read time out.
			socket.setSoTimeout(timeout);

			DataOutputStream os = new DataOutputStream(socket.getOutputStream());

			//Write the request header and its parameter.
			os.writeUTF(PACKET.checkRidExistInWorkingMemory + " " + rid);
			os.flush();

			DataInputStream is = new DataInputStream(socket.getInputStream());
			String result = is.readUTF();
			if (result.equals("0"))
				result = "";

			is.close();
			socket.close();

			return result;
		} catch (IOException e) {
			//Only treat it as exceptional if the host is still online but IOException occurs.
			if (StartupSoft.online.get())
				throw new IllegalStateException("At WMRequestListener client function addRequest, connection failed. Original Message: " +
						Util.stackTraceToString(e));
			System.out.println("WMRequest timeout. Main controller offline, add operation cancelled and returned '' empty string");
			return "";
		}
	}

	public void halt() {
		try {
			serverSocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void run() {
		System.out.println("WMRequestListener for WM: " + nodeUID + " ONLINE. hostName: " + serverSocket.getInetAddress().getHostAddress() + "; port: " + serverSocket.getLocalPort());
		//While your parent thread is not halted yet, this shares the halt index of his parent WM service.
		while (!StartupSoft.halt.get(haltIndex).get()) {
			Socket clientSocket = null;
			try {
				//Timeout periodically to check whether isHalt() or not.
				//http://stackoverflow.com/questions/2983835/how-can-i-interrupt-a-serversocket-accept-method
				clientSocket = serverSocket.accept();
			}
			catch (SocketTimeoutException ste) {
				continue;
			} catch (IOException e) {
				throw new IllegalStateException("WM Request Listener: Error during serverSocket.accept(). Original message: " + e.getMessage());
			}
			if (clientSocket.isClosed()) {
				throw new IllegalStateException("At WM handle request: The given connection is already closed. Addr: "
						+ clientSocket.getInetAddress().getHostAddress() + " Port:" + clientSocket.getPort());
			}
			try {
				/*
				 * NOTE THAT network semantic is port client close, stream receiver close.
				 */
				DataInputStream is = new DataInputStream(clientSocket.getInputStream());
				DataOutputStream os = new DataOutputStream(clientSocket.getOutputStream());
				String inputData = is.readUTF();
				if (inputData.length() < 2)
					throw new IllegalStateException("At WM, inputData too short. Expecting command(1 char) + ' '(1 char) "
							+ "+ param(unknown char) but get: '" + inputData + "'");

				String request = inputData.substring(0, 1);
				String data = inputData.substring(2, inputData.length());

				if (request.equals(WMRequestListener.PACKET.checkRidExistInWorkingMemory)) {
					Graph txGraph = StartupSoft.factory.getTx();
					txGraph.loggerSet(StartupSoft.logger, null);
					//Expect only 1 rid per network request call.
					String resultRid = WorkingMemory.checkRidExistInReality(data, txGraph);
					boolean isExist = true;

					if (resultRid.equals(""))
						isExist = false;

					//Return a response to the original caller then clean up.
					try {

						//If exist, write the rid, else false (0)
						if (isExist)
							os.writeUTF(resultRid);
						else
							os.writeUTF("0");

						os.flush();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				is.close();
			}
			catch (IOException e) {
				throw new IllegalStateException("WM Request Listener: Error during processing user input. Original message: " + e.getMessage());
			}
		}

		//Close the server socket and exit.
		try {
			serverSocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		System.out.println("WMRequestListener for WM: " + nodeUID + " Closed.");
	}
}
