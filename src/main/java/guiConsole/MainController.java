package guiConsole;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.orientechnologies.orient.client.remote.OServerAdmin;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.OServerMain;

import edu.uci.ics.jung.graph.util.EdgeType;
import guiConsole.VisualizationController.Type;
import guiConsole.VisualizationController.VisualizationGraph;
import isradatabase.Direction;
import isradatabase.Edge;
import isradatabase.Graph;
import isradatabase.GraphFactory;
import isradatabase.Vertex;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import utilities.Util;
import ymlDefine.YmlDefine.ConsoleConfig;
import ymlDefine.YmlDefine.DBCredentialConfig;

public class MainController {
	/*
	 * Naming convention is <panelHierarchyName>_<componentName>_<componentType>_<duplicateIdCount>
	 * -For panelHierarchyName, LHS means left hand side panel, the option panel,
	 * RHS means right hand side panel, the specific option panel, where sometimes contains more button and label integrated,
	 * for those you should bind those name together (path) into the whole name, separated by _,
	 * for those doesn't specify LHS or RHS, it means regard it as a whole, for panel usage only.
	 * -componentName is optional for those who regard things as a whole (without specifying LHS or RHS, panel) as it is not rational to specify
	 * a name for them (the hierarchy path is vivid enough to tell its location and purpose), name are only for those interactive component.
	 * -componentType is the base type of the object, for example button, check boxes, panel.
	 * main in name means main window, the most outer panel.
	 * duplicateIdCount is optional, for any element that can be duplicated, like tab.
	 */

	/*
	 * Defines all the panel switching elements, for scene switching purposes. Buttons and panes only.
	 */
	/**
	 * Main page tab buttons.
	 */
	@FXML
	private Button main_home_button, main_monitor_button, main_quit_button;

	/**
	 * Home LHS pane's static buttons for pane navigation.
	 */
	@FXML
	private Button homeLHS_status_button, homeLHS_preference_button, homeLHS_help_button, homeLHS_about_button;

	/**
	 * Monitor LHS pane's static buttons for pane navigation.
	 */
	@FXML
	private Button monitorLHS_overview_button, monitorLHS_nodeView_button, monitorLHS_taskView_button, monitorLHS_mindView_button,
	monitorLHS_nodeLog_button, monitorLHS_mindLog_button, monitorLHS_request_button;

	/**
	 * Main home pane visibility control.
	 */
	@FXML
	private HBox home_pane_hbox;

	/**
	 * Main monitor pane visibility control.
	 */
	@FXML
	private HBox monitor_pane_hbox;

	/**
	 * Home pane RHS panes visibility controls.
	 */
	@FXML
	private AnchorPane homeRHS_status_pane, homeRHS_preference_pane, homeRHS_help_pane, homeRHS_about_pane;

	/**
	 * Monitor pane RHS panes visibility controls.
	 */
	@FXML
	private AnchorPane monitorRHS_overview_pane, monitorRHS_nodeView_pane, monitorRHS_taskView_pane, monitorRHS_mindView_pane,
	monitorRHS_nodeLog_pane, monitorRHS_mindLog_pane, monitorRHS_request_pane;

	/*
	 * Enums to refer to particular pane.
	 */
	private static final class MainPane {
		public static final int HOME = 0;
		public static final int MONITOR = 1;
	}
	private static final class HomePane {
		public static final int STATUS = 0;
		public static final int PREFERENCE = 1;
		public static final int HELP = 2;
		public static final int ABOUT = 3;
	}
	private static final class MonitorPane {
		public static final int OVERVIEW = 0;
		public static final int NODEVIEW = 1;
		public static final int TASKVIEW = 2;
		public static final int MINDVIEW = 3;
		public static final int NODELOG = 4;
		public static final int MINDLOG = 5;
		public static final int REQUEST = 6;
	}

	/*
	 * Group all of them into a list in order to loop around them easily, only tabs of the same type are grouped together.
	 * Use it to set visibility of nodes.
	 * WARNING: ALL these must obey the order of its particular pane class's static final int ordering.
	 * Will be initialized in the FXML initialize function.
	 */
	private List<HBox> mainPanes = null;
	private List<AnchorPane> homePanes = null;
	private List<AnchorPane> monitorPanes = null;

	/**
	 * Set all the given panes to invisible.
	 */
	private void setPanesInvisibleHBox(List<HBox> givenPanes) {
		for (HBox pane: givenPanes)
			pane.setVisible(false);
	}
	private void setPanesInvisibleAnchorPane(List<AnchorPane> givenPanes) {
		for (AnchorPane pane: givenPanes)
			pane.setVisible(false);
	}

	/**
	 * Set the selected pane to be visible and make all other panes invisible.
	 * @param givenPanes The panes are needed to make them invisible and make only the selected pane visible.
	 * @param index The index MUST be from the particular static final pane's integer class's value. They are synchronized with the list's
	 * index, thus we will use that to select panes properly.
	 */
	private void setPaneVisibleHBox(List<HBox> givenPanes, int index) {
		setPanesInvisibleHBox(givenPanes);
		givenPanes.get(index).setVisible(true);
	}
	private void setPaneVisibleAnchorPane(List<AnchorPane> givenPanes, int index) {
		setPanesInvisibleAnchorPane(givenPanes);
		givenPanes.get(index).setVisible(true);
	}

	/*
	 * Individual pane switching controller below.
	 */
	public void setVisibleHomePane(ActionEvent event) {
		setPaneVisibleHBox(mainPanes, MainPane.HOME);
	}
	public void setVisibleMonitorPane(ActionEvent event) {
		setPaneVisibleHBox(mainPanes, MainPane.MONITOR);
	}

	public void setVisibleHomeStatusPane(ActionEvent event) {
		setPaneVisibleAnchorPane(homePanes, HomePane.STATUS);
	}
	public void setVisibleHomePreferencePane(ActionEvent event) {
		setPaneVisibleAnchorPane(homePanes, HomePane.PREFERENCE);
	}
	public void setVisibleHomeHelpPane(ActionEvent event) {
		setPaneVisibleAnchorPane(homePanes, HomePane.HELP);
	}
	public void setVisibleHomeAboutPane(ActionEvent event) {
		setPaneVisibleAnchorPane(homePanes, HomePane.ABOUT);
	}

	public void setVisibleMonitorOverviewPane(ActionEvent event) {
		setPaneVisibleAnchorPane(monitorPanes, MonitorPane.OVERVIEW);
	}
	public void setVisibleMonitorNodeViewPane(ActionEvent event) {
		setPaneVisibleAnchorPane(monitorPanes, MonitorPane.NODEVIEW);
	}
	public void setVisibleMonitorTaskViewPane(ActionEvent event) {
		setPaneVisibleAnchorPane(monitorPanes, MonitorPane.TASKVIEW);
	}
	public void setVisibleMonitorMindViewPane(ActionEvent event) {
		setPaneVisibleAnchorPane(monitorPanes, MonitorPane.MINDVIEW);
	}
	public void setVisibleMonitorNodeLogPane(ActionEvent event) {
		setPaneVisibleAnchorPane(monitorPanes, MonitorPane.NODELOG);
	}
	public void setVisibleMonitorMindLogPane(ActionEvent event) {
		setPaneVisibleAnchorPane(monitorPanes, MonitorPane.MINDLOG);
	}
	public void setVisibleMonitorRequestPane(ActionEvent event) {
		setPaneVisibleAnchorPane(monitorPanes, MonitorPane.REQUEST);
	}
	//End of pane switching logics.

	/**
	 * Quit Button logic. Platform exit to gracefully call the GUI thread to die, then it will call its own stop() function to clean up.
	 */
	public void mainQuit(ActionEvent event) {
		Platform.exit();
	}

	//-From here on all below will be individual page pane logic.
	//--Begin of GUI GCMD - CLI GCMD wrapper logic.
	@FXML
	public AnchorPane cmd_pane;
	@FXML
	public Button cmdBtm_stn_inputSend_button_0;
	@FXML
	public TextField cmdBtm_stn_input_textField_0;
	@FXML
	public TextArea cmdBtm_stn_commandOutput_textArea_0;
	@FXML
	public Label cmdBtm_stn_input_consoleInitial_label_0;
	//Result output is for logging the async output from the connected node, using logListener to get those log message under the hood.
	@FXML
	public TextArea cmdBtm_stn_resultOutput_textArea_0;

	public void cmdSendButton(ActionEvent event) {
		//Check whether the CLI itself is online or not before sending the command blindly.
		if (EmbeddedCLI.isOnline()) {
			//Send the command and empty the text field. Then update the console initial in case it changed and log it to the output screen.
			String command = cmdBtm_stn_input_textField_0.getText();
			cmdBtm_stn_input_textField_0.setText("");
			EmbeddedCLI.embeddedConsole.sendCommand(command);

			String consoleInitial = EmbeddedCLI.embeddedConsole.getHostConsoleInitial();
			cmdBtm_stn_input_consoleInitial_label_0.setText(consoleInitial);

			//Append the just sent command to the screen.
			cmdBtm_stn_commandOutput_textArea_0.appendText("\n" + consoleInitial + command);

			//Print the output to the command output screen.
			String commandOutput = "";
			while (commandOutput != null) {
				commandOutput = EmbeddedCLI.embeddedConsole.getOutput();
				if (commandOutput != null)
					cmdBtm_stn_commandOutput_textArea_0.appendText("\n" + commandOutput);
			}
		}
		else {
			cmdBtm_stn_inputSend_button_0.setDisable(true);
			throw new IllegalStateException("CLI not online but you clicked its send command button.");
		}
	}
	//The send using enter key is at the data logic class.
	//TODO: Multiple instance console not yet supported, now only 1 console available.
	//-->End of GUI GCMD - CLI GCMD wrapper logic.

