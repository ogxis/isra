package ICL;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.commons.io.FileUtils;

import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;

/**
 * Fetch audio data and concatenate them. To use it copy it to startupSoft main, after the txGraph instance has been initialized.
 * Sample code. txGraph not initialized.
 * Test requires the data vertex to exist.
 */
public class FetchAudioData {
	public static void main(String[] args) {
		Graph txGraph = null;
		ArrayList<Vertex> audioDataVertex = txGraph.getVerticesOfClass(DBCN.V.LTM.rawData.PI.dev.mic1.cn);
		ArrayList<byte[]> audioData = new ArrayList<byte[]>();
		for (Vertex v : audioDataVertex) {
			audioData.add( (byte[]) v.getProperty(LP.data));
		}
		ArrayList<Byte> concat = new ArrayList<Byte>();
		ICL.Audio.setAudioDataHeader(audioData.get(0));

		for (byte[] data : audioData) {
			byte[] result = ICL.Audio.trimAudioDataHeader(data);
			for (byte b : result)
				concat.add(b);
		}

		byte[] audioHeader = ICL.Audio.getAudioDataHeader();
		byte[] finalResult = new byte[audioHeader.length + concat.size()];

		int count = 0;
		for (int i=0; i<audioHeader.length; i++) {
			finalResult[i] = audioHeader[i];
			count++;
		}
		for (byte b : concat) {
			finalResult[count] = b;
			count++;
		}

		try {
			FileUtils.writeByteArrayToFile(new File("resources/test/audioICL/temp/audioConcatenatedResult"), finalResult);
		} catch (IOException e) {
			e.printStackTrace();
		}

		System.exit(0);
	}

}
