package utilities;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.esotericsoftware.yamlbeans.YamlWriter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import ICL.ICLPatternType;
import isradatabase.Direction;
import isradatabase.Edge;
import isradatabase.Graph;
import isradatabase.Vertex;
import linkProperty.LinkProperty.LP;
import stm.DBCN;
import ymlDefine.YmlDefine.DBCredentialConfig;

/**
 * General purpose static utility class.
 */
public abstract class Util {
	/**
	 * Traverse from a vertex to another vertex by crossing the edge with specific label. All edges are required to have a label.
	 * Support multiple vertexes get. He will get all the vertexes with specified edge label type.
	 * @param targetVertex Source vertex of any class, traverse start from here.
	 * @param direction Either in, out or both. The edges that the source vertex have.
	 * @param edgeLabel A special tag that bind with the edge. To uniquely identify that type of edges.
	 * @return The vertexes at the other side of the edge type specified. NPE if no data is available.
	 */
	public static ArrayList<Vertex> traverse (Vertex targetVertex, Direction direction, String edgeLabel) {
		try {
			ArrayList<Vertex> result = targetVertex.getVertices(direction, edgeLabel);
			return result;
		}
		catch (NullPointerException npe) {
			if (targetVertex == null)
				System.out.println("Given targetVertex is null.");
			else
				System.out.println("Given Vertex: " + targetVertex + "; No adjacent vertex available."
						+ " Direction: " + direction + " edgeLabel: "+ edgeLabel);
			throw npe;
		}
	}

	/**
	 * Equivalent to traverse, with added functionality that limits how many vertex to be traversed.
	 * @param targetVertex Source vertex of any class, traverse start from here.
	 * @param direction Either in, out or both. The edges that the source vertex have.
	 * @param edgeLabel A special tag that bind with the edge. To uniquely identify that type of edges.
	 * @return The vertexes at the other side of the edge type specified. NPE if no data is available.
	 */
	public static ArrayList<Vertex> traverse (Vertex targetVertex, Direction direction, String edgeLabel, int limit) {
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		try {
			ArrayList<Vertex> data = targetVertex.getVertices(direction, edgeLabel);
			for (int i=0; i<data.size(); i++) {
				if (i == limit)
					break;
				result.add(data.get(i));
			}
			return result;
		}
		catch (NullPointerException npe) {
			if (targetVertex == null) {
				System.out.println("Given targetVertex is null.");
				throw npe;
			}
			else
				throw new IllegalArgumentException("Given Vertex: " + targetVertex
						+ "; No adjacent vertex available." + " Direction: " + direction + " edgeLabel: "+ edgeLabel);
		}
	}

	/**
	 * Traverse and check type. Only check if ambiguity exist. Else can use unchecked version.
	 */
	public static ArrayList<Vertex> traverse (Vertex targetVertex, Direction direction, String edgeLabel, String[] expectedType) {
		ArrayList<Vertex> fetched = Util.traverse(targetVertex, direction, edgeLabel);
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		for (int i=0; i<fetched.size(); i++) {
			String className = fetched.get(i).getCName();
			if (Util.equalAny(className, expectedType))
				result.add(fetched.get(i));
		}
		return result;
	}
	/**
	 * Traverse and check type. Only check if ambiguity exist. Else can use unchecked version.
	 */
	public static ArrayList<Vertex> traverse (Vertex targetVertex, Direction direction, String edgeLabel, String expectedType) {
		ArrayList<Vertex> fetched = Util.traverse(targetVertex, direction, edgeLabel);
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		for (int i=0; i<fetched.size(); i++) {
			String className = fetched.get(i).getCName();
			if (className.equals(expectedType))
				result.add(fetched.get(i));
		}
		return result;
	}
	/**
	 * Traverse and check type. With limit. Only check if ambiguity exist. Else can use unchecked version.
	 */
	public static ArrayList<Vertex> traverse (Vertex targetVertex, Direction direction, String edgeLabel, int limit, String[] expectedType) {
		ArrayList<Vertex> fetched = Util.traverse(targetVertex, direction, edgeLabel);
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		for (int i=0; i<fetched.size(); i++) {
			String className = fetched.get(i).getCName();
			if (Util.equalAny(className, expectedType))
				result.add(fetched.get(i));
			if (result.size() == limit)
				break;
		}
		return result;
	}

	/**
	 * Equivalent to traverse, but get only 1 vertex. Must known beforehand that there will only be one vertex at the other side.
	 * @param targetVertex Source vertex of any class, traverse start from here.
	 * @param direction Either in, out or both. The edges that the source vertex have.
	 * @param edgeLabel A special tag that bind with the edge. To uniquely identify that type of edges.
	 * @return The vertexes at the other side of the edge type specified. Throws IllegalArgumentException if no element is found.
	 */
	public static Vertex traverseOnce (Vertex targetVertex, Direction direction, String edgeLabel) {
		try {
			return Util.traverse(targetVertex, direction, edgeLabel).get(0);
		}
		catch (IndexOutOfBoundsException e) {
			throw new IllegalArgumentException("No element found after traversal. Configuration is Direction: " + direction
					+ "; Edge:" + edgeLabel + "; Vertex: " + targetVertex + "; Original Message:"+ e.getMessage());
		}
	}

	public static Vertex traverseOnce (Vertex targetVertex, Direction direction, String edgeLabel, String expectedType) {
		ArrayList<Vertex> fetched = Util.traverse(targetVertex, direction, edgeLabel);
		Vertex result = null;
		int duplicateCount = 0;
		for (int i=0; i<fetched.size(); i++) {
			String className = fetched.get(i).getCName();
			if (className.equals(expectedType)) {
				if (result == null)
					result = fetched.get(i);
				else
					duplicateCount++;
			}
		}
		//TODO: If wanted error message when duplicate occur, print the duplicate count.
		return result;
	}
	public static Vertex traverseOnce (Vertex targetVertex, Direction direction, String edgeLabel, String[] expectedType) {
		ArrayList<Vertex> fetched = Util.traverse(targetVertex, direction, edgeLabel);
		Vertex result = null;
		int duplicateCount = 0;
		for (int i=0; i<fetched.size(); i++) {
			String className = fetched.get(i).getCName();
			for (String givenType : expectedType) {
				if (className.equals(givenType)) {
					if (result == null)
						result = fetched.get(i);
					else
						duplicateCount++;
				}
			}
		}
		if (duplicateCount > 0)
			throw new IllegalStateException("Expect only 1 result but get: " + duplicateCount);
		return result;
	}

