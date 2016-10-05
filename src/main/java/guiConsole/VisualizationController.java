package guiConsole;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.apache.commons.collections15.Transformer;
import org.apache.commons.collections15.functors.ConstantTransformer;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;

import edu.uci.ics.jung.algorithms.layout.CircleLayout;
import edu.uci.ics.jung.algorithms.layout.Layout;
import edu.uci.ics.jung.graph.SparseMultigraph;
import edu.uci.ics.jung.visualization.VisualizationViewer;
import edu.uci.ics.jung.visualization.control.DefaultModalGraphMouse;
import edu.uci.ics.jung.visualization.picking.PickedInfo;
import edu.uci.ics.jung.visualization.renderers.DefaultVertexLabelRenderer;
import edu.uci.ics.jung.visualization.renderers.Renderer.VertexLabel.Position;
import isradatabase.Direction;
import isradatabase.Graph;
import isradatabase.Vertex;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.embed.swing.SwingNode;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import stm.LTM;
import sun.audio.AudioData;
import sun.audio.AudioDataStream;
import sun.audio.AudioPlayer;
import utilities.Util;

/**
 * Visualization logic that are shared among all mindView operation that requires DB visualization, partial DB visualization and raw data
 * playback using audio, visual and all sensor.
 * For displaying and selecting node code.
 * http://stackoverflow.com/questions/13747139/displaying-selected-node-name-in-jung
 *
 * TODO: We are too late to discover this lib, use this in the future. http://graphstream-project.org/
 */
public class VisualizationController {
	//Only S2DG and its variant uses opencv to stitch images, this is used to avoid double import.
	//TODO: Make the path dynamically modifiable.
	private boolean opencvLoaded = false;
	private String opencvJarPath = "";

	public static enum Type {
		GRAPH2D_FULL, GRAPH2D_PARTIAL, SENSOR_PLAYBACK
	}

	//The current selected vertex's rid, there are 2 window, 1 main(mainGraph) 1 secondary(extracted graph from mainGraph).
	@FXML
	private TextField mainGraphPane_selectedRid_textField, s2dg_selectedRid_textField;

	//Used to get and translate class type. Class type first string (#123), Class name in second string (GCAMain)
	private static final TreeMap<String, String> registeredClass = new TreeMap<String, String>();

	//Unsure how it works too, just extract and modified what we need and it works.
	public class VertexPaintTransformer implements Transformer<String,Paint> {

		private final PickedInfo<String> pi;

		VertexPaintTransformer ( PickedInfo<String> pi ) {
			super();
			if (pi == null)
				throw new IllegalArgumentException("PickedInfo instance must be non-null");
			this.pi = pi;
		}

		@Override
		public Paint transform(String i) {
			//Get the class name if it is not yet registered, as a class may have dynamic additional class initial, we cannot hardcode it.
			String className = registeredClass.get(Util.ridGetClassInitial(i));
			//If not available, determine its type and register it.
			if (className == null) {
				Graph txGraph = MainController.factory.getTx();
				className = Util.ridToVertex(i, txGraph).getCName();
				registeredClass.put(Util.ridGetClassInitial(i), className);
			}

			//Default color.
			Color p = Color.red;

			//Specific color for specific class.
			if (className.equals(DBCN.V.general.convergenceMain.cn))
				p = Color.cyan;
			else if (className.equals(DBCN.V.general.convergenceSecondary.cn))
				p = Color.orange;

			if (pi.isPicked(i))
				p = Color.yellow;
			return p;
		}
	}

	public class VertexLabelTransformer implements Transformer<String, String> {
		//These are used to synchronize RID selection and make sure we don't run too make runLater instance if the
		//previous runLater has not returned yet. We do not check whether it is the same or not as we uses both edge and vertex within
		//1 textField, thus if both of the are selected as the same, it will not get updated, thus causing a undesirable behavior that
		//you selected edge, but both the selected edge and vertex are the same last time, thus the edge will not be displayed if you
		//check whether they are the same in order to same runLater cycle.
		private String mainGraphPaneCurrentSelectedRid = "";
		private String secondaryGraphPaneCurrentSelectedRid = "";
		private final AtomicBoolean mainGraphSelectedRidUpdateDone = new AtomicBoolean(true);
		private final AtomicBoolean secondaryGraphSelectedRidUpdateDone = new AtomicBoolean(true);

		private final PickedInfo<String> pi;
		private boolean isMainGraph;

		public VertexLabelTransformer(PickedInfo<String> pi, boolean isMainGraph) {
			this.pi = pi;
			this.isMainGraph = isMainGraph;
		}

		@Override
		public String transform(String t) {
			if (pi.isPicked(t)) {
				//Check what type it is, main is the big main graph, secondary is the extracted graph from main.
				//If the previous updating operation is done, update the rid textField.
				String rid = t.toString();

				if (isMainGraph && mainGraphSelectedRidUpdateDone.get()) {
					mainGraphPaneCurrentSelectedRid = rid;
					mainGraphSelectedRidUpdateDone.set(false);
					//Set the newly selected vertex to the main controller so it can setup and update the new selected graph (S2DG) properly.
					MainController.setMainGraphSelectedVertexRid(mainGraphPaneCurrentSelectedRid);

					Platform.runLater(new Runnable() {
		    			@Override
		    			public void run() {
							mainGraphPane_selectedRid_textField.setText(mainGraphPaneCurrentSelectedRid);
							mainGraphSelectedRidUpdateDone.set(true);
		    			}
		    		});
				}
				else if (!isMainGraph && secondaryGraphSelectedRidUpdateDone.get()) {
					secondaryGraphPaneCurrentSelectedRid = rid;
					secondaryGraphSelectedRidUpdateDone.set(false);
		    		Platform.runLater(new Runnable() {
		    			@Override
		    			public void run() {
		    				s2dg_selectedRid_textField.setText(secondaryGraphPaneCurrentSelectedRid);
		    				secondaryGraphSelectedRidUpdateDone.set(true);
		    			}
		    		});
				}
				return rid;
			}
			else
				return "";
		}
	}

