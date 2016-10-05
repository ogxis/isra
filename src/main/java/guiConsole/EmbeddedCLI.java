package guiConsole;

import console.Console;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import utilities.Util;

public class EmbeddedCLI implements Runnable {
	/**
	 * Check whether the CLI thread is online.
	 * @return
	 */
	public static boolean isOnline() {
		if (embeddedConsole == null)
			return false;
		return embeddedConsole.isOnline();
	}

	public static Console embeddedConsole = null;
	//Controller is required to access the fxml element (GUI component) of the controller, they cannot be static (tried and it causes reflection
	//errors from the javafx internal code), so we must get to this controller to access those components.
	//Cannot declare those FXML element here as they must be within the controller in order to be initialized by the library, at outside
	//it would just be null as it never gets to be initialized.
	private MainController controller;

	public EmbeddedCLI(MainController controller) {
		this.controller = controller;
	}

	/*
	 * When it reaches this thread, all the javafx element should had already been initialized.
	 */

	@Override
	public void run() {
		//Startup the background CLI console in embedded mode.
		final String[] defaultConsoleArgs = new String[]{"config/ConsoleConfig.yml"};
		embeddedConsole = new Console(defaultConsoleArgs, true);
		final Thread thread = new Thread(embeddedConsole);
		thread.start();

		//Disable the GUI-CLI console wrapper until the CLI unit is online.
		new Thread(new Runnable() {
		    @Override
		    public void run() {
		    	Platform.runLater(new Runnable() {
					@Override
					public void run() {
						controller.cmd_pane.setDisable(true);
					}
		    	});

				System.out.println("Waiting for background CLI to boot up.");
				while (!embeddedConsole.isOnline() && !Main.isHalt()) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				if (!Main.isHalt()) {
					System.out.println("Background CLI startup success using config file: " + defaultConsoleArgs[0]);

			    	Platform.runLater(new Runnable() {
						@Override
						public void run() {
							controller.cmd_pane.setDisable(false);
							//Set its initials.
							String consoleInitial = EmbeddedCLI.embeddedConsole.getHostConsoleInitial();
							controller.cmdBtm_stn_input_consoleInitial_label_0.setText(consoleInitial);

							//Print the CLI setup notices to the command output screen.
							String commandOutput = "";
							while (commandOutput != null) {
								commandOutput = EmbeddedCLI.embeddedConsole.getOutput();
								if (commandOutput != null)
									controller.cmdBtm_stn_commandOutput_textArea_0.appendText("\n" + commandOutput);
							}
						}
			    	});
				}
				else {
					embeddedConsole.halt();
					System.out.println("Background CLI startup failure using config file: " + defaultConsoleArgs[0]);
				}
		    }
		}).start();

		//Setup event listener for when user pressed enter on the cli command text field it will send that command to the underlying CLI console.
		//http://stackoverflow.com/questions/13880638/how-do-i-pick-up-the-enter-key-being-pressed-in-javafx2
		controller.cmdBtm_stn_input_textField_0.setOnKeyPressed(new EventHandler<KeyEvent>() {
			@Override
			public void handle(KeyEvent ke) {
				if (ke.getCode().equals(KeyCode.ENTER)) {
					if (EmbeddedCLI.isOnline()) {
						//Send the command and empty the text field. Then update the console initial in case it changed and log it to the output screen.
						String command = controller.cmdBtm_stn_input_textField_0.getText();
						controller.cmdBtm_stn_input_textField_0.setText("");
						EmbeddedCLI.embeddedConsole.sendCommand(command);

						String consoleInitial = EmbeddedCLI.embeddedConsole.getHostConsoleInitial();
						controller.cmdBtm_stn_input_consoleInitial_label_0.setText(consoleInitial);

						//Append the just sent command to the screen.
						controller.cmdBtm_stn_commandOutput_textArea_0.appendText("\n" + consoleInitial + command);

						//Print the output to the command output screen.
						String commandOutput = "";
						while (commandOutput != null) {
							commandOutput = EmbeddedCLI.embeddedConsole.getOutput();
							if (commandOutput != null)
								controller.cmdBtm_stn_commandOutput_textArea_0.appendText("\n" + commandOutput);
						}
					}
					else {
						controller.cmdBtm_stn_inputSend_button_0.setDisable(true);
						throw new IllegalStateException("CLI not online but you clicked its send command button.");
					}
				}
			}
		});

		//Get the asyn command output log from the target node in CLI using logListener.
		new Thread(new Runnable() {
		    @Override
		    public void run() {
				while (!Main.isHalt()) {
					if (embeddedConsole.isOnline()) {
						final String nextLog = embeddedConsole.logListener.getNextLog();
						if (nextLog == "") {
							Util.sleep(1000);
							continue;
						}

				    	Platform.runLater(new Runnable() {
							@Override
							public void run() {
								//Append the log to the GUI console particular node output screen.
								controller.cmdBtm_stn_resultOutput_textArea_0.appendText("/n" + nextLog);
							}
				    	});
					}
					else
						Util.sleep(1000);
				}
		    }
		}).start();
	}
}