	/**
	 * Get all edges between targetVertex and all the other vertexes at the other side of the edge filtered by edgeLabel.
	 */
	public static ArrayList<Edge> traverseGetEdges (Vertex targetVertex, Direction direction, String edgeLabel) {
		return targetVertex.getEdges(direction, edgeLabel);
	}

	/**
	 * Traverse and get element 'startTimeOffset' property within the edges between vertexes.
	 */
	public static ArrayList<Long> traverseGetStartTimeOffset (Vertex targetVertex, Direction direction, String edgeLabel) {
		ArrayList<Edge> edges = traverseGetEdges(targetVertex, direction, edgeLabel);
		ArrayList<Long> result = new ArrayList<Long>();
		for (Edge e : edges)
			result.add((Long) e.getProperty(LP.startTimeOffset));
		return result;
	}

	/**
	 * Identical to 'traverse', this returns all of the resulting vertexes' RIDs instead of their vertex.
	 */
	public static ArrayList<String> traverseGetRid (Vertex targetVertex, Direction direction, String edgeLabel) {
		ArrayList<Vertex> original = Util.traverse(targetVertex, direction, edgeLabel);
		ArrayList<String> result = new ArrayList<String>(original.size());
		for (Vertex v : original)
			result.add(v.getRid());
		return result;
	}

	public static String traverseOnceGetRid (Vertex targetVertex, Direction direction, String edgeLabel) {
		return traverseOnce(targetVertex, direction, edgeLabel).getRid();
	}

	/**
	 * Traverse twice to get its occurrence (siblings) from their parent. Note that we traverse via 'occurrence' OUT to its parent instead
	 * of the 'parent' edge as most data doesn't have 'parent' edge but have 'occurrence' edge.
	 * @param targetVertex Any data vertex, never general vertex.
	 * @return Occurrence (siblings) of targetVertex, result size will at least be 1, that would be himself.
	 */
	public static ArrayList<Vertex> traverseGetOccurrence (Vertex targetVertex) {
		//OUT as we make link to parent to register ourself as data vertex. IN as parent means the children that share similar identity like us, siblings.
		Vertex parent = Util.traverseOnce(targetVertex, Direction.OUT, DBCN.E.occurrence);
		return Util.traverse(parent, Direction.IN, DBCN.E.occurrence);
	}

	/**
	 * Identical to 'traverseGetOccurrence', this return their RIDs instead of the actual vertex.
	 */
	public static ArrayList<String> traverseGetOccurrenceRid (Vertex targetVertex) {
		ArrayList<Vertex> occurrenceList = Util.traverseGetOccurrence(targetVertex);
		return vertexToRid(occurrenceList);
	}

	/**
	 * Traverse and fetch both sibling and parent's siblings occurrence, then convert them into RIDs and return them as a single list.
	 * LTM vertex type only.
	 * @param targetVertex A LTM general vertex.
	 */
	public static ArrayList<String> traverseGetOccurrenceRidGrandparentScale (Vertex targetVertex) {
		Vertex parent = Util.traverseOnce(targetVertex, Direction.OUT, DBCN.E.parent);
		ArrayList<String> parentOccurrenceRids = Util.traverseGetOccurrenceRid(parent);
		ArrayList<String> siblingOccurrenceRids = Util.traverseGetOccurrenceRid(targetVertex);
		ArrayList<String> result = new ArrayList<String>(parentOccurrenceRids.size() + siblingOccurrenceRids.size());
		result.addAll(parentOccurrenceRids);
		result.addAll(siblingOccurrenceRids);
		return result;
	}

	/**
	 * Traverse and get the given frame's next frame. Frame is GCAMain frame.
	 * @param targetFrame A General GCAMain frame. Not a small subtype of GCA.
	 * @return The next GCAMain frame.
	 */
	public static Vertex traverseGetGCANextFrame (Vertex GCAMainFrame) {
		String expectedClassName = GCAMainFrame.getCName();
		if ( !expectedClassName.equals(DBCN.V.general.GCAMain.cn) )
			throw new IllegalArgumentException("Must be a GCAMain general vertex, expected class:" + DBCN.V.general.GCAMain.cn + " but received:" + expectedClassName);
		//IN 'previous' means next, OUT 'previous' means previous. As we implement new vertex mark their source, not source go out and find new child.
		return Util.traverseOnce(GCAMainFrame, Direction.IN, DBCN.E.previous, DBCN.V.general.GCAMain.cn);
	}

	/**
	 * Traverse and get the given frame's previous frame. Frame is GCAMain frame. Essentially getting the frame's requirement.
	 * @param targetFrame A General GCAMain frame. Not a small subtype of GCA.
	 * @return The previous GCA frame.
	 */
	public static Vertex traverseGetGCAPreviousFrame (Vertex GCAMainFrame) {
		String expectedClassName = GCAMainFrame.getCName();
		if ( !expectedClassName.equals(DBCN.V.general.GCAMain.cn) )
			throw new IllegalArgumentException("Must be a GCAMain general vertex, expected class:" + DBCN.V.general.GCAMain.cn + " but received:" + expectedClassName);
		//IN 'previous' means next, OUT 'previous' means previous. As we implement new vertex mark their source, not source go out and find new child.
		return Util.traverseOnce(GCAMainFrame, Direction.OUT, DBCN.E.previous, DBCN.V.general.GCAMain.cn);
	}