	@FXML
	public AnchorPane mainGraphPane_draw_anchorPane, s2dg_draw_anchorPane;

	/**
	 * Wrapper class to wrap all required component we need into 1 object.
	 */
	public class VisualizationGraph {
		edu.uci.ics.jung.graph.Graph<String, String> g;
		VisualizationViewer<String, String> vv;

		VisualizationGraph(edu.uci.ics.jung.graph.Graph<String, String> g, VisualizationViewer<String, String> vv) {
			this.g = g;
			this.vv = vv;
		}
	}

	@FXML
	private TextField mainGraphPane_currentTreeRid_textField;
	//Called by main, used to update current tree rid to inform user what tree we are in now.
	public void updateCurrentTreeRidTextField(final String newTreeRid) {
		Platform.runLater(new Runnable() {
			@Override
			public void run() {
				mainGraphPane_currentTreeRid_textField.setText(newTreeRid);
			}
		});
	}

	//These graph mouse instance are used to set the mouse operation (select/transform). Will be initialized during its particular view creation.
	//MainGraph is the main big LHS graph, secondary graph is the selected part extracted and focused graph.
	private DefaultModalGraphMouse<String, String> mainGraphMouse = null;
	private DefaultModalGraphMouse<String, String> secondaryGraphMouse = null;
	/**
	 * Setup a view at the reserved place and display it, then return a graph for user for update, it will reflect the update in the graph.
	 * @param type The type of view to be setup. Every view has different configuration.
	 * @param additionalConfig Optional configuration object.
	 * @return
	 */
	public VisualizationGraph setupGraphView (Type type, Object additionalConfig) {
		edu.uci.ics.jung.graph.Graph<String, String> graph = new SparseMultigraph<String, String>();

		if (type == Type.GRAPH2D_FULL || type == Type.GRAPH2D_PARTIAL) {
			//SpringLayout is directed graph, the movable animated graph.
			//Layout<String, String> layout = new SpringLayout<String, String>(graph);
			Layout<String, String> layout = new CircleLayout<String, String>(graph);
			final VisualizationViewer<String, String> vv = new VisualizationViewer<String, String>(layout);

			vv.getRenderContext().setVertexLabelRenderer(new DefaultVertexLabelRenderer(Color.green));
			//Both vertex and edge uses the same transformer as internally it just get and returns the RID of the selected record.
			//The true and false indicate whether they are main or secondary, so the selected rid get printed to the correct textField.
			if (type == Type.GRAPH2D_FULL) {
				vv.getRenderContext().setVertexLabelTransformer(new VisualizationController.VertexLabelTransformer(vv.getPickedVertexState(), true));
				vv.getRenderContext().setEdgeLabelTransformer(new VisualizationController.VertexLabelTransformer(vv.getPickedEdgeState(), true));
			}
			else {
				vv.getRenderContext().setVertexLabelTransformer(new VisualizationController.VertexLabelTransformer(vv.getPickedVertexState(), false));
				vv.getRenderContext().setEdgeLabelTransformer(new VisualizationController.VertexLabelTransformer(vv.getPickedEdgeState(), false));
			}
			vv.getRenderContext().setVertexFillPaintTransformer(new VisualizationController.VertexPaintTransformer(vv.getPickedVertexState()));
			vv.getRenderContext().setEdgeDrawPaintTransformer(new VisualizationController.VertexPaintTransformer(vv.getPickedEdgeState()));

			vv.getRenderContext().setEdgeDrawPaintTransformer(new ConstantTransformer(Color.white));
			vv.getRenderContext().setEdgeStrokeTransformer(new ConstantTransformer(new BasicStroke(1f)));
			vv.getRenderer().getVertexLabelRenderer().setPosition(Position.CNTR);

			//https://stackoverflow.com/questions/37740057/jung-large-graph-visualization
			vv.getRenderingHints().remove(
					RenderingHints.KEY_ANTIALIASING);

			DefaultModalGraphMouse<String, String> graphMouse = new DefaultModalGraphMouse<String, String>();
			graphMouse.setMode(edu.uci.ics.jung.visualization.control.ModalGraphMouse.Mode.PICKING);
			vv.setGraphMouse(graphMouse);

			//http://docs.oracle.com/javase/8/javafx/api/javafx/embed/swing/SwingNode.html
			final SwingNode swingNode = new SwingNode();
			SwingUtilities.invokeLater(new Runnable() {
				@Override
				public void run() {
					swingNode.setContent(vv);
				}
			});

			if (type == Type.GRAPH2D_FULL) {
				//Reset the mouse toggle button, previous RID textField and useless previous swingNode.
				mainGraphPane_draw_anchorPane.getChildren().clear();
				mainGraphPane_transform_toggleButton.setSelected(false);
				mainGraphPane_selectedRid_textField.setText("");

				mainGraphPane_draw_anchorPane.getChildren().add(swingNode);
				mainGraphMouse = graphMouse;
			}
			else {
				//Reset the mouse toggle button, previous RID textField and useless previous swingNode.
				s2dg_transform_toggleButton.setSelected(false);
				s2dg_draw_anchorPane.getChildren().clear();
				s2dg_selectedRid_textField.setText("");

				s2dg_draw_anchorPane.getChildren().add(swingNode);
				secondaryGraphMouse = graphMouse;
			}
			return new VisualizationGraph(graph, vv);
		}
		throw new IllegalStateException("Unsupported operation type: " + type);
	}

	public VisualizationGraph setupGraphView (Type type) {
		return setupGraphView(type, null);
	}

	//Toggle buttons for changing the mouse behavior. Only 1 can be active at any time. Those mouse instance may be null if their respective
	//window is not open or initializing.
	@FXML
	public ToggleButton mainGraphPane_transform_toggleButton, s2dg_transform_toggleButton;

	public void mainMouseTransform(ActionEvent event) {
		if (mainGraphMouse == null)
			mainGraphPane_transform_toggleButton.setSelected(false);
		else {
			if (mainGraphPane_transform_toggleButton.isSelected())
				mainGraphMouse.setMode(edu.uci.ics.jung.visualization.control.ModalGraphMouse.Mode.TRANSFORMING);
			else
				mainGraphMouse.setMode(edu.uci.ics.jung.visualization.control.ModalGraphMouse.Mode.PICKING);
		}
	}

