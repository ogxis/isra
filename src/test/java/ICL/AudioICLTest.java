package ICL;

import java.awt.Point;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Arrays;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.junit.Test;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import utilities.Util;

public class AudioICLTest {
	@Test
	public void kryoTest() {
		Kryo kryo = new Kryo();
		kryo.register(ArrayList.class);
		ArrayList<String> list =  new ArrayList<String>(
				Arrays.asList("ohhh", "yeah!")
			);
		Output output = new Output(new ByteArrayOutputStream());
		kryo.writeObject(output, list);
		byte[] byteOutput = output.toBytes();
		output.close();

		Input input;
		input = new Input(new ByteArrayInputStream(byteOutput));
		ArrayList<String> dList = kryo.readObject(input, ArrayList.class);
		input.close();

		if (!dList.get(0).equals("ohhh") && dList.get(1).equals("yeah!"))
			assert false;
	}

	@Test
	public void audio_dejavu_fingerprint() {
		//http://stackoverflow.com/questions/6295866/how-can-i-capture-the-output-of-a-command-as-a-string-with-commons-exec
		//audio and command path can be relative path.
		String targetAudioFilePath = "resources/test/audioICL/sampleInput.wav";
		String operationType = " fingerprint ";
		String resetDatabase = " true ";
		String commandFilePath = "python resources/audioICL/isra_audioICL_fingerprintAndRecognize.py "
				+ operationType + resetDatabase + targetAudioFilePath;
	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	    CommandLine commandline = CommandLine.parse(commandFilePath);
	    DefaultExecutor exec = new DefaultExecutor();
	    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
	    exec.setStreamHandler(streamHandler);
	    try {
	    	exec.execute(commandline);
	    } catch (IOException e) {
	    	//If error occurs, means it is an internal error.
	    	e.printStackTrace();
	    	System.out.println(outputStream.toString());
	    	assert false;
	    }
	    System.out.println(outputStream.toString());
	}

	@Test
	public void audio_dejavu_recognize() {
		//http://stackoverflow.com/questions/6295866/how-can-i-capture-the-output-of-a-command-as-a-string-with-commons-exec
		//audio and command path can be relative path.
		String targetAudioFilePath = "resources/test/audioICL/sampleInput.wav";
		String operationType = " recognize ";
		String resetDatabase = " false ";
		String commandFilePath = "python resources/audioICL/isra_audioICL_fingerprintAndRecognize.py "
				+ operationType + resetDatabase + targetAudioFilePath;

	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	    CommandLine commandline = CommandLine.parse(commandFilePath);
	    DefaultExecutor exec = new DefaultExecutor();
	    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
	    exec.setStreamHandler(streamHandler);
	    try {
	    	exec.execute(commandline);
	    } catch (IOException e) {
	    	//If error occurs, means it is an internal error.
	    	e.printStackTrace();
	    	System.out.println(outputStream.toString());
	    	assert false;
	    }
	    System.out.println(outputStream.toString());

	    //'None' is the library print statement indicated not match and we do not recommend to modify anything in the lib thus we just use that value.
	    if (outputStream.toString().contains("None"))
	    	System.out.println("Not match.");
	    else
	    	System.out.println("Match  " + outputStream.toString());
	}

	@Test
	public void audio_wavFileConcat() {
		String outputPath = "resources/test/audioICL/temp/concat.wav";
		String concatFileName = "resources/test/audioICL/sampleInput.wav";
		ArrayList<String> concatFilesArr = new ArrayList<String>();

		//The value has no significant, just trying to concat values.
		for (int i=0; i<5; i++)
			concatFilesArr.add(concatFileName);

		String commandFilePath = "python resources/audioICL/isra_audioICL_concatWav.py "
				+ outputPath + " " + Util.arrayListOfStringToStringSeparatedBySpace(concatFilesArr);

	    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	    CommandLine commandline = CommandLine.parse(commandFilePath);
	    DefaultExecutor exec = new DefaultExecutor();
	    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
	    exec.setStreamHandler(streamHandler);
	    try {
	    	exec.execute(commandline);
	    } catch (IOException e) {
	    	//If error occurs, means it is an internal error.
	    	e.printStackTrace();
	    	System.out.println(outputStream.toString());
	    	assert false;
	    }
	    System.out.println(outputStream.toString());
	}