	/**
	 * Similar to traverseGCAMainGetRequirement, but this one doesn't traverse back to its previous frame but instead fetch the requirement directly
	 * at the given frame.
	 * @param generalGCAMainFrame A General GCA frame. Not a small subtype of GCA.
	 * @return A group of requirement type GCA vertex data' list.
	 */
	public static ArrayList<Vertex> GCAMainGetRequirement (Vertex generalGCAMainFrame) {
		String expectedClassName = generalGCAMainFrame.getCName();
		if ( !expectedClassName.equals(DBCN.V.general.GCAMain.cn) )
			throw new IllegalArgumentException("Must be a GCAMain general vertex, expected class:" + DBCN.V.general.GCAMain.cn + " but received:" + expectedClassName);

		Vertex GCAMainDataVertex = Util.traverseOnce(generalGCAMainFrame, Direction.IN, DBCN.E.data, DBCN.V.LTM.GCAMain.cn);
		ArrayList<Vertex> GCAChildGeneralVertexList = Util.traverse(GCAMainDataVertex, Direction.OUT, DBCN.E.GCA);
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		for (int i=0; i<GCAChildGeneralVertexList.size(); i++) {
			Vertex GCAChildGeneralVertex = GCAChildGeneralVertexList.get(i);
			String className = GCAChildGeneralVertex.getCName();
			//If they are requirement type GCA, we will extract them.
			if (className.equals(DBCN.V.general.GCAMain.rawData.cn) || className.equals(DBCN.V.general.GCAMain.rawDataICL.cn)) {
				//Traverse and get all of its data then add it to the result list.
				Vertex GCAChildDataVertex = Util.traverseOnce(GCAChildGeneralVertex, Direction.IN, DBCN.E.data);
				result.addAll(Util.traverse(GCAChildDataVertex, Direction.OUT, DBCN.E.GCA));
			}
		}
		return result;
	}

	/**
	 * Traverse a generalGCAMainVertex and extract all of its requirement type GCA child by traversing backward once to its previous frame, from there
	 * fetch the requirement data and after that group them all into a single vertex list and return.
	 * @param generalGCAMainFrame A General GCA frame. Not a small subtype of GCA.
	 * @return A group of requirement type GCA vertex data' list.
	 */
	public static ArrayList<Vertex> traverseGCAMainGetRequirement (Vertex generalGCAMainFrame) {
		Vertex previousGCAMainFrame = Util.traverseGetGCAPreviousFrame(generalGCAMainFrame);
		return GCAMainGetRequirement(previousGCAMainFrame);
	}

	/**
	 * Be noted that result and requirement are actually the same thing, if you traverse backward once, that backward's data is the requirement of
	 * your current action, thus the current data becomes the result. Thus we can reuse get requirement functionality.
	 * @param generalGCAMainFrame A General GCA frame. Not a small subtype of GCA.
	 * @return A group of result type GCA vertex data' list.
	 */
	public static ArrayList<Vertex> GCAMainGetResult (Vertex generalGCAMainFrame) {
		return GCAMainGetRequirement(generalGCAMainFrame);
	}

	/**
	 * Extract the 'process' general vertex directly from the generalGCAMainFrame given and return as a vertex list of general PO vertex.
	 * @param generalGCAMainFrame A General GCA frame. Not a small subtype of GCA.
	 * @return A vertex list of general PO vertex.
	 */
	public static ArrayList<Vertex> GCAMainGetProcess (Vertex generalGCAMainFrame) {
		String expectedClassName = generalGCAMainFrame.getCName();
		if ( !expectedClassName.equals(DBCN.V.general.GCAMain.cn) )
			throw new IllegalArgumentException("Must be a GCAMain general vertex, expected class:" + DBCN.V.general.GCAMain.cn + " but received:" + expectedClassName);

		Vertex GCAMainDataVertex = Util.traverseOnce(generalGCAMainFrame, Direction.IN, DBCN.E.data, DBCN.V.LTM.GCAMain.cn);
		ArrayList<Vertex> GCAChildGeneralVertexList = Util.traverse(GCAMainDataVertex, Direction.OUT, DBCN.E.GCA);
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		for (int i=0; i<GCAChildGeneralVertexList.size(); i++) {
			Vertex GCAChildGeneralVertex = GCAChildGeneralVertexList.get(i);
			String className = GCAChildGeneralVertex.getCName();

			//If they are 'process' type, extract them.
			if (className.equals(DBCN.V.general.GCAMain.POFeedbackGCA.cn)) {
				//Traverse and get all of its data then add it to the result list.
				Vertex GCAChildDataVertex = Util.traverseOnce(GCAChildGeneralVertex, Direction.IN, DBCN.E.data);
				result.addAll( Util.traverse(GCAChildDataVertex, Direction.OUT, DBCN.E.GCA) );
			}

		}
		return result;
	}

	/**
	 * Similar to GCAMainGetProcess, but this traverse backward once to get to the actual previous process, unlike the GCAMainGetProcess
	 * where it fetches the process from the given frame directly.
	 * NOTE: Previous frame's process is the current frame requirement.
	 * @param generalGCAMainFrame A General GCA frame. Not a small subtype of GCA.
	 * @return A vertex list of general PO vertex.
	 */
	public static ArrayList<Vertex> traverseGCAMainGetProcess (Vertex generalGCAMainFrame) {
		Vertex previousGCAMainFrame = Util.traverseGetGCAPreviousFrame(generalGCAMainFrame);
		return GCAMainGetProcess(previousGCAMainFrame);
	}