	public void secondaryMouseTransform(ActionEvent event) {
		if (secondaryGraphMouse == null)
			s2dg_transform_toggleButton.setSelected(false);
		else {
			if (s2dg_transform_toggleButton.isSelected())
				secondaryGraphMouse.setMode(edu.uci.ics.jung.visualization.control.ModalGraphMouse.Mode.TRANSFORMING);
			else
				secondaryGraphMouse.setMode(edu.uci.ics.jung.visualization.control.ModalGraphMouse.Mode.PICKING);
		}
	}

	@FXML
	private ToggleButton tc_timePointPlay_toggleButton;
	//The shared is paused variable synchronized with the pause button.
	private AtomicBoolean isPausedSV = new AtomicBoolean(true);
	public void playToggleButton(ActionEvent event) {
		if (tc_timePointPlay_toggleButton.isSelected())
			isPausedSV.set(false);
		else
			isPausedSV.set(true);
	}

	@FXML
	private Slider tc_timePoint_slider;
	private boolean isAllSensorVisualizationByMainTimeLineGCARid = true;
	/**
	 * Setup the data source, if is SV by main timeline, then we will fetch data from the timeline shared GCA rid in order to synchronize
	 * actions, else we will treat it as exp based (embedded version), where the user thread must also feed in the data into here in order
	 * to be processed (it will not get data on its own as the source is variable and unknown).
	 * @param isAllSensorVisualizationByMainTimeLineGCARid
	 */
	public void setSVDataSource(boolean isAllSensorVisualizationByMainTimeLineGCARid) {
		this.isAllSensorVisualizationByMainTimeLineGCARid = isAllSensorVisualizationByMainTimeLineGCARid;

		//If not pure SV by main timeline fetch (use directed data, prediction set by user selected vertexes).
		//Then start a management thread to manage the timeline and all of its functionality.
		if (!isAllSensorVisualizationByMainTimeLineGCARid) {
			new Thread(new Runnable() {
			    @Override
			    public void run() {
			    	//If all worker thread is dead or the main thread is dead, then we quit.
			    	while ( (!audioThreadHalt.get() && !visualThreadHalt.get() && !sensorThreadHalt.get()) && !Main.isHalt()) {
				    	//Move forward the timeline when all of the operation for that particular depth is completed.
				    	if (visualTaskCompleted.get() && audioTaskCompleted.get() && sensorTaskCompleted.get()) {
							//Reset them so it can be set to true again by individual worker thread.
							visualTaskCompleted.set(false);
							audioTaskCompleted.set(false);
							sensorTaskCompleted.set(false);

							Platform.runLater(new Runnable() {
								@Override
								public void run() {
									//Setup the new slider property and text field.
									int currentValue = (int)tc_timePoint_slider.getValue();
									int maxValue = (int)tc_timePoint_slider.getMax();

									//If they are smaller than the max value then increment the slider value by 1.
									//Eg max 10; cur 5 become 6; cur 9 become 10; cur 10 becomes 10.
									if (currentValue < maxValue) {
										tc_timePoint_slider.setValue(currentValue + 1);
										tc_depthCurrent_textField.setText(Integer.toString(currentValue + 1));
									}
								}
							});
				    	}
				    	else
				    		Util.sleep(1);
			    	}
			    }
			}).start();
		}
	}

	private static final ReentrantLock dataVertexesFromTreeLock = new ReentrantLock();
	private ArrayList< ArrayList<Vertex> > dataVertexesFromTree = null;
	/**
	 * This method should never be called directly, call its wrapper class (the one without the initial inner) instead.
	 * @param treeNode
	 */
	private void innerRecursiveTraverseAndGetAllDataVertexOfTree(Vertex treeNode, int depth) {
		//Create new depth by adding a new arraylist if the specified depth doesn't yet exist.
		if (depth >= dataVertexesFromTree.size()) {
			dataVertexesFromTreeLock.lock();
			//Example cur 3, size 1. It will end when cur 3, size 4. So we can now go from 0~3 index.
			while (dataVertexesFromTree.size() <= depth)
				dataVertexesFromTree.add(new ArrayList<Vertex>());
			dataVertexesFromTreeLock.unlock();
		}

		//Traverse and get the data nodes from the tree.
		dataVertexesFromTreeLock.lock();
		dataVertexesFromTree.get(depth).add(Util.traverseOnce(treeNode, Direction.OUT, DBCN.E.data));
		dataVertexesFromTreeLock.unlock();

		ArrayList<Vertex> childConvergences = Util.traverse(treeNode, Direction.IN, DBCN.E.parent);
		for (Vertex v : childConvergences)
			innerRecursiveTraverseAndGetAllDataVertexOfTree(v, depth + 1);
	}
	/**
	 * Wrapper for the inner function, in order to encapsulate initialization and synchronization process.
	 * @param treeNode
	 * @return
	 */
	private ArrayList< ArrayList<Vertex> > recursiveTraverseAndGetAllDataVertexOfTree(Vertex treeNode) {
		dataVertexesFromTree = new ArrayList< ArrayList<Vertex> >();
		innerRecursiveTraverseAndGetAllDataVertexOfTree(treeNode, 0);
		return dataVertexesFromTree;
	}

	//The data vertex that main thread must update it in case they want to update the SV view.
	//Integer is the depth of the data currently resides in.
	private TreeMap<Integer ,byte[]> visualSVDataFull = null;
	//Long is absolute run time during its creation, byte[] is the actual audio data.
	private TreeMap<Integer, TreeMap<Long, byte[]> > audioSVDataFull = null;
	private TreeMap<Integer, SensorData> sensorSVDataFull = null;
	private String lastProcessedVertexRid = "";
	private static final ReentrantLock SVVisualizationDataLock = new ReentrantLock();

	//Visual textfield to let user know the boundary.
	@FXML
	private TextField tc_timePointMin_textField, tc_timePointMax_textField;

