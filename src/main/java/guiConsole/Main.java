package guiConsole;

import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * Direct wrapper to CLI console without modifying any code of the CLI model, will invoke a hidden thread of the original CLI in background,
 * then pass all the user input to the CLI directly and returns the output with optional GUI based modeling by transforming them into more
 * graphical representation of data, for example drawing graph and charts.
 * CLI is not responsible to draw any charts, as if you are using CLI you can't expect to draw anything anyway.
 * Thus only the GUI version has the capability to call external graphing tools, and CLI provides us with the data.
 */


public class Main extends Application {
	//All anonymous thread listen to this halt to halt themselves.
	private static AtomicBoolean halt = new AtomicBoolean(false);
	public static boolean isHalt() {
		return halt.get();
	}

	@Override
	public void start(Stage primaryStage) {
		try {
			//Need a loader to get the controller instance in order to access all those GUI elements. FXML element only be declared
			//within the controller class, define elsewhere it simply will not be initialized.
			FXMLLoader loader = new FXMLLoader(getClass().getResource("/guiConsole/Main.fxml"));
			Parent root = loader.load();

			Scene scene = new Scene(root);
			scene.getStylesheets().add(getClass().getResource("guiConsole.css").toExternalForm());
			primaryStage.setTitle("GCMD MINIMAL V0");
			primaryStage.setScene(scene);
			primaryStage.show();

			//Call GUI thread to terminate if the main window terminate, which will also propagate to other child windows, making them die as well.
			primaryStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
			    @Override
			    public void handle(WindowEvent event) {
			    	Platform.exit();
			    }
			});

			MainController controller = (MainController) loader.getController();

			//Start background CLI.
			Thread thread = new Thread(new EmbeddedCLI(controller));
			thread.start();

			//Lock some screen to avoid it to be auto resized.
			SplitPane.setResizableWithParent(controller.monitorRHS_mindView_viewSelect_vBox, Boolean.FALSE);
			SplitPane.setResizableWithParent(controller.monitorRHS_mindView_InputDataSource_vBox, Boolean.FALSE);

		} catch(Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void stop() {
		System.out.println("GUI thread shutting down.");
		System.out.println("Telling all thread to die...");
		halt.set(true);
		//Kill the embedded console.
		if (EmbeddedCLI.embeddedConsole != null) {
			EmbeddedCLI.embeddedConsole.halt();
		}

		//Kill the embedded DB.
		MainController.haltEmbeddedDBServer();
	}

	public static void main(String[] args) {
		launch(args);
	}
}