	/**
	 * Extract the 'ICL pattern' general vertex directly from the given generalGCAMainFrame and return as a composite pattern.
	 * @param generalGCAMainFrame A General GCA frame. Not a small subtype of GCA.
	 * @return List of pattern separated by type in an object in LTM form not general.
	 */
	public static ICLPatternType GCAMainGetICLPattern (Vertex generalGCAMainFrame) {
		String expectedClassName = generalGCAMainFrame.getCName();
		if ( !expectedClassName.equals(DBCN.V.general.GCAMain.cn) )
			throw new IllegalArgumentException("Must be a GCAMain general vertex, expected class:" + DBCN.V.general.GCAMain.cn + " but received:" + expectedClassName);

		Vertex GCAMainDataVertex = Util.traverseOnce(generalGCAMainFrame, Direction.IN, DBCN.E.data, DBCN.V.LTM.GCAMain.cn);
		ArrayList<Vertex> GCAChildGeneralVertexList = Util.traverse(GCAMainDataVertex, Direction.OUT, DBCN.E.GCA);
		ICLPatternType result = new ICLPatternType();
		for (int i=0; i<GCAChildGeneralVertexList.size(); i++) {
			Vertex GCAChildGeneralVertex = GCAChildGeneralVertexList.get(i);
			String className = GCAChildGeneralVertex.getCName();

			//If they are ICL type GCA, we will extract them.
			if (className.equals(DBCN.V.general.GCAMain.rawDataICL.cn)) {
				//Traverse and get all of its data then add it to the result list.
				Vertex GCAChildDataVertex = Util.traverseOnce(GCAChildGeneralVertex, Direction.IN, DBCN.E.data);
				ArrayList<Vertex> actualChildGeneralVertexList = Util.traverse(GCAChildDataVertex, Direction.OUT, DBCN.E.GCA);

				//Traverse once more to get to the LTM of the ICL pattern, then convert it to RID.
				for (Vertex actualChildGeneralVertex : actualChildGeneralVertexList) {
					String childDataVertexRid = Util.traverseOnceGetRid(actualChildGeneralVertex, Direction.IN, DBCN.E.data);

					if (className.equals(DBCN.V.LTM.rawDataICL.visual.cn)) {
						result.visualRid.add(childDataVertexRid);
					}
					else if (className.equals(DBCN.V.LTM.rawDataICL.audio.cn)) {
						result.audioRid.add(childDataVertexRid);
					}
					else if (className.equals(DBCN.V.LTM.rawDataICL.movement.cn)) {
						result.movementRid.add(childDataVertexRid);
					}
				}

			}
		}
		return result;
	}

	/**
	 * Similar to GCAMainGetICLPattern, but this traverse backward once to get to the actual previous process, unlike the GCAMainGetICLPattern
	 * where it fetches the process from the given frame directly.
	 * NOTE: Previous frame's process is the current frame requirement.
	 * @param generalGCAMainFrame A General GCA frame. Not a small subtype of GCA.
	 * @return List of pattern separated by type in an object in LTM form not general.
	 */
	public static ICLPatternType traverseGCAMainGetICLPattern (Vertex generalGCAMainFrame) {
		Vertex previousGCAMainFrame = Util.traverseGetGCAPreviousFrame(generalGCAMainFrame);
		return GCAMainGetICLPattern(previousGCAMainFrame);
	}

	/**
	 * Traverse from any LTM or expMainGenerla vertex to its GCA general vertex.
	 * @param generalVertex Must be part of LTM datatype's general vertex.
	 * @return The resulting GCA general vertex.
	 */
	public static Vertex traverseGetGCAMainGeneral(Vertex generalVertex, Graph txGraph) {
		System.out.println("traverseGetGCAMainGeneral: " + generalVertex);
		Vertex GCADataVertex = Util.traverseOnce(generalVertex, Direction.IN, DBCN.E.GCA);
		Vertex GCAGeneralVertex = Util.traverseOnce(GCADataVertex, Direction.OUT, DBCN.E.data);
		Vertex GCAMainDataVertex = Util.traverseOnce(GCAGeneralVertex, Direction.IN, DBCN.E.GCA, DBCN.V.LTM.GCAMain.cn);
		Vertex GCAMainGeneralVertex = Util.traverseOnce(GCAMainDataVertex, Direction.OUT, DBCN.E.data, DBCN.V.general.GCAMain.cn);
		return GCAMainGeneralVertex;
	}

	/**
	 * Traverse backward the given frameCount amount of expMainGeneral, get its timeStamp and the given expMainGeneral timeStamp,
	 * then return the differences between 2 timeStamp.
	 * May throw IllegalArgumentException if traverseGetGCAPreviousFrame fails due to unable to find previous vertex.
	 * @param GCAMainGeneral A General GCA frame. Not a small subtype of GCA.
	 * @param frameCount The number of GCAMainGeneral frame to be traversed backward.
	 * @return The differences between 2 timestamp.
	 */
	public static long traverseGCAMainCalculateDuration (Vertex GCAMainGeneral, int frameCount, Graph txGraph) {
		Vertex beginningExpMainGeneral = null;
		int errorCount = 0;
		for (int i=0; i<frameCount; i++) {
			//This is to hold in case the traverseGetGCAPreviousFrame, leaving beginningExpMainGeneral in an undefined state.
			Vertex tempLastExpMainGeneral = null;
			try {
				if (i == 0)
					beginningExpMainGeneral = Util.traverseGetGCAPreviousFrame(GCAMainGeneral);
				else {
					tempLastExpMainGeneral = beginningExpMainGeneral;
					beginningExpMainGeneral = Util.traverseGetGCAPreviousFrame(beginningExpMainGeneral);
				}
			}
			catch (IllegalArgumentException e) {
				if (errorCount > 10)
					throw e;
				if (i == 0)
					GCAMainGeneral = Util.vReload(GCAMainGeneral, txGraph);
				else
					//Reload the last know state before the exception to ensure its validity.
					beginningExpMainGeneral = Util.vReload(tempLastExpMainGeneral, txGraph);
				//i-- to rerun it. Must be the last statement as code above uses 'i' as signal.
				i--;
				errorCount++;
			}
		}
		long beginningTimeStamp = beginningExpMainGeneral.getProperty(LP.timeStamp);
		long endTimeStamp = GCAMainGeneral.getProperty(LP.timeStamp);
		return endTimeStamp - beginningTimeStamp;
	}

	/**
	 * Traverse a link and get only the specified element data type from outer vertex.
	 * Can get any type of data type's field.
	 * @param targetVertex The source vertex, we will traverse to other vertex from here.
	 * @param direction IN, OUT or BOTH, to speed up edges query.
	 * @param edgeLabel A special tag that bind with the edge. To uniquely identify that type of edges.
	 * @return The data field type that user are expecting.
	 */
	public static <T> T traverseGetDataField (Vertex targetVertex, Direction direction, String edgeLabel, LP dataType) {
		ArrayList<Vertex> temp = traverse(targetVertex, direction, edgeLabel);
		return getDataField(temp, dataType);
	}