	/**
	 * Setup visualization data based on the decision tree (prediction branch data). Each convergence vertex is given an instance in each
	 * of those data bearing treemaps to compartmentalize them so we can implement the timeline slider correctly.
	 * @param SVVisualizationDataVertex
	 */
	public void setSVVisualizationData(Vertex SVVisualizationDataVertex) {
		SVVisualizationDataLock.lock();
		//If they are the same do nothing.
		String givenVisualizationDataVertexRid = SVVisualizationDataVertex.getRid();
		if (lastProcessedVertexRid.equals(givenVisualizationDataVertexRid)) {
			return;
		}
		//Else initialize everything and begin deciphering the given tree.
		visualSVDataFull = new TreeMap<Integer ,byte[]>();
		audioSVDataFull = new TreeMap<Integer, TreeMap<Long, byte[]> >();
		sensorSVDataFull = new TreeMap<Integer, SensorData>();

		lastProcessedVertexRid = givenVisualizationDataVertexRid;

		//Translate them into visual, visualICL, audio, audioICL and sensor 5 type.
		//Given vertex can be of type convergenceMain and convergenceSecondary and head only.
		//GCAMain type each individual data thread handles on their own.
		Vertex secondaryConvergence = null;
		if ( SVVisualizationDataVertex.getCName().equals(DBCN.V.general.convergenceMain.cn) ) {
			//Convert it to secondaryConvergence by getting its child, if not available, then skip it.
			try {
				secondaryConvergence = Util.traverseOnce(SVVisualizationDataVertex, Direction.IN, DBCN.E.parent);
			}
			catch (IllegalArgumentException e) {
				System.out.println("No more child available, original message:" + e);
				SVVisualizationDataLock.unlock();
				return;
			}
		}
		else if ( SVVisualizationDataVertex.getCName().equals(DBCN.V.general.convergenceSecondary.cn) ) {
			secondaryConvergence = SVVisualizationDataVertex;
		}
		else if ( SVVisualizationDataVertex.getCName().equals(DBCN.V.general.convergenceHead.cn) ) {
			//Head has edge to the main convergence tree's head, which is a convergence main, traverse from there once more to get its secondary.
			Vertex convergenceMain = Util.traverseOnce(SVVisualizationDataVertex, Direction.OUT, DBCN.E.convergenceHead);
			secondaryConvergence = Util.traverseOnce(convergenceMain, Direction.IN, DBCN.E.parent);
		}
		else
			throw new IllegalStateException("Unsuported type: " + SVVisualizationDataVertex.getCName());

		ArrayList< ArrayList<Vertex> > actualDataVertexesListFull = recursiveTraverseAndGetAllDataVertexOfTree(secondaryConvergence);
		for (int currentDepth=0; currentDepth<actualDataVertexesListFull.size(); currentDepth++) {
			ArrayList<Vertex> visualDataVertexList = new ArrayList<Vertex>();
			ArrayList<Vertex> audioDataVertexList = new ArrayList<Vertex>();
			ArrayList<Vertex> sensorDataVertexList = new ArrayList<Vertex>();

			ArrayList<Vertex> actualDataVertexes = actualDataVertexesListFull.get(currentDepth);
			for (Vertex v : actualDataVertexes) {
				//Combine visual and audio ICL pattern into a singular visible component so user can make sense out of it.
				if (v.getCName().equals(DBCN.V.general.rawDataICL.visual.cn)) {
					visualDataVertexList.add(v);
				}
				else if (v.getCName().equals(DBCN.V.general.rawDataICL.audio.cn)) {
					audioDataVertexList.add(v);
				}
				else if (Util.equalAny(v.getCName(), LTM.MOVEMENT)) {
					sensorDataVertexList.add(v);
				}
			}

			//Load the native file if not yet loaded. Required for any opencv operation.
			if (!opencvLoaded) {
				opencvLoaded = true;
				if (opencvJarPath.equals(""))
					System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
				else
					System.load(opencvJarPath);
			}
			//Re aggregate them into a singular representation for display purposes.
			//Allow user to specify aggregation time frame if they are unable to make sense of what they are looking at at real time.
			//TODO: DO NOT hardcode image size as in future it will be variable size.
			Mat output = new Mat(1280, 720, CvType.CV_8UC3, new Scalar(0, 0, 0));
			for (int i=0; i<visualDataVertexList.size(); i++) {
				Vertex visualDataVertex = visualDataVertexList.get(i);
				Mat visualPattern = ICL.ICL.Visual.visualVertexToMat(visualDataVertex);
				//http://stackoverflow.com/questions/10991523/opencv-draw-an-image-over-another-image
				//http://stackoverflow.com/questions/29275254/error-when-using-cvmat-submat-opencv
				//Create a region of interest at the output image, then draw into it.
				Rect roi = new Rect(new Point( (int)visualDataVertex.getProperty(LP.imgX), (int)visualDataVertex.getProperty(LP.imgY)), visualPattern.size());
				visualPattern.copyTo( output.submat(roi) );
			}
			//Convert it to byte array in jpg form for display.
			MatOfByte bytemat = new MatOfByte();
			Imgcodecs.imencode(".jpg", output, bytemat);
			visualSVDataFull.put(currentDepth, bytemat.toArray());


			//Audio data rearrange and play them in order.
			TreeMap<Long, byte[]> audioOutput = new TreeMap<Long, byte[]>();
			for (Vertex v : audioDataVertexList) {
				byte[] audioData = ICL.ICL.Audio.audioVertexToByteArray(v);
				//Audio absolute time stamp, set during its creation.
				audioOutput.put( (Long)v.getProperty(LP.audioAbsTimestamp), audioData);
			}
			audioSVDataFull.put(currentDepth, audioOutput);

			//Sensor data, -1 if no update which had already been done for us in its constructor.
			SensorData sensorOutput = new SensorData();
			//Traverse to each of the data and select only the general type of what we wanted.
			for (Vertex v : sensorDataVertexList) {
				String vertexClass = v.getCName();
				if (vertexClass.equals(DBCN.V.general.rawData.POFeedback.dev.motor1.cn))
					sensorOutput.flm = Util.traverseGetDataField(v, Direction.IN, DBCN.E.data, LP.data, DBCN.V.LTM.rawData.POFeedback.dev.motor1.cn);
				else if (vertexClass.equals(DBCN.V.general.rawData.POFeedback.dev.motor2.cn))
					sensorOutput.frm = Util.traverseGetDataField(v, Direction.IN, DBCN.E.data, LP.data, DBCN.V.LTM.rawData.POFeedback.dev.motor2.cn);
				else if (vertexClass.equals(DBCN.V.general.rawData.POFeedback.dev.motor3.cn))
					sensorOutput.blm = Util.traverseGetDataField(v, Direction.IN, DBCN.E.data, LP.data, DBCN.V.LTM.rawData.POFeedback.dev.motor3.cn);
				else if (vertexClass.equals(DBCN.V.general.rawData.POFeedback.dev.motor4.cn))
					sensorOutput.brm = Util.traverseGetDataField(v, Direction.IN, DBCN.E.data, LP.data, DBCN.V.LTM.rawData.POFeedback.dev.motor4.cn);
			}
			sensorSVDataFull.put(currentDepth, sensorOutput);
		}
		SVVisualizationDataLock.unlock();

		//-1 to get the absolute index that start from 0.
		final int maxDataVertexIndex = actualDataVertexesListFull.size() - 1;
		Platform.runLater(new Runnable() {
			@Override
			public void run() {
				//Setup the new slider property and text field.
				tc_timePoint_slider.setMin(0);
				tc_timePoint_slider.setMax(maxDataVertexIndex);
				tc_timePointMin_textField.setText("0");
				tc_timePointMax_textField.setText(Integer.toString(maxDataVertexIndex));
			}
		});
	}