	//--Begin of mindView logic.
	@FXML
	public ToggleButton monitorRHS_mindView_sensorFeed_toggleButton;
	/**
	 * Toggle sensor feed view on and off. Sensor has 3 component, visual, audio and all other sensor in numerical form.
	 * @param event
	 */
	private static Stage mindViewSensorFeedStage = null;
	private static VisualizationController sensorFeedController = null;
	public void mindViewSensorFeedToggle (ActionEvent event) {
		//Check if the view window is already online, if so just use it, else create it.
		if (mindViewSensorFeedStage == null) {
			try {
				FXMLLoader loader = new FXMLLoader(getClass().getResource("/guiConsole/MindView_Visualization.fxml"));

				Parent root = loader.load();
				Scene scene  = new Scene(root);
				scene.getStylesheets().add(getClass().getResource("MindView_sensorFeed.css").toExternalForm());
				final Stage newStage = new Stage();
				newStage.setTitle("MindView - Sensor Feed");
				newStage.setScene(scene);
				newStage.show();
				mindViewSensorFeedStage = newStage;
				sensorFeedController = (VisualizationController) loader.getController();
				sensorFeedController.setSVDataSource(true);

				//http://stackoverflow.com/questions/11000098/how-can-i-avoid-a-splitpane-to-resize-one-of-the-panes-when-the-window-resizes
				SplitPane.setResizableWithParent(sensorFeedController.mainGraphPane_pane_vBox, Boolean.FALSE);
				SplitPane.setResizableWithParent(sensorFeedController.s2dg_pane_vBox, Boolean.FALSE);
				SplitPane.setResizableWithParent(sensorFeedController.sv_pane_vBox, Boolean.FALSE);
				SplitPane.setResizableWithParent(sensorFeedController.tc_pane_vBox, Boolean.FALSE);
				SplitPane.setResizableWithParent(sensorFeedController.s2dgSV_resize_splitPane, Boolean.FALSE);

				sensorFeedController.main_resize_splitpane.setDividerPositions(0);
				sensorFeedController.s2dgSV_resize_splitPane.setDividerPositions(0);
				sensorFeedController.nonMainGraph_pane_splitPane.setDividerPositions(1);

				//Reset the stage to null during the view close so it can be recreated using this logic again and untoggle the button.
				newStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
					@Override
					public void handle(WindowEvent event) {
						if (mindViewSensorFeedStage != null) {
							mindViewSensorFeedStage.close();
							mindViewSensorFeedStage = null;
						}
						monitorRHS_mindView_sensorFeed_toggleButton.setSelected(false);

						//Call all the data operation threads to die.
						sensorFeedController.audioVisualSensorHalt();
					}
				});
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}

