package ICL;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import org.apache.commons.io.FileUtils;

import utilities.Util;

/**
 * Test the fastest and glitch less method of fetching audio data stream and split them into files.
 * Source of clicking noise: http://www.kozco.com/tech/audacity/pix/DCOffset.jpg
 * http://stackoverflow.com/questions/36859363/clicking-noise-when-concatenating-joining-two-or-more-wav-files
 */
public class AudioICLSpeedTest {
	public static void main(String[] args) {
		long startTime = System.currentTimeMillis();

		//Select the sample length to extract header from. Tenth means 100ms, thus 4410 data (4410 * 10 = 44100hz, 100ms * 10 = 1s)
		Path path = Paths.get("src/main/resources/audioICL/audioHeader/tenthSecSample.wav");
		try {
			byte[] data = Files.readAllBytes(path);
			ICL.Audio.setAudioDataHeader(data);
		} catch (IOException e) {
			e.printStackTrace();
		}

		String audioUrl = "http://10.42.0.92:8080/audio.wav";
		InputStream audioInput = null;

		//Connect to audio server.
		URL actualAudioUrlConnection = null;
		try {
			actualAudioUrlConnection = new URL(audioUrl);
		} catch (MalformedURLException e1) {
			throw new IllegalStateException ("Unreachable audio URL:" + audioUrl);
			//TODO: perform recovery by keep updating the url until it becomes valid by keep fetching it from DB.
		}

		ArrayList<byte[]> audioDataList = new ArrayList<byte[]>();

		//Fetch audio
		try {
			audioInput = actualAudioUrlConnection.openStream();
		} catch (IOException e) {
			throw new IllegalStateException("Audio Input Device Error.", e);
		}
		DataInputStream audioDataInputStream = new DataInputStream(audioInput);

		boolean headerSkipped = false;
		int count = 0;
		while (System.currentTimeMillis() - startTime < 10000) {
			try {
				if (headerSkipped) {
					byte[] audioData = new byte[4410];
					audioDataInputStream.readFully(audioData);
					audioDataList.add(audioData);
					count++;
				}
				else {
					headerSkipped = true;
					byte[] audioData = new byte[46];
					audioDataInputStream.readFully(audioData);
				}
			} catch (IOException e) {
				throw new IllegalStateException("Audio Input Device Error.", e);
			}
		}
		try {
			audioInput.close();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		for (int i=0; i<audioDataList.size(); i++) {
			try {
				FileUtils.writeByteArrayToFile(new File("src/test/resources/audioICL/temp/audioConcat/a" + i +".wav"), Util.concatByteArray(ICL.Audio.getAudioDataHeader(), audioDataList.get(i)));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		//10 means 10 sec.
		System.out.println("Total frame: " + count + "; Average per sec: " + count / 10);
	}

}
