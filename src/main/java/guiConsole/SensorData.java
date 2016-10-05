package guiConsole;

/**
 * All sensor data to be displayed to the user via the GUI. Add new sensor data here in order to make use of the faucet to transfer the
 * data, the receiving site uses this class too and fetch only the data that they wanted.
 */
public class SensorData {
	//Motor data front left bottom right initials.
	double flm;
	double frm;
	double blm;
	double brm;

	public SensorData() {
		flm = -1d;
		frm = -1d;
		blm = -1d;
		brm = -1d;
	}
}