	/**
	 * Similar to traverseGetDataField above, but this will filter out and select only the first matching vertex of class 'targetClass'
	 * and fetch its property field named 'dataType'.
	 * @param targetVertex
	 * @param direction
	 * @param edgeLabel
	 * @param dataType
	 * @param targetClass
	 * @return
	 */
	public static <T> T traverseGetDataField (Vertex targetVertex, Direction direction, String edgeLabel, LP dataType, String targetClass) {
		ArrayList<Vertex> temp = traverse(targetVertex, direction, edgeLabel);
		for (Vertex v : temp) {
			if (v.getCName().equals(targetClass))
				return v.getProperty(dataType);
		}
		throw new IllegalStateException("No matching vertex found. ArraySize: " + temp.size() + " Elements:" + temp.toString());
	}

	/**
	 * Get any data type specified, equivalent to vertex.getProperty
	 * @param targetVertex The data to be fetched.
	 * @return Any type of data user are expecting, either Iterable or primitive type according to orientdb spec.
	 */
	public static <T> T getDataField (Vertex targetVertex, LP dataType) {
		return targetVertex.getProperty(dataType);
	}

	/**
	 * Overloaded function for simpler access, see original getProperty.
	 * *****Return only the first element's data field from the vertex list given. So arrayList with more than 1 entry will raise error.
	 * @param targetList The input list that we will get his 'data' field from.
	 * @return Any type of data user are expecting, either Iterable or primitive type according to orientdb spec.
	 */
	public static <T> T getDataField (ArrayList<Vertex> targetList, LP dataType) {
		//check exception first, we only allows list with 1 element to use this function.
		if (targetList.size() != 1) {
			StringBuilder stringBuilder = new StringBuilder();

			//generate list of RID that are given to us. So user know WTF to deal with to solve this bug.
			for (int i=0; i < targetList.size(); i++) {
				Vertex v = targetList.get(i);
				stringBuilder.append(v.getRid());
				//stop adding " " space if it is the last element.
				if ( i-1 != targetList.size())
					stringBuilder.append(" ");
			}
			throw new IllegalStateException("Function traverseGetDataField(), input list have '" + targetList.size() + "' entry, only exactly"
			+ " 1 entry is allowed. Rationale: This cannot process multiple entry at once because the result will be mixed together."
			+ "All input vertex RID from the list are: " + stringBuilder.toString());
		}
		return targetList.get(0).getProperty(dataType);
	}

	public static Edge getEdge (Vertex targetVertex, Direction direction, String edgeLabel) {
		return targetVertex.getEdges(direction, edgeLabel).iterator().next();
	}

	/**
	 * Translate string based RID back into real vertex by performing a fetch from DB.
	 * @param ridList The ridList that contain rid in String form.
	 * @param txGraph A functional transactional graph that is already connected to the main DB.
	 * @return A iterable orientVertex fetched from db based on the rid provided.
	 */
	public static ArrayList<Vertex> ridToVertex (ArrayList<String> ridList, Graph txGraph) {
		StringBuilder serializedRid = new StringBuilder();

		for (int i=0; i < ridList.size(); i++) {
			serializedRid.append(ridList.get(i));
			//stop adding "," comma if it is the last element.
			if ( i+1 != ridList.size())
				serializedRid.append(",");
		}

		//example format: SELECT FROM  [#3:0,#3:1]
		return txGraph.directQueryExpectVertex("SELECT FROM [" + serializedRid.toString() + "]");
	}

	/**
	 * Translate string based RID back into real vertex by performing a fetch from DB.
	 * One rid only.
	 * @param ridList The ridList that contain rid in String form.
	 * @param txGraph A functional transactional graph that is already connected to the main DB.
	 * @return A iterable orientVertex fetched from db based on the rid provided.
	 */
	public static Vertex ridToVertex (String rid, Graph txGraph) {
		//example format: SELECT FROM  [#3:0,#3:1]
		return txGraph.directQueryExpectVertex("SELECT FROM [" + rid + "]").get(0);
	}

	/**
	 * Convert vertex list into RID list.
	 * @param vertexList
	 * @return
	 */
	public static ArrayList<String> vertexToRid (ArrayList<Vertex> vertexList) {
		ArrayList<String> result = new ArrayList<String>();
		for (Vertex v : vertexList) {
			result.add(v.getRid());
		}
		return result;
	}

	/**
	 * Remove vertex using rid.
	 * NOTE: User MUST WRAP this in a TRANSACTION!!!  It doesn't manage its own transaction.
	 * @param rid The rid of the vertex to be removed
	 * @param txGraph A functional transactional graph that is already connected to the main DB.
	 */
	public static void removeVertexByRid (String rid, Graph txGraph) {
		Util.ridToVertex(rid, txGraph).remove();;
	}

	/**
	 * Covert polyVal to fit in certain threshold defined by upperBoundary and lowerBoundary.
	 * Uses double to prevent premature precision loss.
	 * Does not support negative u/l bound
	 * @param lBound LowerBoundary.
	 * @param uBound UpperBoundary.
	 * @param polyVal The polyVal to be converted.
	 * @return A value that fits in between upper and lower bound that matches the polyVal given.
	 */
	public static double polyValDenormalize(double lBound, double uBound, double polyVal) {
		if (uBound >= 0 && lBound >=0) {
			double interval = uBound - lBound;

			//reverse operation of normalization is  (polyVal / 100 ) * uBound = orinalVal   <----> (originalVal / uBound) * 100 = polyVal
			return (polyVal / 100 ) * interval;
		}

		//we have negative boundary. This case lower bound is negative and upperBound is positive, eg audio bitdepth.
		else if (uBound >= 0 && lBound <= 0) {
			//normalization with negative lBound:  ((value + uBound) / converted UBound) * 100% = polyVal
			//inverse operation is: value = ((polyVal / 100) * converted UBound) + uBound
			//rational is convert negative lBound to 0, update uBound, then calculate in positive form, then convert it to percentage.
			double newUBound = Math.abs(lBound) + uBound;
			return ((polyVal / 100) * newUBound) - uBound;
		}

		//both boundary are negative, which doesn't exist yet. TODO: here.
		else
			return 0d;
	}

