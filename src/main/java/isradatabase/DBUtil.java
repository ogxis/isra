package isradatabase;

import java.util.ArrayList;

import com.tinkerpop.blueprints.impls.orient.OrientEdge;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;

/**
 * DB Wrapper Utilities, to convert backend component into wrapped component.
 * TODO: OServerAdmin is not implemented yet as it is strictly OrientDB only feature, just leave it there for now.
 */
public abstract class DBUtil {
	/**
	 * Convert blueprint's vertex into OrientDB vertex then to israVertex.
	 */
	public static Vertex backendVtoIsraV(com.tinkerpop.blueprints.Vertex vertex) {
		return new Vertex( (OrientVertex) vertex);
	}
	/**
	 * Convert iterables of blueprint's vertexes into OrientDB vertex then to ArrayList of israVertex.
	 */
	public static ArrayList<Vertex> backendIterableVtoIsraArraylistV(Iterable<com.tinkerpop.blueprints.Vertex> vertexes) {
		ArrayList<Vertex> result = new ArrayList<Vertex>();
		for (com.tinkerpop.blueprints.Vertex v : vertexes)
			result.add(backendVtoIsraV(v));
		return result;
	}
	/**
	 * Convert blueprint's edge into OrientDB edge then to israEdge.
	 */
	public static Edge backendEtoIsraE(com.tinkerpop.blueprints.Edge edge) {
		return new Edge( (OrientEdge)edge);
	}
	/**
	 * Convert iterables of blueprint's edges into OrientDB edges then to ArrayList of israEdges.
	 */
	public static ArrayList<Edge> backendIterableEtoIsraArraylistE(Iterable<com.tinkerpop.blueprints.Edge> edges) {
		ArrayList<Edge> result = new ArrayList<Edge>();
		for (com.tinkerpop.blueprints.Edge e : edges)
			result.add(backendEtoIsraE(e));
		return result;
	}
}
