package hw;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

import org.junit.Test;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;

import pointerchange.POInterchange;

public class HWTest {
	@Test
	public void test() {
		long time = System.currentTimeMillis() + 10000;
		int count = 0;
		while (System.currentTimeMillis() < time) {
			String POFeedbackYml = "";
			try {
				Socket clientSocket = new Socket("10.42.0.56", 40000);
				DataInputStream inStream = new DataInputStream(clientSocket.getInputStream());
				POFeedbackYml = inStream.readUTF();

				inStream.close();
				clientSocket.close();
			} catch (UnknownHostException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			POInterchange POFeedbackData = null;
			YamlReader reader = new YamlReader(POFeedbackYml);
			try {
				POFeedbackData = reader.read(POInterchange.class);
			} catch (YamlException e1) {
				e1.printStackTrace();
			}
			count++;
		}
		System.out.println("Average fetch per sec: " + ((double)count / 10d));
		System.out.println(count);
	}
}