	/**
	 * Copy vertex from source to target. Edge and properties only.
	 * TODO: make sure this function work as expected.
	 * @return The modified target.
	 */
	public static Vertex copyVertexEdgeAndProperty(Vertex source, Vertex target) {
		target = source;
		return target;
	}

	public static <T> String  objectToYml(T anything) throws YamlException {
		Writer swriter = new StringWriter();
		YamlWriter ywriter = new YamlWriter(swriter);
		ywriter.write(anything);
		ywriter.close();
		return swriter.toString();
	}

	/**
	 * NOTE: MUST WRAP this inside a TRANSACTION on user site! this doesn't manage its own transaction!
	 * @param className
	 * @param txGraph
	 */
	public static void removeAllVertexFromClass(String className, Graph txGraph) {
		ArrayList<Vertex> itr = txGraph.getVerticesOfClass(className);
		for (Vertex toBeRemoved : itr) {
			toBeRemoved.remove();
		}
	}

	/**
	 * Check whether the input equals to any target.
	 * @return True if matches.
	 */
	public static boolean equalAny(String target, String[] input) {
		for (String sample : input) {
			if (sample.equals(target))
				return true;
		}
		return false;
	}
	/**
	 * Similar to equalAny. Receive arraylist instead of array.
	 */
	public static boolean equalAny(String target, ArrayList<String> input) {
		for (String sample : input) {
			if (sample.equals(target))
				return true;
		}
		return false;
	}

	/*
	 * Externalize these function so future changes in semantic will be trouble free.
	 */
	/**
	 * Traverse and get the GCAMainGeneral vertex from other GCA general vertex.
	 * @return GCAMainGeneral Vertex.
	 */
	public static Vertex traverseGetGCAMainGeneralFromOtherGCAGeneral(Vertex otherGCAGeneralVertex) {
		//Refer to STMServer GCAMain for more hierarchy detail.
		Vertex GCAMainDataVertex = Util.traverseOnce(otherGCAGeneralVertex, Direction.IN, DBCN.E.GCA, DBCN.V.LTM.GCAMain.cn);
		Vertex GCAMainGeneralVertex = Util.traverseOnce(GCAMainDataVertex, Direction.OUT, DBCN.E.data, DBCN.V.general.GCAMain.cn);
		return GCAMainGeneralVertex;
	}

	/**
	 * Input an array list of primitive type only, sort it and return a list containing original index instead of scores.
	 * Return empty list if given array is empty.
	 * @param ascending True then ascending, false descending.
	 */
	public static <T> ArrayList<Integer> sortGetIndex(ArrayList<T> arr, boolean ascending) {
		Multimap<T, Integer> sort = HashMultimap.create();
		for (int i=0; i<arr.size(); i++) {
			T t = arr.get(i);
			sort.put(t, i);
		}

		ArrayList<Integer> result = new ArrayList<Integer>();

		//If nothing is available just return an empty list.
		if (arr.isEmpty())
			return result;

		result.addAll( sort.values() );

		if (!ascending)
			Collections.reverse(result);

		return result;
	}

	/**
	 * Serialize any object into binary without caring its type.
	 */
	public static byte[] kryoSerialize (Object obj) {
		Kryo kryo = new Kryo();
		Output output = new Output(new ByteArrayOutputStream());
		kryo.writeObject(output, obj);
		byte[] byteOutput = output.toBytes();
		output.close();

		return byteOutput;
	}

	/**
	 * Deserialize byte array into object of the type you specify, its type must be known beforehand.
	 * @param byteArrInput
	 * @param classType
	 * @return
	 */
	public static <T> T kryoDeserialize (byte[] byteArrInput, Class<?> classType) {
		Kryo kryo = new Kryo();
		Input input;
		input = new Input(new ByteArrayInputStream(byteArrInput));
		Object result = kryo.readObject(input, classType);
		input.close();
		return (T)result;
	}

	/**
	 * Convert raw byte audio data (with header already trimmed) to double 16bit precision audio data of range -1 to 1, the real representation.
	 * http://stackoverflow.com/questions/4616310/convert-signed-int-2bytes-16-bits-in-double-format-with-java
	 */
	public static double[] audioByteArrayToDoubleArray(byte[] audioData) {
		double[] doubles = new double[audioData.length/2];

		ByteBuffer buf = ByteBuffer.wrap(audioData);
		buf.order(ByteOrder.LITTLE_ENDIAN);

		int count = 0;
		while (buf.remaining() >= 2) {
		    short s = buf.getShort();
		    double mono = (double) s;
		    double mono_norm = mono / 32768.0;

		    doubles[count] = mono_norm;
		    count++;
		}
		return doubles;
	}

	/**
	 * Convert actual decoded double[] 16bit precision audio data back into byte[] form for storage.
	 * Reverse operation of audioByteArrayToDoubleArray.
	 */
	public static byte[] audioDoubleArrayToByteArray(double[] audioData) {
		byte[] result = new byte[audioData.length*2];
		for (int i=0; i<audioData.length; i++) {
			double d = audioData[i];
			short s = (short) (d*32768.0);
			result[i*2] = (byte)(s & 0xff);
			result[i*2+1] = (byte)((s >> 8) & 0xff);
		}
		return result;
	}