	private AtomicInteger currentDepthShared = new AtomicInteger(0);
	@FXML
	private TextField tc_depth_textField, tc_depthCurrent_textField;
	/**
	 * Call by fxml when text field being modified.
	 */
	public void timelineValueUpdateTextField() {
		String userInput = tc_depth_textField.getText();
		//Reset the text.
		tc_depth_textField.setText("");
		int depth = Integer.parseInt(userInput);
		//If out of bound then do nothing.
		if (depth < 0 || depth > tc_timePoint_slider.getMax()) {
			return;
		}
		else {
			tc_timePoint_slider.setValue(depth);
			tc_depthCurrent_textField.setText(userInput);
			currentDepthShared.set(depth);

			//Reset them so it can be set to true again by individual worker thread.
			visualTaskCompleted.set(false);
			audioTaskCompleted.set(false);
			sensorTaskCompleted.set(false);
		}
	}

	/**
	 * Called when slider value changes. Update the textfield and shared value accordingly.
	 */
	public void timelineSliderValueChanged() {
		int currentValue = (int)tc_timePoint_slider.getValue();
		tc_depthCurrent_textField.setText(Integer.toString(currentValue));
		currentDepthShared.set(currentValue);

		//Reset them so it can be set to true again by individual worker thread.
		visualTaskCompleted.set(false);
		audioTaskCompleted.set(false);
		sensorTaskCompleted.set(false);
	}

	//Get visual audio and sensor aggregated data ONLY if the user has specify they are not real full sensor read (read directly from GCA),
	//these data are fed back by the mainController based on the tree they had selected.
	//These get are also responsible to manage the internal SV timeline so all of them got to synchronize and simplify displaying code.
	//Return null if no data has changed. All of them get from the shared value managed by headless internal timeline (not the global one) thread.
	private byte[] getSVVisualData() {
		//Follow current timeline depth.
		SVVisualizationDataLock.lock();
		byte[] result = visualSVDataFull.get(currentDepthShared.get());
		SVVisualizationDataLock.unlock();
		return result;
	}
	private TreeMap<Long, byte[]> getSVAudioData() {
		SVVisualizationDataLock.lock();
		TreeMap<Long, byte[]> result = audioSVDataFull.get(currentDepthShared.get());
		SVVisualizationDataLock.unlock();
		return result;
	}
	private SensorData getSVSensorData() {
		SVVisualizationDataLock.lock();
		SensorData result = sensorSVDataFull.get(currentDepthShared.get());
		SVVisualizationDataLock.unlock();
		return result;
	}

	/**
	 * Traverse GCA and get to rawData data vertex of specified type. Return empty arraylist if not found OR when currentRid is invalid.
	 * @param type General vertex type of raw data.
	 * @param txGraph
	 * @return Data Vertex type of raw data.
	 */
	private ArrayList<Vertex> traverseGCAGetRawDataDataVertex(String type, Graph txGraph) {
		try {
			Vertex GCAMainVertex = Util.ridToVertex(MainController.getCurrentRid(), txGraph);
			Vertex GCAMainDataVertex = Util.traverseOnce(GCAMainVertex, Direction.IN, DBCN.E.data);
			ArrayList<Vertex> otherGCAVertexes = Util.traverse(GCAMainDataVertex, Direction.OUT, DBCN.E.GCA);
			int rawDataGCAIndex = -1;
			//locate the GCA of type raw data.
			for (int i=0; i<otherGCAVertexes.size(); i++) {
				Vertex v = otherGCAVertexes.get(i);
				if (v.getCName().equals(DBCN.V.general.GCAMain.rawData.cn)) {
					rawDataGCAIndex = i;
					break;
				}
			}

			//Sometime the GCA is not spaced evenly, thus some GCAMain may not have all GCA type as its data at any
			//particular moment although he really should. Bypass that by ignoring it explicitly.
			if (rawDataGCAIndex == -1) {
				return new ArrayList<Vertex>();
			}

			//Fetch the data and display it.
			Vertex rawDataGCADataVertex = Util.traverseOnce(otherGCAVertexes.get(rawDataGCAIndex), Direction.IN, DBCN.E.data);
			ArrayList<Vertex> actualRawDataGeneralVertex = Util.traverse(rawDataGCADataVertex, Direction.OUT, DBCN.E.GCA);

			ArrayList<Vertex> result = new ArrayList<Vertex>();
			for (Vertex v : actualRawDataGeneralVertex) {
				if (v.getCName().equals(type)) {
					result.add(Util.traverseOnce(v, Direction.IN, DBCN.E.data));
				}
			}
			return result;
		}
		catch (NoSuchElementException e) {
			//MainController.getCurrentRid() may fail as it might still be in init stage or user haven click the
			//timeline start yet, if fail then we return empty array.
		}
		return new ArrayList<Vertex>();
	}

