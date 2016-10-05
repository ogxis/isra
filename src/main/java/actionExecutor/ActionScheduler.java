package actionExecutor;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import logger.Logger.CLA;
import logger.Logger.Credential;
import logger.Logger.LVL;
import pointerchange.POInterchange;
import startup.StartupSoft;
import stm.DBCN;
import stm.STMClient;
import stm.STMServer;
import utilities.Util;

/**
 * Holds all the function for output to external physical devices.
 * IMPLEMENTATION NOTE: Caller are responsible to do the commit().
 */
public abstract class ActionScheduler {
	public static String rpiUrl;

	/*
	 * Must call this for the 'execute' function to work.
	 */
	public static void init() {
		Graph txGraph = StartupSoft.factory.getTx();
		ActionScheduler.rpiUrl = txGraph.getFirstVertexOfClass(DBCN.V.extInterface.hw.controller.rpi.cn).getProperty(LP.data);
		txGraph.shutdown();
	}

	/**
	 * Execute the given process immediately.
	 * @param processDataVertex Any POFeedBack general vertex.
	 */
	public static void execute(Vertex processGeneralVertex, Graph txGraph) {
		//If uses mock device, just consume the request, mock device is for testing purposes only.
		if (STMServer.mockDevice)
			return;

		String dataType = processGeneralVertex.getCName();
		//TODO: Add more device as needed, to output to the external device.
		//Make direct connection to device and call him to execute it according to the data given.

		//POInterchange format is used to send structured data to the external device.
		//Our device here is a raspberry pi with 4 motors. Speaker is currently not available.
		POInterchange POOutputToDev = new POInterchange();
		POOutputToDev.motor1 = -1d;
		POOutputToDev.motor2 = -1d;
		POOutputToDev.motor3 = -1d;
		POOutputToDev.motor4 = -1d;
		POOutputToDev.speaker1 = new byte[0];

		//Add some mutation as our motor is not servo, it is DC, output only, thus he cannot know the possibility that the value
		//can be changed. Now we add some mutation at the beginning, later after he knows, we no longer need this.
		//POFeedback is important but DC doesn't give us.
		//TODO: If in future you can change to servo motor, then the mutation is no longer necessary. Feedback is the key.
		double globalDist = STMClient.getGlobalDist(txGraph);

		if (dataType.equals(DBCN.V.general.rawData.POFeedback.dev.motor1.cn)) {
			POOutputToDev.motor1 = Util.traverseGetDataField(processGeneralVertex, Direction.IN, DBCN.E.data, LP.data
					, DBCN.V.LTM.rawData.POFeedback.dev.motor1.cn);
			POOutputToDev.motor1 = (POOutputToDev.motor1 + globalDist) / 2;
			if (POOutputToDev.motor1 > 100d)
				POOutputToDev.motor1 = 100d;
			else if (POOutputToDev.motor1 < 0d)
				POOutputToDev.motor1 = 0d;
		}
		else if (dataType.equals(DBCN.V.general.rawData.POFeedback.dev.motor2.cn)) {
			POOutputToDev.motor2 = Util.traverseGetDataField(processGeneralVertex, Direction.IN, DBCN.E.data, LP.data
					, DBCN.V.LTM.rawData.POFeedback.dev.motor2.cn);
			POOutputToDev.motor2 = (POOutputToDev.motor2 + globalDist) / 2;
			if (POOutputToDev.motor2 > 100d)
				POOutputToDev.motor2 = 100d;
			else if (POOutputToDev.motor2 < 0d)
				POOutputToDev.motor2 = 0d;
		}
		else if (dataType.equals(DBCN.V.general.rawData.POFeedback.dev.motor3.cn)) {
			POOutputToDev.motor3 = Util.traverseGetDataField(processGeneralVertex, Direction.IN, DBCN.E.data, LP.data
					, DBCN.V.LTM.rawData.POFeedback.dev.motor3.cn);
			POOutputToDev.motor3 = (POOutputToDev.motor3 + globalDist) / 2;
			if (POOutputToDev.motor3 > 100d)
				POOutputToDev.motor3 = 100d;
			else if (POOutputToDev.motor3 < 0d)
				POOutputToDev.motor3 = 0d;
		}
		else if (dataType.equals(DBCN.V.general.rawData.POFeedback.dev.motor4.cn)) {
			POOutputToDev.motor4 = Util.traverseGetDataField(processGeneralVertex, Direction.IN, DBCN.E.data, LP.data
					, DBCN.V.LTM.rawData.POFeedback.dev.motor4.cn);
			POOutputToDev.motor4 = (POOutputToDev.motor4 + globalDist) / 2;
			if (POOutputToDev.motor4 > 100d)
				POOutputToDev.motor4 = 100d;
			else if (POOutputToDev.motor4 < 0d)
				POOutputToDev.motor4 = 0d;
		}
		else if (dataType.equals(DBCN.V.general.rawData.POFeedback.dev.speaker1.cn)) {
			POOutputToDev.speaker1 = Util.traverseGetDataField(processGeneralVertex, Direction.IN, DBCN.E.data, LP.data
					, DBCN.V.LTM.rawData.POFeedback.dev.speaker1.cn);
		}

		System.out.println("Action Scheduler: waiting receiver to accept socket connection.");

		//Send data to receiver.
		Socket toDeviceSocket;
		try {
			//No need to close stream, close only socket, close socket on the final receiver operation. We are not final receiver here so
			//no close socket.
			toDeviceSocket = new Socket(rpiUrl, 45000);
			DataOutputStream toDevStream = new DataOutputStream(toDeviceSocket.getOutputStream());
			toDevStream.writeUTF(Util.objectToYml(POOutputToDev));
		} catch (IOException e) {
			StartupSoft.logger.log(new Credential(), LVL.ERROR, CLA.EXCEPTION, "Action scheduler failed to connect socket.", e);
		}

		StartupSoft.logger.log(new Credential(), LVL.INFO, CLA.NORM, "Action Scheduler: Sent motorData: " + POOutputToDev.motor1 + " "
				+  POOutputToDev.motor2 + " " + POOutputToDev.motor3 + " " + POOutputToDev.motor4 + " ; Audio data: "
				+ ( POOutputToDev.speaker1.length == 0 ? -1 : processGeneralVertex ) );
	}
}