		//Check the button state and reset the toggle button state if needed, if all are down, kill the stage itself.
		//Also setup their respective window visibility and kill the data fetching thread if it is no longer visible.
		if (monitorRHS_mindView_sensorFeed_toggleButton.isSelected()) {
			sensorFeedController.sv_pane_vBox.setVisible(true);
		}
		else {
			sensorFeedController.sv_pane_vBox.setVisible(false);
			sensorFeedController.audioVisualSensorHalt();

			if (mindViewSensorFeedStage != null) {
				mindViewSensorFeedStage.close();
				mindViewSensorFeedStage = null;
				return;
			}
		}
		//Note that SV manage itself, it has its own integrated AV management logic and will be activated on its own site using its own
		//buttons, so we don't have to create the data threads here for him, he will do that on its own. He will also listen to its own
		//halt flag to halt all of its data logic thread. Its code is at visualization controller audioVisualSensorManage.
		//We need to provide him with data update if we doesn't specify him to listen to main timeline managed GCA rid (which only pure SV
		//data displayer do that, the sensor view, all other doesn't, so we must provide him with the data we want to visualize in vertex
		//form of any class).
	}

	@FXML
	public ToggleButton monitorRHS_mindView_globalDist_toggleButton;

	//Share state for the anonymous updating thread to call him to terminate if toggled close.
	private static final AtomicBoolean mindViewGlobalDistStatus = new AtomicBoolean(false);
	private static Stage mindViewGlobalDistStage = null;
	//Used to detect whether user has changed the timeline manually, so we can redraw the globalDist graph.
	private static AtomicLong lastTimeUserChangedTimelineManually = new AtomicLong(-1l);
	/**
	 * Toggle the view of globalDist on and off according to the selected view. Create an external view window and manage its life cycle
	 * in an anonymous thread until this the window is closed or the view toggle button released.
	 */
	public void mindViewGlobalDistToggle (ActionEvent event) {
		if (monitorRHS_mindView_globalDist_toggleButton.isSelected()) {
			mindViewGlobalDistStatus.set(true);

	        //defining the axes
	        final NumberAxis xAxis = new NumberAxis(0, 10000, 1000);
	        xAxis.setAutoRanging(false);
	        xAxis.setLabel("Millisecond");
	        final NumberAxis yAxis = new NumberAxis(0, 100, 10);
	        yAxis.setAutoRanging(false);
	        yAxis.setLabel("Percentile");
	        //creating the chart
	        final LineChart<Number, Number> globalDistLineChart = new LineChart<Number,Number>(xAxis,yAxis);

	        globalDistLineChart.setTitle("Global Distribution");
	        globalDistLineChart.setCreateSymbols(false);
	        globalDistLineChart.setAnimated(false);

			//Check if the view window is already online, if so just use it, else create it.
			if (mindViewGlobalDistStage == null) {
		        Scene scene  = new Scene(globalDistLineChart, 400, 400);
		        //Load the css to make the line stroke smaller, the default is too big and was obscure data.
				scene.getStylesheets().add(getClass().getResource("MindView_globalDist.css").toExternalForm());
				final Stage newStage = new Stage();
				newStage.setTitle("MindView - Global Distribution");
				newStage.setScene(scene);
				newStage.show();

				//Reset the stage to null during the view close so it can be recreated using this logic again and untoggle the button.
				newStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
					@Override
					public void handle(WindowEvent event) {
						if (mindViewGlobalDistStage != null) {
							mindViewGlobalDistStage.close();
							mindViewGlobalDistStage = null;
							mindViewGlobalDistStatus.set(false);
						}
						monitorRHS_mindView_globalDist_toggleButton.setSelected(false);
					}
				});

				mindViewGlobalDistStage = newStage;
			}

			//Run the updating logic in a separate thread.
			new Thread(new Runnable() {
			    @Override
			    public void run() {
			    	//While the toggle button is still pressed or the halt is set, keep fetch data from the DB.
			    	Graph txGraph = factory.getTx();

			    	//Store the currently displayed information.
			    	LinkedList<Double> polyValList = new LinkedList<Double>();
			    	LinkedList<Long> timestampList = new LinkedList<Long>();
			    	String lastProcessedRid = "";
			    	long lastTimestamp = lastTimeUserChangedTimelineManually.get();

			    	while (mindViewGlobalDistStatus.get() && !Main.isHalt()) {
			    		//Only run if play button is pressed, else just wait.
			    		if (!isPaused()) {
			    			//Check whether it has already been processed previously. If so just skip it and sleep some.
			    			String currentRid = getCurrentRid();

			    			if (currentRid.equals(lastProcessedRid)) {
			    				//Cannot guarantee when it will wake, but just simply sleep to avoid 100% cpu usage.
			    				Util.sleep(1);
			    				continue;
			    			}

			    			//Check whether user has changed the timeline manually, if so, reset the graph.
			    			if (lastTimestamp != lastTimeUserChangedTimelineManually.get()) {
			    				lastTimestamp = lastTimeUserChangedTimelineManually.get();
			    				polyValList = new LinkedList<Double>();
			    				timestampList = new LinkedList<Long>();
			    			}

			    			//Add in all the new data.
			    			lastProcessedRid = currentRid;
			    			Vertex currentVertex = Util.ridToVertex(currentRid, txGraph);
			    			polyValList.add( (Double)currentVertex.getProperty(LP.polyVal) );
			    			timestampList.add( (Long)currentVertex.getProperty(LP.timeStamp) );

			    			//If the sample size has exceeded 10 second, trim it so it remains in the 10 sec timeframe.
			    			while (timestampList.getLast() - timestampList.getFirst() > 10000) {
			    				polyValList.poll();
			    				timestampList.poll();
			    			}

			    			final XYChart.Series<Number, Number> series = new XYChart.Series<Number, Number>();
			    			ArrayList<XYChart.Data<Number, Number>> data = new ArrayList<XYChart.Data<Number, Number>>();

			    			//Create the graph with relative time passed so the first element will be pasted in as 0, and maximum is 10000 (10 sec)
			    			//instead of absolute time point (epoch time).
			    			long relativeStartTime = timestampList.getFirst();
			    			for (int i=0; i<polyValList.size(); i++) {
			    				data.add( new XYChart.Data<Number, Number>(timestampList.get(i) - relativeStartTime , polyValList.get(i) ) );
			    			}
			    			series.getData().addAll(data);

			    			//All the updating operation must be done on the GUI application thread else it will throw error.
			    			//Run later will run it later inside the application thread.
			    			//http://stackoverflow.com/questions/21083945/how-to-avoid-not-on-fx-application-thread-currentthread-javafx-application-th
			    			Platform.runLater(new Runnable() {
			    				@Override
			    				public void run() {
			    					globalDistLineChart.getData().clear();
			    					globalDistLineChart.getData().add(series);
			    				}
			    			});
			    		}
			    	}
			    	txGraph.shutdown();
			    }
			}).start();
		}

		//User retract the view, means they don't want to see this graph anymore, remove it from the external view by calling the showing
		//thread to halt and untoggle the button..
		else {
			if (mindViewGlobalDistStage != null) {
				mindViewGlobalDistStage.close();
				mindViewGlobalDistStage = null;
				mindViewGlobalDistStatus.set(false);
			}
			monitorRHS_mindView_globalDist_toggleButton.setSelected(false);
		}
	}

	//Decision Tree View:
	//Globally shared graph factory, will be set once data source is secured and will be shared among all data fetching thread to fetch
	//data from the connected DB (factory means connection to DB successful else it wouldn't exist).
	public static GraphFactory factory = null;

	/**
	 * Recursively traverse all and draw the graph of type main2D and selected2D graph only. (It traverse and draw all child with 'parent'
	 * edge to the given vertex).
	 * Should never be called directly!
	 * TODO: Is the graph share able like this or it must be shared using static reference?
	 * TODO: Currently color changing for selected path no supported as we cannot send additional data to the drawing function.
	 * @param graph Drawable graph.
	 */
	private void recursiveDecisionTreeGraphDrawInner(edu.uci.ics.jung.graph.Graph<String, String> graph, Vertex convergenceVertex, String parentRid) {
		String convergenceVertexClassName = convergenceVertex.getCName();

		ArrayList<Vertex> children = Util.traverse(convergenceVertex, Direction.IN, DBCN.E.parent);
		Iterable<Edge> childrenEdge = Util.traverseGetEdges(convergenceVertex, Direction.IN, DBCN.E.parent);

		int index = 0;
		for (Edge edge : childrenEdge) {
			Vertex child = children.get(index);
			String childRid = child.getRid();

			if (convergenceVertexClassName.equals(DBCN.V.general.convergenceMain.cn)) {
//				int selectedIndex = convergenceVertex.getProperty(LP.selectedSolutionIndex);
				//TODO: Change color.
			}
			else if (convergenceVertexClassName.equals(DBCN.V.general.convergenceSecondary.cn)) {
				//TODO: Change color
			}
			else
				throw new IllegalStateException("Unsupported vertex type: " + convergenceVertexClassName);

			graph.addVertex(childRid);
			//"" empty string means it is the head, thus no edge will be made.
			if (!parentRid.equals("")) {
				graph.addEdge(edge.getRid(), childRid, parentRid, EdgeType.DIRECTED);
			}

			recursiveDecisionTreeGraphDrawInner(graph, child, childRid);

			index++;
		}
	}

	/**
	 * Wrapper, so user don't have to provide convergenceVertex's rid.
	 * @param graph
	 * @param convergenceVertex
	 */
	private void recursiveDecisionTreeGraphDraw(VisualizationGraph graph, Vertex convergenceVertex, VisualizationController controller) {
		//Clean the previous graph.
		//https://sourceforge.net/p/jung/discussion/252062/thread/2f351861/
		while(graph.g.getVertexCount() != 0) {
			graph.g.removeVertex(graph.g.getVertices().iterator().next());
		}

		String convergenceVertexClassName = convergenceVertex.getCName();
		Vertex convertedVertex = null;
		//If convergence head convert it to normal convergence type of main or secondary as following logic expects that.
		if (convergenceVertexClassName.equals(DBCN.V.general.convergenceHead.cn)) {
			convertedVertex = Util.traverseOnce(convergenceVertex, Direction.OUT, DBCN.E.convergenceHead);
		}
		else
			convertedVertex = convergenceVertex;

		//Empty string means he is the head, thus only vertex is created and no edge is created as there is no another vertex to edge to.
		recursiveDecisionTreeGraphDrawInner(graph.g, convertedVertex, "");

		//http://stackoverflow.com/questions/6083623/redraw-graph-on-jung
		graph.vv.repaint();

		//Update the tree rid so user can know what tree they are in now.
		controller.updateCurrentTreeRidTextField(convertedVertex.getRid());
	}

	@FXML
	public ToggleButton monitorRHS_mindView_decisionTree_toggleButton;
	@FXML
	public ToggleButton monitorRHS_mindView_decisionTreeFSV_toggleButton;
	@FXML
	public ToggleButton monitorRHS_mindView_decisionTreeS2DG_toggleButton;

	//The visualization data fetching anonymous thread will watches these variable as their termination criteria.
	public static final AtomicBoolean DTMainWindowThreadStatus = new AtomicBoolean(false);
	public static final AtomicBoolean DTS2DGWindowThreadStatus = new AtomicBoolean(false);

	private static Stage mindViewDecisionTreeStage = null;
	private static VisualizationController decisionTreeController = null;
	/**
	 * Decision Tree shares a window for all of its view, they are added to its compartment when triggered, and separated by split window to
	 * adjust its size and view dynamically.
	 * This function should be shared for all 3 toggle button views.
	 */
	public void mindViewDecisionTreeToggle (ActionEvent event) {
		//Check if the view window is already online, if so just use it, else create it.
		if (mindViewDecisionTreeStage == null) {
			try {
				FXMLLoader loader = new FXMLLoader(getClass().getResource("/guiConsole/MindView_Visualization.fxml"));

				Parent root = loader.load();
				Scene scene  = new Scene(root);
				scene.getStylesheets().add(getClass().getResource("MindView_decisionTree.css").toExternalForm());
				final Stage newStage = new Stage();
				newStage.setTitle("MindView - Decision Tree");
				newStage.setScene(scene);
				newStage.show();
				mindViewDecisionTreeStage = newStage;
				decisionTreeController = (VisualizationController) loader.getController();
				decisionTreeController.setSVDataSource(false);

				//Reset the stage to null during the view close so it can be recreated using this logic again and untoggle the button.
				newStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
					@Override
					public void handle(WindowEvent event) {
						if (mindViewDecisionTreeStage != null) {
							mindViewDecisionTreeStage.close();
							mindViewDecisionTreeStage = null;
						}
						monitorRHS_mindView_decisionTree_toggleButton.setSelected(false);
						monitorRHS_mindView_decisionTreeFSV_toggleButton.setSelected(false);
						monitorRHS_mindView_decisionTreeS2DG_toggleButton.setSelected(false);

						//Call all the data operation threads to die.
						DTMainWindowThreadStatus.set(false);
						DTS2DGWindowThreadStatus.set(false);
						decisionTreeController.audioVisualSensorHalt();
					}
				});
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}

		//Check the button state and reset the toggle button state if needed, if all are down, kill the stage itself.
		//Also setup their respective window visibility and kill the data fetching thread if it is no longer visible.
		if (monitorRHS_mindView_decisionTree_toggleButton.isSelected())
			decisionTreeController.mainGraphPane_pane_vBox.setVisible(true);
		else {
			decisionTreeController.mainGraphPane_pane_vBox.setVisible(false);
			DTMainWindowThreadStatus.set(false);
		}

		if (monitorRHS_mindView_decisionTreeFSV_toggleButton.isSelected()) {
			decisionTreeController.sv_pane_vBox.setVisible(true);
		}
		else {
			decisionTreeController.sv_pane_vBox.setVisible(false);
			decisionTreeController.audioVisualSensorHalt();
		}

		if (monitorRHS_mindView_decisionTreeS2DG_toggleButton.isSelected())
			decisionTreeController.s2dg_pane_vBox.setVisible(true);
		else {
			decisionTreeController.s2dg_pane_vBox.setVisible(false);
			DTS2DGWindowThreadStatus.set(false);
		}

		//If both core element of the secondary window are not visible, then we hide the timeControl bar, as the timeControl bar is only
		//useful if any one of them is up.
		if (!monitorRHS_mindView_decisionTreeFSV_toggleButton.isSelected() && !monitorRHS_mindView_decisionTreeS2DG_toggleButton.isSelected())
			decisionTreeController.tc_pane_vBox.setVisible(false);
		else
			decisionTreeController.tc_pane_vBox.setVisible(true);

		//Close the window if all the views are disabled.
		if (!monitorRHS_mindView_decisionTree_toggleButton.isSelected() && !monitorRHS_mindView_decisionTreeFSV_toggleButton.isSelected()
				&& !monitorRHS_mindView_decisionTreeS2DG_toggleButton.isSelected()) {
			//Call all the data operation threads to die.
			DTMainWindowThreadStatus.set(false);
			DTS2DGWindowThreadStatus.set(false);
			decisionTreeController.audioVisualSensorHalt();

			if (mindViewDecisionTreeStage != null) {
				mindViewDecisionTreeStage.close();
				mindViewDecisionTreeStage = null;
				return;
			}
		}

		//Begin the update logic, 1 thread per view.
		//Both are strings as we treat them as rid. First is vertex, second is edge. Setup view at its correct reserved panel.
		if (!DTMainWindowThreadStatus.get() && monitorRHS_mindView_decisionTree_toggleButton.isSelected()) {
			DTMainWindowThreadStatus.set(true);
			final VisualizationGraph g = decisionTreeController.setupGraphView(Type.GRAPH2D_FULL);

			new Thread(new Runnable() {
			    @Override
			    public void run() {
    				Graph txGraph = factory.getTx();
    				long lastProcessedTimestamp = 0l;

    				while (DTMainWindowThreadStatus.get() && !Main.isHalt()) {
    					//Get the current decision tree.
    					/*
    					 * There is variation here, you may choose one of the parallel tree to view, the aggregated tree including or excluding
    					 * the in between competition transition, or simply all of them ordered by time so the tree keeps on changing.
    					 * Currently we employ the last solution (keep changing).
    					 */
    					String currentRid = getCurrentRid();
    					Vertex currentVertex = Util.ridToVertex(currentRid, txGraph);
    					long fetchedAbsoluteTime = currentVertex.getProperty(LP.timeStamp);

						//fetchedAbsoluteTime is essentially the absolute real time of that particular moment.
    					//If the current selected decision tree head is outdated, fetch a new tree.
    					//Identifiable by their timestamp difference.
    					if (fetchedAbsoluteTime > lastProcessedTimestamp) {
    						//Fetch the new tree by getting the closest entry to the given timeStamp, that timeStamp is from GCA not
    						//convergenceHead, thus has to be selected from reverse so it get the latest tree in relation to its time
    						//and also avoid tree that are too early by stating that it must be larger than the given tree.
    						Vertex nextDecisionTree = txGraph.directQueryExpectVertex("select from " + DBCN.V.general.convergenceHead.cn +
    								" where " + LP.timeStamp.toString() + " between " + lastProcessedTimestamp +
    								" and " + fetchedAbsoluteTime + " order by @rid desc limit 1").get(0);

    						//If entry exist, draw it out.
    						if (nextDecisionTree != null) {
    							fetchedAbsoluteTime = lastProcessedTimestamp;

    							//Clean then recursively draw all the vertexes and highlight the selected path.
    							recursiveDecisionTreeGraphDraw(g, nextDecisionTree, decisionTreeController);

    							//Update the SV source so he can update its view too.
    							decisionTreeController.setSVVisualizationData(nextDecisionTree);
    						}
	    				}
    					//Sleep abit to avoid full cpu usage, doesn't have to wake on time.
    					else
    						Util.sleep(1);
					}
			    }
			}).start();
		}
		else if (!DTS2DGWindowThreadStatus.get() && monitorRHS_mindView_decisionTreeS2DG_toggleButton.isSelected()) {
			DTS2DGWindowThreadStatus.set(true);
			final VisualizationGraph g = decisionTreeController.setupGraphView(Type.GRAPH2D_PARTIAL);

			new Thread(new Runnable() {
			    @Override
			    public void run() {
			    	Graph txGraph = factory.getTx();
			    	String previousSelectedVertexRid = "";

					while (DTS2DGWindowThreadStatus.get() && !Main.isHalt()) {
						//Get the currently selected vertex selected by user from main graph.
						String currentSelectedRid = getMainGraphSelectedVertexRid();
						//If it gives us an invalid rid, then we do nothing.
						if (currentSelectedRid == null || currentSelectedRid.equals("")) {
							Util.sleep(1);
							continue;
						}

						Vertex currentSelectedVertex = Util.ridToVertex(currentSelectedRid, txGraph);

						//Only redraw it if the data had changed or if it is first time.
						if (previousSelectedVertexRid.equals("") || !currentSelectedRid.equals(previousSelectedVertexRid)) {
							previousSelectedVertexRid = currentSelectedRid;

							//It can only be 2 type, convergenceMain or secondary, where only secondary contains the data we want to draw.
							//Recursively expand downward and display the remaining branch in a graph beginning from the specified point.
							recursiveDecisionTreeGraphDraw(g, currentSelectedVertex, decisionTreeController);
						}
					}
					txGraph.shutdown();
			    }
			}).start();
		}
		//Note that SV manage itself, it has its own integrated AV management logic and will be activated on its own site using its own
		//buttons, so we don't have to create the data threads here for him, he will do that on its own. He will also listen to its own
		//halt flag to halt all of its data logic thread. Its code is at visualization controller audioVisualSensorManage.
		//We need to provide him with data update if we doesn't specify him to listen to main timeline managed GCA rid (which only pure SV
		//data displayer do that, the sensor view, all other doesn't, so we must provide him with the data we want to visualize in vertex
		//form of any class).
	}

	@FXML
	private ToggleButton monitorRHS_mindView_prediction_toggleButton;
	@FXML
	private ToggleButton monitorRHS_mindView_predictionFSV_toggleButton;

	//The visualization data fetching anonymous thread will watches these variable as their termination criteria.
	public static final AtomicBoolean predictionMainWindowThreadStatus = new AtomicBoolean(false);

	private static Stage mindViewPredictionStage = null;
	private static VisualizationController predictionController = null;

	//Disabled in fxml, reenable it when your code is ready.
	public void mindViewPredictionToggle (ActionEvent event) {
		//Check if the view window is already online, if so just use it, else create it.
		if (mindViewPredictionStage == null) {
			try {
				FXMLLoader loader = new FXMLLoader(getClass().getResource("/guiConsole/MindView_Visualization.fxml"));

				Parent root = loader.load();
				Scene scene  = new Scene(root);
				scene.getStylesheets().add(getClass().getResource("MindView_prediction.css").toExternalForm());
				final Stage newStage = new Stage();
				newStage.setTitle("MindView - Prediction");
				newStage.setScene(scene);
				newStage.show();
				mindViewPredictionStage = newStage;
				predictionController = (VisualizationController) loader.getController();
				predictionController.setSVDataSource(false);

				predictionController.main_resize_splitpane.setDividerPositions(0);
				//http://stackoverflow.com/questions/11000098/how-can-i-avoid-a-splitpane-to-resize-one-of-the-panes-when-the-window-resizes
				SplitPane.setResizableWithParent(predictionController.mainGraphPane_pane_vBox, Boolean.FALSE);

				//Reset the stage to null during the view close so it can be recreated using this logic again and untoggle the button.
				newStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
					@Override
					public void handle(WindowEvent event) {
						if (mindViewPredictionStage != null) {
							mindViewPredictionStage.close();
							mindViewPredictionStage = null;
						}
						monitorRHS_mindView_prediction_toggleButton.setSelected(false);
						monitorRHS_mindView_predictionFSV_toggleButton.setSelected(false);

						//Call all the data operation threads to die.
						predictionMainWindowThreadStatus.set(false);
						predictionController.audioVisualSensorHalt();
					}
				});
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}

		//Check the button state and reset the toggle button state if needed, if all are down, kill the stage itself.
		//Also setup their respective window visibility and kill the data fetching thread if it is no longer visible.
		//Prediction's main is the selected 2D graph, it is derivative of a branch of the decision tree, thus it never uses the full graph
		//main feature, only decision tree uses that.
		if (monitorRHS_mindView_prediction_toggleButton.isSelected())
			predictionController.s2dg_pane_vBox.setVisible(true);
		else {
			predictionController.s2dg_pane_vBox.setVisible(false);
			predictionMainWindowThreadStatus.set(false);
		}

		if (monitorRHS_mindView_predictionFSV_toggleButton.isSelected()) {
			predictionController.sv_pane_vBox.setVisible(true);
		}
		else {
			predictionController.sv_pane_vBox.setVisible(false);
			predictionController.audioVisualSensorHalt();
		}
		//For Prediction, timeController bar should be always on.
		if (monitorRHS_mindView_prediction_toggleButton.isSelected() || monitorRHS_mindView_predictionFSV_toggleButton.isSelected())
			predictionController.tc_pane_vBox.setVisible(true);
		else
			predictionController.tc_pane_vBox.setVisible(false);

		//Close the window if all the views are disabled.
		if (!monitorRHS_mindView_prediction_toggleButton.isSelected() && !monitorRHS_mindView_predictionFSV_toggleButton.isSelected()) {
			//Call all the data operation threads to die.
			predictionMainWindowThreadStatus.set(false);
			predictionController.audioVisualSensorHalt();

			if (mindViewPredictionStage != null) {
				mindViewPredictionStage.close();
				mindViewPredictionStage = null;
				return;
			}
		}

		//Begin the update logic, 1 thread per view.
		//Both are strings as we treat them as rid. First is vertex, second is edge. Setup view at its correct reserved panel.
		//Prediction's main is the graph2d's partial view. Prediction never uses the graph2d's full as it contains only a branch, never like
		//a full neuron shaped (many branches) decision tree.
		if (!predictionMainWindowThreadStatus.get() && monitorRHS_mindView_prediction_toggleButton.isSelected()) {
			predictionMainWindowThreadStatus.set(true);
			final VisualizationGraph g = predictionController.setupGraphView(Type.GRAPH2D_PARTIAL);

			new Thread(new Runnable() {
			    @Override
			    public void run() {
					while (predictionMainWindowThreadStatus.get() && !Main.isHalt()) {
						/*
						 * Predictions are branch based, which means it is subset of decision tree, a branch of it, therefore visualization
						 * contains multiple snapshot of prediction at that time (predict to future, thus multiple possible outcome of future)
						 * but at here we visualize only the prediction selected outcome, thus it becomes a linear video of prediction of what
						 * is going to happen.
						 * Therefore prediction window itself is really redundant, but it frees you from selecting branches and
						 * restrict you to visualize other branches. Which is useless.
						 * Therefore not implemented for now as it is redundant.
						 */
						Util.sleep(2000);
					}
			    }
			}).start();
		}
		//Note that SV manage itself, it has its own integrated AV management logic and will be activated on its own site using its own
		//buttons, so we don't have to create the data threads here for him, he will do that on its own. He will also listen to its own
		//halt flag to halt all of its data logic thread. Its code is at visualization controller audioVisualSensorManage.
		//We need to provide him with data update if we doesn't specify him to listen to main timeline managed GCA rid (which only pure SV
		//data displayer do that, the sensor view, all other doesn't, so we must provide him with the data we want to visualize in vertex
		//form of any class).
	}

	//Disabled in fxml, reenable it when your code is ready.
	public void mindViewMindStructureToggle (ActionEvent event) {
		//TODO: This is the 3DG (3D graph implementation) entry. Not implemented now as we are bastard.
	}

	//Begin of data source input.
	//All the GCARid here is GCAMain Rid.
	//Uses direct main DB fetch with range within the start and end GCARid specified.
	@FXML
	private TextField monitorRHS_mindView_directFetchStartRid_textField, monitorRHS_mindView_directFetchEndRid_textField;
	@FXML
	private ToggleButton monitorRHS_mindView_directFetchValidate_toggleButton;

	//Uses latest real time data directly from main DB, with range within the starting GCARid specified and with no end.
	@FXML
	private TextField monitorRHS_mindView_latestDataFetchStartRid_textField;
	@FXML
	private ToggleButton monitorRHS_mindView_latestDataFetchValidate_toggleButton;

	//Uses data from the extracted DB, with range of the specified beginning GCARid and the ending GCARid.
	@FXML
	private TextField monitorRHS_mindView_fileViewPath_textField;
	@FXML
	private ToggleButton monitorRHS_mindView_fileViewValidate_toggleButton;
	@FXML
	private Button monitorRHS_mindView_fileViewBrowse_button;

	//An optional field to show the current status of the source validation progress.
	@FXML
	private TextField monitorRHS_mindView_sourceValidated_textField;

	@FXML
	public VBox monitorRHS_mindView_InputDataSource_vBox;

	//DB login paths and credentials.
	@FXML
	private TextField monitorRHS_mindView_DBPath_textField, monitorRHS_mindView_username_textField, monitorRHS_mindView_password_textField;

	//DB connected or not title bar word. Will be set after we had validated DB.
	@FXML
	public TextField main_dbStatus_textField;

	//Move the divider position to reveal the view selection when data source is successfully validated.
	@FXML
	private SplitPane monitorRHS_mindView_splitPane;

	//Timeline Min, Max and current Rid, timepoint and time.
	@FXML
	private TextField monitorRHS_mindView_timelineMinRid_textField, monitorRHS_mindView_timelineMinTimePoint_textField
	, monitorRHS_mindView_timelineMinTime_textField;
	@FXML
	private TextField monitorRHS_mindView_timelineMaxRid_textField, monitorRHS_mindView_timelineMaxTimePoint_textField
	, monitorRHS_mindView_timelineMaxTime_textField;
	@FXML
	private TextField monitorRHS_mindView_timelineCurrentRid_textField, monitorRHS_mindView_timelineCurrentTimePoint_textField
	, monitorRHS_mindView_timelineCurrentTime_textField;

	//Timeline vBox pane to disable them if they the user selected data source is real time.
	@FXML
	private HBox monitorRHS_mindView_timelineMax_HBox;

	//Timeline position selecting widgets.
	//Timeline set
	@FXML
	private Slider monitorRHS_mindView_timeline_slider;
	@FXML
	private TextField monitorRHS_mindView_timelineSetByRid_textField, monitorRHS_mindView_timelineSetByTimePoint_textField;

	//Timeline speed set.
	@FXML
	private TextField monitorRHS_mindView_timelinePlaySpeed_textField;

	//Timeline get
	@FXML
	private TextField monitorRHS_mindView_timelineGetRid_textField, monitorRHS_mindView_timelineGetTimePoint_textField
	, monitorRHS_mindView_timelineGetTime_textField;
	@FXML
	private Button monitorRHS_mindView_timelineGet_button;

	//Timeline play
	@FXML
	private ToggleButton monitorRHS_mindView_timelinePlay_toggleButton;

	//Globally shared variable, updated when user changed the speed of the simulation. This is used by the timeline headless thread
	//to synchronize its action. 1000 means 1 second, the default value.
	private static AtomicLong speedMillisecPerSec = new AtomicLong(1000);
	//Used to identify scrollbar value changed activity for the timeline thread to synchronize its actions.
	private static AtomicBoolean scrollbarValueChanged = new AtomicBoolean(false);
	private static AtomicLong scrollbarChangedToValue = new AtomicLong(-1);

	//Current running RID, to be shared among threads as we cannot fetch directly from the GUI component.
	private static String currentRid = null;
	private static final ReentrantLock currentRidLock = new ReentrantLock();
	private static void updateCurrentRid(String newCurrentRid) {
		currentRidLock.lock();
		currentRid = newCurrentRid;
		currentRidLock.unlock();
	}
	public static String getCurrentRid() {
		currentRidLock.lock();
		String result = currentRid;
		currentRidLock.unlock();
		if (result == null)
			return "";
		return result;
	}

	//The main graph selected rid to be ported to secondary graph (selected branch) to display it properly.
	private static String mainGraphSelectedVertexRid = null;
	private static final ReentrantLock mainGraphSelectedVertexRidLock = new ReentrantLock();
	private static String getMainGraphSelectedVertexRid() {
		mainGraphSelectedVertexRidLock.lock();
		String result = mainGraphSelectedVertexRid;
		mainGraphSelectedVertexRidLock.unlock();
		return result;
	}
	//Set by visualizationController thread, where when user select a vertex, it will be ported to here.
	public static void setMainGraphSelectedVertexRid(String selectedVertexRid) {
		mainGraphSelectedVertexRidLock.lock();
		mainGraphSelectedVertexRid = selectedVertexRid;
		mainGraphSelectedVertexRidLock.unlock();
	}

	//Disabled by default, contains all the view button access, should only be enabled if data source is secured.s
	@FXML
	public VBox monitorRHS_mindView_viewSelect_vBox;

	/**
	 * Will only be used if user uses data source from file (validate file).
	 * It will start the embedded DB server.
	 */
	private static OServer embeddedDBServerForFileValidateOnly = null;

	/**
	 * Shutdown the embedded DB when the main thread dies.
	 */
	public static void haltEmbeddedDBServer() {
		if (embeddedDBServerForFileValidateOnly != null)
			embeddedDBServerForFileValidateOnly.shutdown();
	}

	/**
	 * MindView DataSource Validation button reset.
	 * Use internally only.
	 */
	private void mindViewDataSourceValidationButtonReset() {
		//Re enable those buttons so user can retry.
		monitorRHS_mindView_directFetchValidate_toggleButton.setDisable(false);
		monitorRHS_mindView_latestDataFetchValidate_toggleButton.setDisable(false);
		monitorRHS_mindView_fileViewValidate_toggleButton.setDisable(false);

		monitorRHS_mindView_directFetchValidate_toggleButton.setSelected(false);
		monitorRHS_mindView_latestDataFetchValidate_toggleButton.setSelected(false);
		monitorRHS_mindView_fileViewValidate_toggleButton.setSelected(false);
	}

	//The absolute start and end time, for slider update when using set by value (rid and timepoint).
	private long absoluteStartTime = -1l;
	private long absoluteEndTime = -1l;

	/**
	 * All the data source validate button uses this function.
	 * @param event
	 */
	public void mindViewMindDataSourceValidateButton (ActionEvent event) {
		monitorRHS_mindView_sourceValidated_textField.setText("Processing");

		String currentLoginPath = monitorRHS_mindView_DBPath_textField.getText();
		String currentUsername = monitorRHS_mindView_username_textField.getText();
		String currentPassword = monitorRHS_mindView_password_textField.getText();

		//Make a list of them so user can supply a list of them to try each of them separately.
		ArrayList<String> currentLoginPathList = new ArrayList<String>();
		ArrayList<String> currentUsernameList = new ArrayList<String>();
		ArrayList<String> currentPasswordList = new ArrayList<String>();

		//Disable all the button.
		monitorRHS_mindView_directFetchValidate_toggleButton.setDisable(true);
		monitorRHS_mindView_latestDataFetchValidate_toggleButton.setDisable(true);
		monitorRHS_mindView_fileViewValidate_toggleButton.setDisable(true);

		//If the use hardcoded default value button is selected, we use the default value.
		if (monitorRHS_mindView_DBCredentialdefault_toggleButton.isSelected()) {
			//Load the config file then load DBCredential data file.
			//Read the default config file.
			try {
				YamlReader reader = new YamlReader(new FileReader("config/ConsoleConfig.yml"));
				ConsoleConfig config = reader.read(ConsoleConfig.class);
				DBCredentialConfig DBLoginCredential = Util.loadDBCredentialFromFile(config.DBCredentialConfigFilePath);

				//DBPath is unique server path, dbMode is static, always use 'remote' protocol, so it is reused. We here do the append work.
				for (String dbPath : DBLoginCredential.dbPath)
					currentLoginPathList.add(DBLoginCredential.dbMode + dbPath);
				currentUsernameList = DBLoginCredential.userName;
				currentPasswordList = DBLoginCredential.password;
			} catch (YamlException e) {
				monitorRHS_mindView_sourceValidated_textField.setText("False. Default Credential file 'config/ConsoleConfig.yml' corrupted.");
				throw new IllegalStateException("Default Credential file 'config/ConsoleConfig.yml' corrupted.", e);
			} catch (FileNotFoundException e) {
				monitorRHS_mindView_sourceValidated_textField.setText("False. Default Credential file 'config/ConsoleConfig.yml' not found.");
				throw new IllegalStateException("Default Credential file 'config/ConsoleConfig.yml' not found.", e);
			}
		}
		else {
			currentLoginPathList.add(currentLoginPath);
			currentUsernameList.add(currentUsername);
			currentPasswordList.add(currentPassword);
		}

		//TODO: Boot up the DB first. If they are main, then no booting is required, if from file then booting is required.
		if (monitorRHS_mindView_fileViewValidate_toggleButton.isSelected()) {
			//Run the physical server embedded in your application folder, start it up, then attempt to connect to it.
			//http://orientdb.com/docs/2.1/Embedded-Server.html
			try {
				embeddedDBServerForFileValidateOnly = OServerMain.create();
				//TODO: Provide adjustable name.
				embeddedDBServerForFileValidateOnly.startup(new File("/usr/local/temp/db.config"));
				embeddedDBServerForFileValidateOnly.activate();
			} catch (Exception e) {

				throw new IllegalStateException("Failed to start embedded DB.", e);
			}
		}

		//Try to login the database to see if the provided credentials are valid.
		//Connect to the DB specified by the user.
		System.out.println("Connecting to DB Server...");
		Graph txGraph = null;
		OServerAdmin serverAdmin = null;
		boolean dbConnectSuccess = false;
		String successLoginPath = null;
		for (int i=0; i<currentLoginPathList.size(); i++) {
			try {
				serverAdmin = new OServerAdmin(currentLoginPathList.get(i)).connect(currentUsernameList.get(i), currentPasswordList.get(i));
				if (serverAdmin.existsDatabase()) {
					GraphFactory factory = new GraphFactory(currentLoginPathList.get(i));
					factory.setAutoStartTx(false);
					factory.setupPool(0, 100);
					MainController.factory = factory;
					txGraph = factory.getTx();
					dbConnectSuccess = true;
					successLoginPath = currentLoginPathList.get(i);
				}
			}
			catch (Exception e) {
				System.out.println("Cannot connect to DB, original exception: " + Util.stackTraceToString(e));
			}
		}
		//If failed to connect to any of the servers, reset and return.
		if (!dbConnectSuccess) {
			monitorRHS_mindView_sourceValidated_textField.setText("False. DB login failed.");

			//Re enable those buttons so user can retry.
			mindViewDataSourceValidationButtonReset();
			return;
		}

		String sharedStartRid = null;
		String sharedEndRid = null;
		if (monitorRHS_mindView_directFetchValidate_toggleButton.isSelected()) {
			//Validate bounds.
			String startRid = monitorRHS_mindView_directFetchStartRid_textField.getText();
			String endRid = monitorRHS_mindView_directFetchEndRid_textField.getText();

			//Start RID cannot never be 0 as it is place holder set during DBHierarchy setup.
			try {
				if (Util.ridToInt(startRid) == 0) {
					System.out.println("Starting RID cannot be of 0 as 0 is placeholder.");
					monitorRHS_mindView_sourceValidated_textField.setText("False. Starting RID cannot be of 0 as 0 is placeholder. Use any > 0");
					mindViewDataSourceValidationButtonReset();
					return;
				}
			}
			catch (NumberFormatException e) {
				System.out.println("Starting RID cannot be of 0 as 0 is placeholder.");
				monitorRHS_mindView_sourceValidated_textField.setText("False. Starting RID Invalid format, should be #xxx:xxx where x is number.");
				mindViewDataSourceValidationButtonReset();
				return;
			}
			Vertex startVertex = null;
			Vertex endVertex = null;
			try {
				startVertex = Util.ridToVertex(startRid, txGraph);
				endVertex = Util.ridToVertex(endRid, txGraph);
			}
			catch (Exception e) {
				System.out.println("Vertex bound validation failed, original exception: " + Util.stackTraceToString(e));
				monitorRHS_mindView_sourceValidated_textField.setText("False. Vertex bound validation failed.");
				mindViewDataSourceValidationButtonReset();
				return;
			}

			//Check vertex type, it must be of type GCAMain.
			if (!startVertex.getCName().equals(DBCN.V.general.GCAMain.cn) || !endVertex.getCName().equals(DBCN.V.general.GCAMain.cn)) {
				System.out.println("Given Vertex RID is not of class type: " + DBCN.V.general.GCAMain.cn);
				monitorRHS_mindView_sourceValidated_textField.setText("False. Given Vertex RID is not of class type: " + DBCN.V.general.GCAMain.cn);
				mindViewDataSourceValidationButtonReset();
				return;
			}

			//Setup all the bounds at the timeline system.
			Long startTime = startVertex.getProperty(LP.timeStamp);
			monitorRHS_mindView_timelineMinRid_textField.setText(startRid);
			monitorRHS_mindView_timelineMinTimePoint_textField.setText(startTime.toString());
			monitorRHS_mindView_timelineMinTime_textField.setText( Util.epochMilliToReadable(startTime) );

			Long endTime = endVertex.getProperty(LP.timeStamp);
			monitorRHS_mindView_timelineMaxRid_textField.setText(endRid);
			monitorRHS_mindView_timelineMaxTimePoint_textField.setText(endTime.toString());
			monitorRHS_mindView_timelineMaxTime_textField.setText( Util.epochMilliToReadable(endTime) );

			//Current uses the exact same data of start vertex. Make sense to start from the beginning.
			monitorRHS_mindView_timelineCurrentRid_textField.setText(startRid);
			monitorRHS_mindView_timelineCurrentTimePoint_textField.setText(startTime.toString());
			monitorRHS_mindView_timelineCurrentTime_textField.setText( Util.epochMilliToReadable(startTime) );

			//Setup scrollbar range. It uses RID as range. Set its initial point to absolute start of the specified data range.
			monitorRHS_mindView_timeline_slider.setMin(startTime);
			monitorRHS_mindView_timeline_slider.setMax(endTime);
			monitorRHS_mindView_timeline_slider.setValue(startTime);

			absoluteStartTime = startTime;
			absoluteEndTime = endTime;

			sharedStartRid = startRid;
			sharedEndRid = endRid;
		}
		else if (monitorRHS_mindView_latestDataFetchValidate_toggleButton.isSelected()) {
			//Validate bounds.
			String startRid = monitorRHS_mindView_latestDataFetchStartRid_textField.getText();

			//Start RID cannot never be 0 as it is place holder set during DBHierarchy setup.
			if (Util.ridToInt(startRid) == 0) {
				System.out.println("Starting RID cannot be of 0 as 0 is placeholder.");
				monitorRHS_mindView_sourceValidated_textField.setText("False. Starting RID cannot be of 0 as 0 is placeholder. Use any > 0");
				mindViewDataSourceValidationButtonReset();
				return;
			}

			Vertex startVertex = null;
			try {
				startVertex = Util.ridToVertex(startRid, txGraph);
			}
			catch (Exception e) {
				System.out.println("Vertex bound validation failed, original exception: " + Util.stackTraceToString(e));
				monitorRHS_mindView_sourceValidated_textField.setText("False. Vertex bound validation failed.");
				mindViewDataSourceValidationButtonReset();
				return;
			}

			//Check vertex type, it must be of type GCAMain.
			if (!startVertex.getCName().equals(DBCN.V.general.GCAMain.cn)) {
				System.out.println("Given Vertex RID is not of class type: " + DBCN.V.general.GCAMain.cn);
				monitorRHS_mindView_sourceValidated_textField.setText("False. Given Vertex RID is not of class type: " + DBCN.V.general.GCAMain.cn);
				mindViewDataSourceValidationButtonReset();
				return;
			}

			//Setup all the bounds at the timeline system.
			Long startTime = startVertex.getProperty(LP.timeStamp);
			monitorRHS_mindView_timelineMinRid_textField.setText(startRid);
			monitorRHS_mindView_timelineMinTimePoint_textField.setText(startTime.toString());
			monitorRHS_mindView_timelineMinTime_textField.setText( Util.epochMilliToReadable(startTime) );

			//Current uses the exact same data of start vertex. Make sense to start from the beginning.
			monitorRHS_mindView_timelineCurrentRid_textField.setText(startRid);
			monitorRHS_mindView_timelineCurrentTimePoint_textField.setText(startTime.toString());
			monitorRHS_mindView_timelineCurrentTime_textField.setText( Util.epochMilliToReadable(startTime) );

			//Disable the maximum field as real time data has no upper boundary.
			monitorRHS_mindView_timelineMax_HBox.setDisable(true);
			//Disable the scrollbar too as it has no upper limit. Set it to some useless value.
			monitorRHS_mindView_timeline_slider.setMin(0);
			monitorRHS_mindView_timeline_slider.setMax(1);
			monitorRHS_mindView_timeline_slider.setValue(1);
			monitorRHS_mindView_timeline_slider.setDisable(true);

			sharedStartRid = startRid;
			sharedEndRid = null;

			//No end time.
			absoluteStartTime = startTime;
		}
		else if (monitorRHS_mindView_fileViewValidate_toggleButton.isSelected()) {
			//User file doesn't have to specify bound as the bound is the beginning and end of the vertexes contained in the file.

			//We must get the second GCA vertex, not the first (starting with #xxx:0 as it is placeholder set during DBHierarchy setup.
			Vertex startVertex = txGraph.getSecondVertexOfClass(DBCN.V.general.GCAMain.cn);
			Vertex endVertex = txGraph.getLatestVertexOfClass(DBCN.V.general.GCAMain.cn);
			final String startRid = startVertex.getRid();
			final String endRid = endVertex.getRid();

			//Setup all the bounds at the timeline system.
			Long startTime = startVertex.getProperty(LP.timeStamp);
			monitorRHS_mindView_timelineMinRid_textField.setText(startRid);
			monitorRHS_mindView_timelineMinTimePoint_textField.setText(startTime.toString());
			monitorRHS_mindView_timelineMinTime_textField.setText( Util.epochMilliToReadable(startTime) );

			Long endTime = endVertex.getProperty(LP.timeStamp);
			monitorRHS_mindView_timelineMaxRid_textField.setText(endRid);
			monitorRHS_mindView_timelineMaxTimePoint_textField.setText(endTime.toString());
			monitorRHS_mindView_timelineMaxTime_textField.setText( Util.epochMilliToReadable(endTime) );

			//Current uses the exact same data of start vertex. Make sense to start from the beginning.
			monitorRHS_mindView_timelineCurrentRid_textField.setText(startRid);
			monitorRHS_mindView_timelineCurrentTimePoint_textField.setText(startTime.toString());
			monitorRHS_mindView_timelineCurrentTime_textField.setText( Util.epochMilliToReadable(startTime) );

			//Setup scrollbar range. It uses timePoint as range. Set its initial point to absolute start of the specified data range.
			monitorRHS_mindView_timeline_slider.setMin(startTime);
			monitorRHS_mindView_timeline_slider.setMax(endTime);
			monitorRHS_mindView_timeline_slider.setValue(startTime);

			sharedStartRid = startRid;
			sharedEndRid = endRid;

			absoluteStartTime = startTime;
			absoluteEndTime = endTime;
		}

		//Set the notification text to true and disable the whole data input window.
		monitorRHS_mindView_sourceValidated_textField.setText("True");
		monitorRHS_mindView_InputDataSource_vBox.setDisable(true);

		//Set the status bar of DB to connected by showing the connected DB credential.
		main_dbStatus_textField.setText(successLoginPath);

		//Reveal the view selection window.
		monitorRHS_mindView_splitPane.setDividerPositions(1d);

		//Make another final variable in order to port it into the thread.
		final String finalSharedStartRid = sharedStartRid;
		final String finalSharedEndRid = sharedEndRid;
		//Setup the timeline thread to update the current time point and send it to the DataFaucet to synchronize all data fetching system.
		new Thread(new Runnable() {
		    @Override
		    public void run() {
		    	Graph txGraph = factory.getTx();

		    	final Vertex startVertex = Util.ridToVertex(finalSharedStartRid, txGraph);
		    	final Vertex endVertex = Util.ridToVertex(finalSharedEndRid, txGraph);
		    	final long timeStampStartTime = startVertex.getProperty(LP.timeStamp);
		    	final long timeStampEndTime = endVertex.getProperty(LP.timeStamp);

		    	//TODO: In the future fetch on demand.
		    	//Query and get all the vertexes within the timestamp.
		    	//Example format: SELECT FROM GCAMain WHERE timeStamp BETWEEN startTimeStamp and endTimeStamp
		    	//http://orientdb.com/docs/2.0/orientdb.wiki/SQL-Where.html
		    	ArrayList<Vertex> vertexSample = txGraph.directQueryExpectVertex("SELECT FROM " + DBCN.V.general.GCAMain.cn + " WHERE "
						+ LP.timeStamp.toString() + " BETWEEN " + timeStampStartTime + " AND " + timeStampEndTime);

		    	//As the DB may cluster it into multiple class for write efficiency, referencing directly using only RID is unsafe.
		    	//Use absolute timepoint instead.
		    	final ArrayList<String> ridSample = new ArrayList<String>(vertexSample.size());
		    	final ArrayList<Long> timeSample = new ArrayList<Long>(vertexSample.size());

		    	for (Vertex v : vertexSample) {
		    		ridSample.add(v.getRid());
		    		timeSample.add( (Long)v.getProperty(LP.timeStamp) );
		    	}

		    	int lastProcessedIndex = 0;
		    	while (!Main.isHalt()) {
		    		//Only run if play button is pressed, else just wait.
		    		if (!isPaused()) {
		    			long currentSpeed = speedMillisecPerSec.get();

		    			//Extract 1 second of data and calculate its relative offset against the speed.
		    			int indexBegin = lastProcessedIndex;
		    			int indexEnd = -1;
		    			long beginTime = timeSample.get(indexBegin);
		    			for (int i=lastProcessedIndex; i<timeSample.size(); i++) {
		    				if (timeSample.get(i) - beginTime >= 1000) {
		    					indexEnd = i;
		    					break;
		    				}
		    			}

		    			//Calculate the absolute time to be run after factoring in the speed.
		    			long currentTime = System.currentTimeMillis();
		    			ArrayList<Long> absoluteTime = new ArrayList<Long>();
		    			for (int i=indexBegin; i<indexEnd; i++) {
		    				//offset * speed / second; absolute run time = curTime + result.
		    				//1000ms, 45877 - 46000 = 123. 123 * 1000 / 1000 = 123; absolute run time = curTime + 123;
		    				//800ms,  45877 - 46000 = 123. 123 * 800 / 1000 = 98.4 ~ 98; absolute run time = curTime + 98;
		    				//Relative time range is within 0~1000, aka a second.
		    				long relativeTime = timeSample.get(i) - beginTime;
		    				absoluteTime.add( currentTime + Math.round( ((double)(relativeTime * currentSpeed)) / 1000d ) );
		    			}

		    			//i start from relative begin, for further down references to actual data uses. j start from 0, for fetching calculated
		    			//list absoluteTime uses as it start from 0.
		    			for (int i=indexBegin, j=0; i<indexEnd && j<absoluteTime.size(); i++, j++) {
		    				//Keep process and if not yet time, wait. If scrollbar being modified, stop, if play button deactivated, stop.
		    				//Or if user manually changed the timeline value via direct input, stop.
		    				while ( !(Main.isHalt() || scrollbarValueChanged.get() || isPaused()
		    						|| mindViewTimelineRidSetChanged.get() || mindViewTimelineTimepointSetChanged.get()) ) {
		    					if (System.currentTimeMillis() >= absoluteTime.get(j)) {
		    						final int finalIndex = i;
		    						Platform.runLater(new Runnable() {
		    							@Override
		    							public void run() {
		    								monitorRHS_mindView_timelineCurrentRid_textField.setText(ridSample.get(finalIndex));
		    								monitorRHS_mindView_timelineCurrentTimePoint_textField.setText( Long.toString( timeSample.get(finalIndex) ) );
		    								monitorRHS_mindView_timelineCurrentTime_textField.setText( Util.epochMilliToReadable(timeSample.get(finalIndex)) );
		    								monitorRHS_mindView_timeline_slider.setValue(timeSample.get(finalIndex));
		    								//Update the latest rid to shared static variable to share among threads.
		    								updateCurrentRid(ridSample.get(finalIndex));
		    								//here doesn't need lastTimeUserChangedTimelineManually.set(System.currentTimeMillis()); as this
		    								//is changed by system, not by user.
		    							}
		    						});
		    						lastProcessedIndex++;
		    						break;
		    					}
		    					else {
		    						//Sleep for half the time before reaching the next absolute time. Only sleep if it is not too far away.
		    						long sleepTime = (absoluteTime.get(j) - System.currentTimeMillis()) / 2;
		    						if (sleepTime > 200)
		    							Util.sleep(sleepTime);
		    					}
		    				}

		    				if (Main.isHalt() || scrollbarValueChanged.get() || isPaused()
		    						|| mindViewTimelineRidSetChanged.get() || mindViewTimelineTimepointSetChanged.get()) {
		    					break;
		    				}
		    			}

		    			/**
		    			 * If user changed the scrollbar value, reflect it here in the current timeline textField and update the index.
		    			 */
		    			if (scrollbarValueChanged.get()) {
		    				//Approximate the value to closest valid value.
		    				long currentScrollbarValue = scrollbarChangedToValue.get();
		    				int newIndex = -1;
		    				for (int i=0; i<timeSample.size(); i++) {
		    					if (timeSample.get(i) > currentScrollbarValue) {
		    						newIndex = i;
		    						break;
		    					}
		    				}
		    				//If we failed to find any closest value larger than the selected value, it means it is the last value as no value
		    				//can be larger than him.
		    				if (newIndex == -1)
		    					newIndex = timeSample.size() - 1;

		    				//Update the last processed index in order to switch the progress to the proper location. Final int copy again to port into
		    				//the thread.
		    				lastProcessedIndex = newIndex;
		    				final int finalNewIndex = newIndex;

		    				Platform.runLater(new Runnable() {
		    					@Override
		    					public void run() {
		    						monitorRHS_mindView_timelineCurrentRid_textField.setText(ridSample.get(finalNewIndex));
		    						monitorRHS_mindView_timelineCurrentTimePoint_textField.setText( Long.toString( timeSample.get(finalNewIndex) ) );
		    						monitorRHS_mindView_timelineCurrentTime_textField.setText( Util.epochMilliToReadable(timeSample.get(finalNewIndex)) );
		    						//Set the scrollBar to the correct valid value.
		    						monitorRHS_mindView_timeline_slider.setValue(timeSample.get(finalNewIndex));
		    						//Update the latest rid to shared static variable to share among threads.
		    						updateCurrentRid(ridSample.get(finalNewIndex));
		    						lastTimeUserChangedTimelineManually.set(System.currentTimeMillis());
		    					}
		    				});

		    				//Reset it.
		    				scrollbarValueChanged.set(false);
		    			}

		    			//If user has updated the timeline via direct input of type RID, update it accordingly here.
		    			if (mindViewTimelineRidSetChanged.get()) {
		    				mindViewTimelineRidSetChanged.set(false);
		    				String userSetNewRid = getMindViewTimelineSetChangedValue();
		    				final int indexOfUserRid = ridSample.indexOf(userSetNewRid);
		    				if (indexOfUserRid != -1) {
		    					lastProcessedIndex = indexOfUserRid;

			    				Platform.runLater(new Runnable() {
			    					@Override
			    					public void run() {
			    						monitorRHS_mindView_timelineCurrentRid_textField.setText(ridSample.get(indexOfUserRid));
			    						monitorRHS_mindView_timelineCurrentTimePoint_textField.setText( Long.toString( timeSample.get(indexOfUserRid) ) );
			    						monitorRHS_mindView_timelineCurrentTime_textField.setText( Util.epochMilliToReadable(timeSample.get(indexOfUserRid)) );
			    						//Set the scrollBar to the correct valid value.
			    						monitorRHS_mindView_timeline_slider.setValue(timeSample.get(indexOfUserRid));
			    						//Update the latest rid to shared static variable to share among threads.
			    						updateCurrentRid(ridSample.get(indexOfUserRid));
			    						lastTimeUserChangedTimelineManually.set(System.currentTimeMillis());
			    					}
			    				});
		    				}
		    			}

		    			//If user has updated the timeline via direct input of type timepoint, update it accordingly here.
		    			if (mindViewTimelineTimepointSetChanged.get()) {
		    				mindViewTimelineTimepointSetChanged.set(false);
		    				long userSetTimepoint = mindViewTimelineTimepointSetChangedValue.get();

		    				//Check whether it is within bound.
		    				//If it is within absolute boundary of time, or if we are using real time, thus has no ending boundary (-1).
		    				if ( (userSetTimepoint >= absoluteStartTime && userSetTimepoint <= absoluteEndTime)
		    						|| (userSetTimepoint >= absoluteStartTime && absoluteEndTime == -1l) ) {
		    					//Get the index we are currently in by checking if the time is latter than us, he is the one.
		    					int indexOfUserTimepoint = -1;
		    					for (int i=0; i<timeSample.size(); i++) {
		    						if (timeSample.get(i) >= userSetTimepoint) {
		    							indexOfUserTimepoint = i;
		    							break;
		    						}
		    					}
		    					//If not found, means he is using latest or is the last.
		    					if (indexOfUserTimepoint == -1) {
		    						indexOfUserTimepoint = timeSample.size()-1;
		    					}

		    					lastProcessedIndex = indexOfUserTimepoint;
		    					final int finalIndex = indexOfUserTimepoint;
			    				Platform.runLater(new Runnable() {
			    					@Override
			    					public void run() {
			    						monitorRHS_mindView_timelineCurrentRid_textField.setText(ridSample.get(finalIndex));
			    						monitorRHS_mindView_timelineCurrentTimePoint_textField.setText( Long.toString( timeSample.get(finalIndex) ) );
			    						monitorRHS_mindView_timelineCurrentTime_textField.setText( Util.epochMilliToReadable(timeSample.get(finalIndex)) );
			    						//Set the scrollBar to the correct valid value.
			    						monitorRHS_mindView_timeline_slider.setValue(timeSample.get(finalIndex));
			    						//Update the latest rid to shared static variable to share among threads.
			    						updateCurrentRid(ridSample.get(finalIndex));
			    						lastTimeUserChangedTimelineManually.set(System.currentTimeMillis());
			    					}
			    				});
		    				}
		    			}

		    		}
		    		else
		    			Util.sleep(500);
		    	}
		    }
		}).start();

		//Enable the view select panel after the source has been validated.
		//Rationale is that the views requires the data source to be secured in order to have data to show to us.
		monitorRHS_mindView_viewSelect_vBox.setDisable(false);
	}

	/**
	 * Browse file and set data source.
	 * @param event
	 */
	public void mindViewMindDataSourceBrowseButton (ActionEvent event) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select File");
		//http://stackoverflow.com/questions/25491732/how-do-i-open-the-javafx-filechooser-from-a-controller-class
		File file = chooser.showOpenDialog(monitorRHS_mindView_fileViewBrowse_button.getScene().getWindow());
		monitorRHS_mindView_fileViewPath_textField.setText(file.getAbsolutePath());
	}

	/**
	 * Slider update value calls this, then we will notify timeline thread to updates its operation.
	 * This bitch cannot have ActionEvent as parameter else it will throw IllegalArgumentException.
	 * @param event
	 */
	public void mindViewMindTimeSelectSlider () {
		scrollbarValueChanged.set(true);
		scrollbarChangedToValue.set( Math.round( monitorRHS_mindView_timeline_slider.getValue()) );
	}

	/**
	 * Get the current running value.
	 * @param event
	 */
	public void mindViewTimelineGetButton(ActionEvent event) {
		monitorRHS_mindView_timelineGetRid_textField.setText(monitorRHS_mindView_timelineCurrentRid_textField.getText());
		monitorRHS_mindView_timelineGetTimePoint_textField.setText(monitorRHS_mindView_timelineCurrentTimePoint_textField.getText());
		monitorRHS_mindView_timelineGetTime_textField.setText(monitorRHS_mindView_timelineCurrentTime_textField.getText());
	}

	/**
	 * Translate the user input into internal shared static variable so the timing will be updated accordingly.
	 */
	public void mindViewTimelineTimingSet() {
		long originalNum = speedMillisecPerSec.get();
		try {
			//If no error then update it to the given number. Else reset it to the original number.
			long toBeSetNum = Long.parseLong(monitorRHS_mindView_timelinePlaySpeed_textField.getText());

			if (toBeSetNum < 0)
				monitorRHS_mindView_timelinePlaySpeed_textField.setText(Long.toString(originalNum));
			else {
				monitorRHS_mindView_timelinePlaySpeed_textField.setText(Long.toString(toBeSetNum));
				speedMillisecPerSec.set(toBeSetNum);
			}
		} catch (NumberFormatException e) {
			monitorRHS_mindView_timelinePlaySpeed_textField.setText(Long.toString(originalNum));
		}
	}

	//Update whole timeline by RID, set the flag and main timeline thread will process it.
	private AtomicBoolean mindViewTimelineRidSetChanged = new AtomicBoolean(false);
	private String mindViewTimelineRidSetChangedValue = "";
	private static final ReentrantLock mindViewTimelineRidSetChangedLock = new ReentrantLock();

	private String getMindViewTimelineSetChangedValue() {
		mindViewTimelineRidSetChangedLock.lock();
		String result = mindViewTimelineRidSetChangedValue;
		mindViewTimelineRidSetChangedLock.unlock();
		return result;
	}
	public void mindViewTimelineSetByRidTextField() {
		//Set the flag and the main timeline update thread will read it.
		mindViewTimelineRidSetChangedLock.lock();
		mindViewTimelineRidSetChangedValue = monitorRHS_mindView_timelineSetByRid_textField.getText();
		mindViewTimelineRidSetChangedLock.unlock();
		monitorRHS_mindView_timelineSetByRid_textField.setText("");
		mindViewTimelineRidSetChanged.set(true);
	}

	//Update whole timeline by timepoint, set the flag and main timeline thread will process it.
	private AtomicBoolean mindViewTimelineTimepointSetChanged = new AtomicBoolean(false);
	private AtomicLong mindViewTimelineTimepointSetChangedValue = new AtomicLong(-1);
	public void mindViewTimelineSetByTimePointTextField() {
		try {
			long timepointNew = Long.parseLong(monitorRHS_mindView_timelineSetByTimePoint_textField.getText());
			monitorRHS_mindView_timelineSetByTimePoint_textField.setText("");
			mindViewTimelineTimepointSetChangedValue.set(timepointNew);
			mindViewTimelineTimepointSetChanged.set(true);
		}
		catch (NumberFormatException e) {
			monitorRHS_mindView_timelineSetByTimePoint_textField.setText("");
		}
	}

	private static AtomicBoolean isPaused = new AtomicBoolean(true);
	public static boolean isPaused() {
		return isPaused.get();
	}
	public void mindViewPlayButtonToggle(ActionEvent event) {
		if (monitorRHS_mindView_timelinePlay_toggleButton.isSelected()) {
			isPaused.set(false);
		}
		else {
			isPaused.set(true);
		}
	}

	@FXML
	private ToggleButton monitorRHS_mindView_DBCredentialdefault_toggleButton;
	public void mindViewDBUseDefaultCredential(ActionEvent event) {
		//If selected we disable those input textField and set it with our default data.
		if (monitorRHS_mindView_DBCredentialdefault_toggleButton.isSelected()) {
			monitorRHS_mindView_DBPath_textField.setDisable(true);
			monitorRHS_mindView_username_textField.setDisable(true);
			monitorRHS_mindView_password_textField.setDisable(true);
		}
		else {
			monitorRHS_mindView_DBPath_textField.setDisable(false);
			monitorRHS_mindView_username_textField.setDisable(false);
			monitorRHS_mindView_password_textField.setDisable(false);
		}
	}
	//-->End of mindView logic.

	@FXML
	public void initialize() {
		//We got to know the existence of initialize method here.
		//We cannot do static initialization as it will cause NPE as at the static moment, all the FXML element is not initialized.
		//http://stackoverflow.com/questions/34785417/javafx-fxml-controller-constructor-vs-initialize-method
		mainPanes = Arrays.asList(home_pane_hbox, monitor_pane_hbox);
		homePanes = Arrays.asList(homeRHS_status_pane, homeRHS_preference_pane, homeRHS_help_pane, homeRHS_about_pane);
		monitorPanes = Arrays.asList(monitorRHS_overview_pane, monitorRHS_nodeView_pane, monitorRHS_taskView_pane,
				monitorRHS_mindView_pane, monitorRHS_nodeLog_pane, monitorRHS_mindLog_pane, monitorRHS_request_pane);
	}
}