	@Test
	public void audio_lowBandSegment() {
		//http://stackoverflow.com/questions/4616310/convert-signed-int-2bytes-16-bits-in-double-format-with-java
		String fileName = "resources/test/audioICL/cartoon001.wav";
		Path path = Paths.get(fileName);
		try {
			byte[] data = Files.readAllBytes(path);
			double[] result = Util.audioByteArrayToDoubleArray( ICL.Audio.trimAudioDataHeader(data) );

			//Integrity check to see if the data are all within -1 to 1 range.
			for (double d : result) {
				if (d < -1d || d > 1d)
					assert false;
			}

			//Low band cut off.
			final int HEADING_UP = 0;
			final int HEADING_DOWN = 1;
			final int MAINTAIN = 2;
			ArrayList<Point> intersectPoint = new ArrayList<Point>();
			ArrayList<Integer> intersectState = new ArrayList<Integer>();
			int otherCount = 0;
			//cutOffThreshold can only be negative!
			double cutOffThreshold = -0.1;
			assert cutOffThreshold < 0;
			//-1 as we will do +1 to get to next point for 2 point calculation, thus -1 to avoid out of bound.
			for (int i=0; i<result.length - 1; i++) {
				//Check 2 point, there can be 3 condition, 6 possible orientation.
				//View Audio_lowBand_cutoff_peakDetection_possibleSituation_overview.jpeg.
				double d1 = result[i];
				double d2 = result[i+1];

				//Up to Down (Heading downward). eg d1 = 0.3, d2 = -0.4
				if (d1 > cutOffThreshold && d2 < cutOffThreshold) {
					intersectPoint.add(new Point(i, i+1));
					intersectState.add(HEADING_DOWN);
				}
				//Down to Up (Heading upward). eg d1 = -0.4, d2 = 0.3
				else if (d1 < cutOffThreshold && d2 > cutOffThreshold) {
					intersectPoint.add(new Point(i, i+1));
					intersectState.add(HEADING_UP);
				}
				//On the same line as the threshold (equal). eg d1 = -0.1, d2 = -0.1, threshold = -0.1
				else if (d1 == d2 && d1 == cutOffThreshold) {
					intersectPoint.add(new Point(i, i+1));
					intersectState.add(MAINTAIN);
				}
				//Down to up, up to down or equal but doesn't pass through or touches the cut off line.
				else {
					otherCount++;
				}
			}
			System.out.println("Intersect Points: " + intersectPoint.size() + " Other:" + otherCount);

			//Begin grouping them into individual signals. We want a DOWN, UP, DOWN 3 step sequence.
			//So instead of 2 step (end up like a bum), it becomes a stylish square root symbol + a downward straight tail.
			//Note the first beginning signal will be longer than all other as it begins at random.
			ArrayList<Point> groupedSignal = new ArrayList<Point>();
			int lastGroupedIndex = 0;
			boolean down = false;
			for (int i=0; i<intersectPoint.size(); i++) {
				if (intersectState.get(i) == HEADING_DOWN) {
					if (!down) {
						down = true;
					}
					//Already down once. Thus this is second down (already up once so it can come down again). 3 step done,
					//, also mean it is the end of the signal.
					else {
						//One's end is another's start, always use end point instead of start point so it will not leave a trailing unused
						//end point coordinate at the really end.
						int intersectPointEndIndex = intersectPoint.get(i).y;
						groupedSignal.add(new Point(lastGroupedIndex, intersectPointEndIndex));
						lastGroupedIndex = intersectPointEndIndex;
						down = false;
					}
				}
			}

			//Join the tail if the last signal doesn't reach the absolute end.
			if (lastGroupedIndex != result.length - 1) {
				groupedSignal.add(new Point(lastGroupedIndex, result.length - 1));
			}

			System.out.println("Final signal count: " + groupedSignal.size());

			//It is expected that the grouped signal total output size will be greater than the total output size of the original result
			//as the duplicate the starting point of each signal to make it jointed. Else it will be broken and not contagious.
			//At intersect point index there, we used the end index twice to make sure it has no hole, thus added in 1 additional element
			//for each iteration (pattern).
			int sampleCounter = 0;
			for (int j=0; j<groupedSignal.size(); j++) {
				Point p = groupedSignal.get(j);
				for (int i=p.x; i<p.y + 1; i++) {
					sampleCounter++;
				}
			}
			System.out.println("Sample size: " + sampleCounter + " Real original double: "
					+ result.length + " Difference: " + (sampleCounter - result.length));
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		//Look at audioLowBandExtractPatternSampleOutput.png for sample output.
	}

	@Test
	public void audio_fetch() {
		InputStream audioInputStream = null;
		DataInputStream audioDataInputStream = null;
		String audioUrl = "";

		//Connect to audio server if not yet connected before.
		for (int i=0; i<10; i++) {
			if (audioInputStream == null && audioDataInputStream == null) {
				if (audioUrl.equals(""))
					audioUrl = "http://10.42.0.92:8080/audio.wav";
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
			try {
				FileUtils.writeByteArrayToFile(new File("resources/test/audioICL/temp/audioConcatBin/concat" + i), Util.concatByteArray(ICL.Audio.getAudioDataHeader(), audioDataWithoutHeader));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