	//-Visual
	//Image view.
	@FXML
	public ImageView sv_visualImg_imageView;
	@FXML
	public VBox sv_paneVisual_vBox;

	//-Audio
	//Audio wave diagram view.
	@FXML
	public ImageView sv_audio_waveDiagram_imageView;
	@FXML
	public ToggleButton sv_audio_waveDiagram_toggleButton;
	@FXML
	public ToggleButton sv_audio_mute_toggleButton;
	@FXML
	public VBox sv_paneAudio_vBox;

	//-Sensor
	//Motor locations Front Left Right Back Motor.
	@FXML
	public TextField sv_sensor_flm_textField, sv_sensor_frm_textField, sv_sensor_blm_textField, sv_sensor_brm_textField;
	@FXML
	public VBox sv_paneSensor_vBox;

	//FPS field for visual, audio and sensor data, available for SV only. Frame per second or sample per second.
	@FXML
	private TextField sv_visualFps_textField, sv_audioFps_textField, sv_sensorFps_textField;

	@FXML
	private ToggleButton sv_visual_toggleButton, sv_audio_toggleButton, sv_sensor_toggleButton;
	private AtomicBoolean visualThreadHalt = new AtomicBoolean(false);
	private AtomicBoolean audioThreadHalt = new AtomicBoolean(false);
	private AtomicBoolean sensorThreadHalt = new AtomicBoolean(false);
	private AtomicBoolean visualThreadRunning = new AtomicBoolean(false);
	private AtomicBoolean audioThreadRunning = new AtomicBoolean(false);
	private AtomicBoolean sensorThreadRunning = new AtomicBoolean(false);