	/**
	 * Iterate over local machine to find all ip that start with 192.168. which is local eth based address.
	 * TODO: Include real ip as well.
	 * @return The filtered ip addresses.
	 */
	public static ArrayList<InetAddress> getEthIp() {
		ArrayList<InetAddress> result = new ArrayList<InetAddress>();
		//http://stackoverflow.com/questions/10298480/getlocaladdress-returning-0-0-0-0
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
					InetAddress ip = enumIpAddr.nextElement();
					if (ip.getHostAddress().substring(0, 8).equals("192.168."))
						result.add(ip);
				}
			}
		} catch (SocketException e) {
			throw new IllegalStateException("SocketException, original stack trace: " + Util.stackTraceToString(e));
		}
		if (result.isEmpty())
			throw new IllegalStateException("No Eth ip found.");

		return result;
	}

	/**
	 * Iterate over local machine to find all ip that start with 10.42. which is local wlan based address.
	 * TODO: Include real ip as well.
	 * @return The filtered ip addresses.
	 */
	public static ArrayList<InetAddress> getWlanIp() {
		ArrayList<InetAddress> result = new ArrayList<InetAddress>();
		//http://stackoverflow.com/questions/10298480/getlocaladdress-returning-0-0-0-0
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
					InetAddress ip = enumIpAddr.nextElement();
					if (ip.getHostAddress().substring(0, 6).equals("10.42."))
						result.add(ip);
				}
			}
		} catch (SocketException e) {
			throw new IllegalStateException("SocketException, original stack trace: " + Util.stackTraceToString(e));
		}
		if (result.isEmpty())
			throw new IllegalStateException("No Wlan ip found.");

		return result;
	}

	/**
	 * Convert stack trace into string.
	 * http://stackoverflow.com/questions/1149703/how-can-i-convert-a-stack-trace-to-a-string
	 * @param e Any exception.
	 * @return String representation of e.printStackTrace.
	 */
	public static String stackTraceToString(Throwable e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString(); // stack trace as a string
	}

	/**
	 * Reload vertex from database and discard any uncommitted data.
	 * vReload() only needed for vertexes that are used across transaction, if used once and no other preceding operation uses it, no reload
	 * required. If it traverse to get a new copy within transaction, then no reload is required as well.
	 * @return A newly reloaded vertex.
	 */
	public static Vertex vReload(String rid, Graph txGraph) {
		return Util.ridToVertex(rid, txGraph);
	}
	public static Vertex vReload(Vertex targetVertex, Graph txGraph) {
		return Util.ridToVertex(targetVertex.getRid(), txGraph);
	}
	public static ArrayList<Vertex> vReload(ArrayList<Vertex> targetVertexList, Graph txGraph) {
		ArrayList<Vertex> result = new ArrayList<Vertex>(targetVertexList.size());
		for (Vertex v : targetVertexList) {
			result.add( Util.ridToVertex(v.getRid(), txGraph) );
		}
		return result;
	}

	/**
	 * Extract record id from the RID. eg #123:5123 returns 5123.
	 * @param rid
	 * @return
	 */
	public static int ridToInt(String rid) {
		//Sample record id #155:204, we want the 204 and -1 to get 203 (previous). indexOf +1 so it skip the ':' symbol.
		return Integer.parseInt( rid.substring( rid.indexOf(":") + 1, rid.length()) );
	}

	/**
	 * Extract class initial from the RID. eg #123:5123 returns #123:
	 * @param rid
	 * @return
	 */
	public static String ridGetClassInitial(String rid) {
		//Extract #123 and include : by +1 as the substring end index is exclusive.
		return rid.substring(0, rid.indexOf(":") + 1);
	}

	/**
	 * Check whether all the boolean in the array is true.
	 * @param boolArr
	 * @return
	 */
	public static boolean isAllTrue (ArrayList<Boolean> boolArr) {
		for (Boolean b : boolArr) {
			if (b.booleanValue() == false)
				return false;
		}
		return true;
	}

	public static String epochMilliToReadable(long currentMilli) {
		//http://www.epochconverter.com/
		//Without the *1000 as we are already milli sec, not sec.
		return new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss").format(new java.util.Date (currentMilli));
	}

	/**
	 * Calculate the amount of passed count based on the given precision rate and total element size.
	 * @param precisionRate
	 * @param totalElementCount
	 * @return The number of passed count in whole number form.
	 */
	public static long precisionRateCalculatePassedCount(double precisionRate, long totalElementCount) {
		//passedElementCount / totalElementCount = precisionRate
		//passedElementCount = precisionRate * totalElementCount
		//Round it to get a whole number representation.
		return Math.round( precisionRate * (double) totalElementCount );
	}

	/**
	 * Calculate timeRan value from the given expMainGeneral, straight to its sibling and parent level's calculation.
	 * This is the central calculation logic, do not implement this on your own.
	 * @return Will never return the sentinel value -1, if they are -1 and no other timeRan data available, we will return 0.
	 */
	public static long timeRanCountFromExpMainGeneral(Vertex expMainGeneral) {
		//OccurrencePR sentinel value is -1, if they are -1 means they are not processed yet by PaRc, thus we skip them directly.
		String givenExpMainGeneralRid = expMainGeneral.getRid();
		long occurrenceCountPR = expMainGeneral.getProperty(LP.occurrenceCountPR);
		long timeRan = occurrenceCountPR == -1l ? 0l : occurrenceCountPR;
		try {
			Vertex parent = Util.traverseOnce(expMainGeneral, Direction.OUT, DBCN.E.occurrence);
			if (!parent.getCName().equals(DBCN.V.general.exp.cn))
				throw new IllegalStateException("Invalid type: " + parent.getCName());

			long parentOccurrenceCountPR = parent.getProperty(LP.occurrenceCountPR);
			timeRan += parentOccurrenceCountPR == -1l ? 0l : parentOccurrenceCountPR;

			//This sibling list will include our original vertex, thus we must skip it.
			//This will never throw IllegalArgumentException as it guaranteed to pass as it include itself, the given expMainGeneral.
			ArrayList<Vertex> siblings = Util.traverse(parent, Direction.IN, DBCN.E.occurrence);
			for (Vertex v : siblings) {
				if (!v.getCName().equals(DBCN.V.general.exp.cn))
					throw new IllegalStateException("Invalid type: " + parent.getCName());
				if (givenExpMainGeneralRid.equals(v.getRid()))
					continue;
				long siblingOccurrenceCountPR = v.getProperty(LP.occurrenceCountPR);
				timeRan += siblingOccurrenceCountPR == -1l ? 0l : siblingOccurrenceCountPR;
			}
		}
		catch (IllegalArgumentException e) {
			//Do nothing if they have no parent occurrence, mean they themselves is the ultimate parent.
		}

		try {
			ArrayList<Vertex> children = Util.traverse(expMainGeneral, Direction.IN, DBCN.E.occurrence);
			for (Vertex v : children) {
				if (!v.getCName().equals(DBCN.V.general.exp.cn))
					throw new IllegalStateException("Invalid type: " + v.getCName());
				long childrenOccurrenceCountPR = v.getProperty(LP.occurrenceCountPR);
				timeRan += childrenOccurrenceCountPR == -1l ? 0l : childrenOccurrenceCountPR;
			}
		}
		catch (IllegalArgumentException e) {
			//Do nothing if they have no children occurrence.
		}
		return timeRan;
	}

	/**
	 * Convert arrayList of string into a string separated by spaces instead of the conventional [element, element, ...] format of default
	 * java toString() of arrayList.
	 * @param stringArr
	 * @return
	 */
	public static String arrayListOfStringToStringSeparatedBySpace(ArrayList<String> stringArr) {
		//http://stackoverflow.com/questions/4389480/print-array-without-brackets-and-commas
		return stringArr.toString()
			    .replace(",", "")  //remove the commas
			    .replace("[", "")  //remove the right bracket
			    .replace("]", "")  //remove the left bracket
			    .trim();           //remove trailing spaces from partially initialized arrays
	}


	public static void sleep(long milli) {
		try {
			Thread.sleep(milli);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static byte[] concatByteArray(byte[] head, byte[] tail) {
		//http://stackoverflow.com/questions/5513152/easy-way-to-concatenate-two-byte-arrays
		byte[] result = new byte[head.length + tail.length];
		System.arraycopy(head, 0, result, 0, head.length);
		System.arraycopy(tail, 0, result, head.length, tail.length);
		return result;
	}

	public static DBCredentialConfig loadDBCredentialFromFile(String DBCredentialConfigFilePath) {
		DBCredentialConfig config = null;
		YamlReader reader = null;
		try {
			reader = new YamlReader(new FileReader(DBCredentialConfigFilePath));
			config = reader.read(DBCredentialConfig.class);
		}
		catch (FileNotFoundException e) {
			throw new IllegalArgumentException("Given config file not found:" + DBCredentialConfigFilePath);
		}
		catch (YamlException e) {
			throw new IllegalArgumentException("Corrupted config file:" + DBCredentialConfigFilePath + "/nOriginal message" + e.getMessage());
		}
		return config;
	}

	/**
	 * Get the next index cluster to insert into.
	 * @param indexClass
	 * @param txGraph
	 * @return The index cluster class name to be inserted into.
	 */
	public static String WMSTMInsertGetClusterName(String indexClass, Graph txGraph) {
		//Index cluster name is composed of the indexName + clusterId. Without cluster id it would be its config vertex.
		Vertex configVertex = txGraph.getFirstVertexOfClass(indexClass);
		int currentInsertIndex = configVertex.getProperty(LP.currentInsertClusterIndex);
		return indexClass + currentInsertIndex;
	}

	/**
	 * Increment GCACount to automate cluster switching logic.
	 * Call only once for each new GCA frame before importing (inserting) them.
	 * Will handle cluster switching, deletion and update operations.
	 * @param indexClass
	 * @param txGraph
	 */
	public static void WMSTMInsertIncrementGCACount(String indexClass, Graph txGraph) {
		Vertex configVertex = txGraph.getFirstVertexOfClass(indexClass);
		int maxElementCount = configVertex.getProperty(LP.elementPerCluster);
		int currentElementCount = configVertex.getProperty(LP.currentInsertClusterElementCount);
		int currentInsertIndex = configVertex.getProperty(LP.currentInsertClusterIndex);

		//Element has maxed out, switch to next cluster.
		if (currentElementCount == maxElementCount) {
			//Reset currentElementCount as we had switched the cluster.
			currentElementCount = 0;

			int maxCluster = configVertex.getProperty(LP.maxClusterCount);

			//Rollover to the beginning if the cluster are all used, to create a circular index loop.
			if (currentInsertIndex + 1 == maxCluster) {
				currentInsertIndex = 0;

			}
			//Increment by 1 to move to the next cluster, rollover not necessary as their is still trailing cluster available.
			else {
				currentInsertIndex += 1;
			}

			//Remove all record for the given index to mark them as outdated from WM STM.
			txGraph.directQueryExpectVoid("DELETE FROM INDEX:" + indexClass + currentInsertIndex);
		}
		//These variable should only be modified by the logic here, thus over increment of elementCount is impossible, must be external interference.
		else if (currentElementCount > maxElementCount) {
			throw new IllegalStateException("External code modified WM config index system! currentElementCount:" + currentElementCount +
					"; maxElementCount:" + maxElementCount + "; indexClassName:" + indexClass);
		}

		txGraph.begin();
		configVertex.setProperty(LP.currentInsertClusterElementCount, currentElementCount + 1);
		configVertex.setProperty(LP.currentInsertClusterIndex, currentInsertIndex);
		txGraph.finalizeTask();
	}

	/**
	 * Get all the index cluster's class name for the given indexClass in ordered form FIFO.
	 * The latest will be the last class.
	 * @param indexClass
	 * @param txGraph
	 * @return
	 */
	public static ArrayList<String> WMSTMGetClusterName(String indexClass, Graph txGraph) {
		//Generate the className based on the number of maximum cluster size fetched from the config vertex,
		//it is guaranteed to have that amount of index classes.
		Vertex configVertex = txGraph.getFirstVertexOfClass(indexClass);
		int maxCluster = configVertex.getProperty(LP.maxClusterCount);
		int currentClusterIndex = configVertex.getProperty(LP.currentInsertClusterIndex);
		ArrayList<String> result = new ArrayList<String>(maxCluster);

		//Generate the class name by FIFO order. Current cluster index is the latest (last) index.
		//EG: 3 is last, max 10. Then should output 4, 5, 6, 7, 8, 9, 0, 1, 2;
		for (int i=currentClusterIndex + 1; i<maxCluster; i++)
			result.add(indexClass + i);
		for (int i=0; i<currentClusterIndex + 1; i++)
			result.add(indexClass + i);
		return result;
	}
}