	//Record whether the task is completed or not, if completed then move to next depth.
	private AtomicBoolean visualTaskCompleted = new AtomicBoolean(false);
	private AtomicBoolean audioTaskCompleted = new AtomicBoolean(false);
	private AtomicBoolean sensorTaskCompleted = new AtomicBoolean(false);
	/**
	 * Main SV management (selected visualization) method. Manages all the processing and displaying logic.
	 * Put the data translator here to provide it with constant data.
	 * We will automatically skip frames that comes in too fast to display only the latest frame if the latency is too great.
	 * Guarantee is that all the frames will be synchronized to particular time point during display so no incoherence between sensors
	 * display possible.
	 * @param event
	 */
	public void audioVisualSensorManage (ActionEvent event) {
		//NOTE that only the sensorVisualization using the real time data that follows the grand timeline (not the one expecting data to be fed
		//in by selected vertex of main graph) doesn't uses the integrated SV timeline as it doesn't have any non monolith data (all are
		//constant time operation), but for the other side, it requires the timeline bar as it doesn't follow the main timeline.
		//If the button is pressed and the data operation thread is not yet running, then we reset the state and start up the thread,
		//else (button un pressed) then we call the thread to die by setting its flag.
		if (sv_visual_toggleButton.isSelected()) {
			sv_paneVisual_vBox.setVisible(true);
			visualThreadHalt.set(false);

			if (!visualThreadRunning.get()) {
				new Thread(new Runnable() {
				    @Override
				    public void run() {
				    	//Acquire graph from the mainController, set there for no special purpose, just because he is the origin.
				    	Graph txGraph = MainController.factory.getTx();
				    	visualThreadRunning.set(true);

				    	//Calculate fps for SV only, tree based fps cannot be calculated thus not implemented.
				    	int fps = 0;
				    	long lastGCATime = 0l;

				    	while (!visualThreadHalt.get() && !Main.isHalt()) {
				    		byte[] visualData = null;
				    		//If listen to main timeline GCA rid, we fetch data from that rid.
				    		if (isAllSensorVisualizationByMainTimeLineGCARid) {
					    		//If paused then do nothing. This follow the global timeline pause.
					    		if (!MainController.isPaused()) {
					    			//If there is many of them, due to GCA delay sometime, we read only the last one.
					    			ArrayList<Vertex> vertexes = traverseGCAGetRawDataDataVertex(DBCN.V.general.rawData.PI.dev.camera1.cn, txGraph);
					    			Vertex visualDataVertex = vertexes.isEmpty() ? null : vertexes.get(vertexes.size() -1);
					    			if (visualDataVertex != null) {
					    				//Increment fps everytime if data is available.
				    					fps++;
					    				long currentGCATimestamp = Util.ridToVertex(MainController.getCurrentRid(), txGraph).getProperty(LP.timeStamp);
					    				//If it has reached 1 second, post the result to the user and recount fps from scratch.
					    				if (currentGCATimestamp - lastGCATime > 1000) {
					    					lastGCATime = currentGCATimestamp;
					    					final int finalFps = fps;
					    					fps = 0;

					    					Platform.runLater(new Runnable() {
					    		    			@Override
					    		    			public void run() {
					    		    				sv_visualFps_textField.setText(Integer.toString(finalFps));
					    		    			}
					    		    		});
					    				}


					    				visualData = visualDataVertex.getProperty(LP.data);
					    			}
					    		}
					    		else {
					    			Util.sleep(1);
					    			continue;
					    		}
				    		}
				    		else {
				    			//This follow the local timeline so it can be played independently when global timeline pauses.
				    			if (!isPausedSV.get())
				    				visualData = getSVVisualData();
					    		else {
					    			Util.sleep(1);
					    			continue;
					    		}
				    		}

				    		if (visualData == null) {
				    			Util.sleep(1);
				    			//If no data for this depth, mark it as completed.
				    			visualTaskCompleted.set(true);
				    			continue;
				    		}

				    		//Display the image, the timing has been done by inside the fetch procedure.
				    		//http://stackoverflow.com/questions/25609280/display-bytes-from-database-as-image-in-imageview-in-javafx
				    		ByteArrayInputStream in = new ByteArrayInputStream(visualData);
				    		try {
				    			BufferedImage read = ImageIO.read(in);
				    			sv_visualImg_imageView.setImage(SwingFXUtils.toFXImage(read, null));
				    		} catch (IOException e) {
				    			throw new IllegalStateException("Exception: ", e);
				    		}
				    		visualTaskCompleted.set(true);
						}
						visualThreadRunning.set(false);
				    }
				}).start();
			}
		}
		else if (!sv_visual_toggleButton.isSelected()) {
			visualThreadHalt.set(true);
			sv_paneVisual_vBox.setVisible(false);
		}

		if (sv_audio_toggleButton.isSelected()) {
			sv_paneAudio_vBox.setVisible(true);
			audioThreadHalt.set(false);

			if (!audioThreadRunning.get()) {
				new Thread(new Runnable() {
					@Override
					public void run() {
						Graph txGraph = MainController.factory.getTx();
						audioThreadRunning.set(true);

						int fps = 0;
						long lastGCATime = 0l;

						while (!audioThreadHalt.get() && !Main.isHalt()) {
							//Play the sound if it is not muted.
							if (!sv_audio_mute_toggleButton.isSelected()) {
								//If listen to main timeline GCA rid, we fetch data from that rid.
								if (isAllSensorVisualizationByMainTimeLineGCARid) {
						    		//If paused then do nothing. This follow the global timeline pause.
						    		if (!MainController.isPaused()) {
										//For audio vertexes we fetch and play it all.
										ArrayList<Vertex> vertexes = traverseGCAGetRawDataDataVertex(DBCN.V.general.rawData.PI.dev.mic1.cn, txGraph);

										if (vertexes.isEmpty()) {
											Util.sleep(1);
											continue;
										}

										//As they are in the same GCA and are not of pattern type (scattered), in raw data form, they are
										//guaranteed to be in order and run one after another sequentially.
										for (Vertex v : vertexes) {
						    				//Increment fps everytime if data is available.
					    					fps++;
						    				long currentGCATimestamp = Util.ridToVertex(MainController.getCurrentRid(), txGraph).getProperty(LP.timeStamp);
						    				//If it has reached 1 second, post the result to the user and recount fps from scratch.
						    				if (currentGCATimestamp - lastGCATime > 1000) {
						    					lastGCATime = currentGCATimestamp;
						    					final int finalFps = fps;
						    					fps = 0;

						    					Platform.runLater(new Runnable() {
						    		    			@Override
						    		    			public void run() {
						    		    				sv_audioFps_textField.setText(Integer.toString(finalFps));
						    		    			}
						    		    		});
						    				}


											//Play the audio, the timing has been done by inside the fetch procedure.
											//Play the data according to embedded timeline exclusive for the SV (not the timeline of the main timeline thread).
											//http://stackoverflow.com/questions/12589156/java-byte-array-play-sound
											//Create the AudioData object from the byte array
											AudioData audiodataObj = new AudioData( (byte[])v.getProperty(LP.data));
											// Create an AudioDataStream to play back
											AudioDataStream audioStream = new AudioDataStream(audiodataObj);
											// Play the sound
											AudioPlayer.player.start(audioStream);
										}
									}
									else {
										Util.sleep(1);
										continue;
									}
								}
								//Else we listen to given source.
								else {
									//This follow the local timeline so it can be played independently when global timeline pauses.
									if (!isPausedSV.get()) {
										TreeMap<Long, byte[]> audioData = getSVAudioData();
										if (audioData == null) {
											Util.sleep(1);
											//If no data for this depth, mark it as completed.
											audioTaskCompleted.set(true);
											continue;
										}

										//Calculate absolute run time.
										ArrayList<Long> absoluteRunTime = new ArrayList<Long>();
										ArrayList<byte[]> audioDataList = new ArrayList<byte[]>();
										long firstStartTime = -1l;
										long currentTime = System.currentTimeMillis();
										for (Map.Entry<Long, byte[]> entry : audioData.entrySet()) {
											if (firstStartTime == -1l)
												firstStartTime = entry.getKey();
											//Current time + offset.
											absoluteRunTime.add(currentTime + entry.getKey() - firstStartTime);
											audioDataList.add(entry.getValue());
										}

										for (int i=0; i<absoluteRunTime.size(); i++) {
											while (System.currentTimeMillis() < absoluteRunTime.get(i)) {
												;	//Wait until we reaches the time to run.
											}
											//Play the audio, the timing has been done by inside the fetch procedure.
											//Play the data according to embedded timeline exclusive for the SV (not the timeline of the main timeline thread).
											//http://stackoverflow.com/questions/12589156/java-byte-array-play-sound
											//Create the AudioData object from the byte array
											AudioData audiodataObj = new AudioData(audioDataList.get(i));
											// Create an AudioDataStream to play back
											AudioDataStream audioStream = new AudioDataStream(audiodataObj);
											// Play the sound
											AudioPlayer.player.start(audioStream);
										}
										audioTaskCompleted.set(true);
									}
									else {
										Util.sleep(1);
										continue;
									}
								}
							}

							if (sv_audio_waveDiagram_toggleButton.isSelected()) {
								//TODO: Use the fetched audio file and convert it into image and display it.
								//Not supported, nobody is interested in viewing the wave diagram.
							}

							//If it has nothing to do, sleep until user select a work (view) for us, then we fetch the latest data and continue.
							if (!sv_audio_mute_toggleButton.isSelected() && !sv_audio_waveDiagram_toggleButton.isSelected())
								Util.sleep(1);
						}
						audioThreadRunning.set(false);
						txGraph.shutdown();
				    }
				}).start();
			}
		}
		else if (!sv_audio_toggleButton.isSelected()) {
			audioThreadHalt.set(true);
			sv_audio_waveDiagram_toggleButton.setSelected(false);
			sv_audio_mute_toggleButton.setSelected(false);
			sv_paneAudio_vBox.setVisible(false);
		}

		if (sv_sensor_toggleButton.isSelected()) {
			sv_paneSensor_vBox.setVisible(true);
			sensorThreadHalt.set(false);

			if (!sensorThreadRunning.get()) {
				new Thread(new Runnable() {
					@Override
					public void run() {
						sensorThreadRunning.set(true);
						Graph txGraph = MainController.factory.getTx();

						int fps = 0;
						long lastGCATime = 0l;

						while (!sensorThreadHalt.get() && !Main.isHalt()) {
							//If listen to main timeline GCA rid, we fetch data from that rid.
							SensorData sensorData = new SensorData();
							if (isAllSensorVisualizationByMainTimeLineGCARid) {
								//If paused then do nothing. This follow the global timeline pause.
								if (!MainController.isPaused()) {
									ArrayList<Vertex> sensorDataVertex1 = traverseGCAGetRawDataDataVertex(DBCN.V.general.rawData.POFeedback.dev.motor1.cn, txGraph);
									ArrayList<Vertex> sensorDataVertex2 = traverseGCAGetRawDataDataVertex(DBCN.V.general.rawData.POFeedback.dev.motor2.cn, txGraph);
									ArrayList<Vertex> sensorDataVertex3 = traverseGCAGetRawDataDataVertex(DBCN.V.general.rawData.POFeedback.dev.motor3.cn, txGraph);
									ArrayList<Vertex> sensorDataVertex4 = traverseGCAGetRawDataDataVertex(DBCN.V.general.rawData.POFeedback.dev.motor4.cn, txGraph);
									//Get the last one if there is many of them.
				    				//Increment fps everytime if data is available.
									if (!sensorDataVertex1.isEmpty()) {
										sensorData.flm = sensorDataVertex1.get(sensorDataVertex1.size() - 1).getProperty(LP.data);
				    					fps++;
									}
									if (!sensorDataVertex2.isEmpty()) {
										sensorData.frm = sensorDataVertex2.get(sensorDataVertex2.size() - 1).getProperty(LP.data);
				    					fps++;
									}
									if (!sensorDataVertex3.isEmpty()) {
										sensorData.blm = sensorDataVertex3.get(sensorDataVertex3.size() - 1).getProperty(LP.data);
				    					fps++;

									}
									if (!sensorDataVertex4.isEmpty()) {
										sensorData.brm = sensorDataVertex4.get(sensorDataVertex4.size() - 1).getProperty(LP.data);
										fps++;
									}

				    				long currentGCATimestamp = Util.ridToVertex(MainController.getCurrentRid(), txGraph).getProperty(LP.timeStamp);
				    				//If it has reached 1 second, post the result to the user and recount fps from scratch.
				    				if (currentGCATimestamp - lastGCATime > 1000) {
				    					lastGCATime = currentGCATimestamp;
				    					final int finalFps = fps;
				    					fps = 0;

				    					Platform.runLater(new Runnable() {
				    		    			@Override
				    		    			public void run() {
				    		    				sv_sensorFps_textField.setText(Integer.toString(finalFps));
				    		    			}
				    		    		});
				    				}
								}
								else {
									Util.sleep(1);
									continue;
								}
							}
							else {
				    			//This follow the local timeline so it can be played independently when global timeline pauses.
				    			if (!isPausedSV.get())
									sensorData = getSVSensorData();
					    		else {
					    			Util.sleep(1);
					    			continue;
					    		}
								if (sensorData == null) {
									Util.sleep(1);
									//If no data for this depth, mark it as completed.
									sensorTaskCompleted.set(true);
									continue;
								}
							}

							//Display the sensor data if they are updated. If they are <0, means they are uninitialized. We treat that
							//as do not change.
							if (sensorData.flm >= 0d)
								sv_sensor_flm_textField.setText(Double.toString(sensorData.flm));
							if (sensorData.frm >= 0d)
								sv_sensor_frm_textField.setText(Double.toString(sensorData.frm));
							if (sensorData.blm >= 0d)
								sv_sensor_blm_textField.setText(Double.toString(sensorData.blm));
							if (sensorData.brm >= 0d)
								sv_sensor_brm_textField.setText(Double.toString(sensorData.brm));

							sensorTaskCompleted.set(true);
						}
						sensorThreadRunning.set(false);
					}
				}).start();
			}
		}
		else if (!sv_sensor_toggleButton.isSelected()) {
			sensorThreadHalt.set(true);
			sv_paneSensor_vBox.setVisible(false);
		}

		//If all buttons are untoggled then we kill all the thread.
		if ( (!sv_visual_toggleButton.isSelected() && !sv_audio_toggleButton.isSelected() && !sv_sensor_toggleButton.isSelected())) {
			visualThreadHalt.set(true);
			audioThreadHalt.set(true);
			sensorThreadHalt.set(true);

			//Doesn't have to close the main window here, just have to stop operations as other thread might still be using the window
			//doing other tasks, for example mainGraph and secondaryGraph which continues to run and render the graph in our window
			//neighboring compartment.
		}
	}

	/**
	 * Call all the currently running AVS data threads to terminate and reset its state.
	 */
	public void audioVisualSensorHalt() {
		visualThreadHalt.set(true);
		audioThreadHalt.set(true);
		sensorThreadHalt.set(true);

		sv_visual_toggleButton.setSelected(false);
		sv_audio_toggleButton.setSelected(false);
		sv_sensor_toggleButton.setSelected(false);

		sv_audio_waveDiagram_toggleButton.setSelected(false);
		sv_audio_mute_toggleButton.setSelected(false);

		sv_paneVisual_vBox.setVisible(false);
		sv_paneAudio_vBox.setVisible(false);
		sv_paneSensor_vBox.setVisible(false);
	}

	//All the pane that can be switched visible or not based on user selection.
	@FXML
	public VBox mainGraphPane_pane_vBox, s2dg_pane_vBox, sv_pane_vBox, tc_pane_vBox;
	@FXML
	public SplitPane nonMainGraph_pane_splitPane;
	@FXML
	public SplitPane main_resize_splitpane;
	@FXML
	public SplitPane s2dgSV_resize_splitPane;
}
