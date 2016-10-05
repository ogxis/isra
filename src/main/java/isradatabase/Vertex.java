package isradatabase;

import java.util.ArrayList;

import com.tinkerpop.blueprints.impls.orient.OrientVertex;

import linkProperty.LinkProperty.LP;
import logger.Logger.LPLOG;

/**
 * Graph basic storage element, vertex.
 */
public class Vertex {
	private OrientVertex ov;
	public Vertex(OrientVertex givenOv) {
		ov = givenOv;
	}

	/**
	 * Get the backend vertex, required for adding edges.
	 * @return
	 */
	protected OrientVertex getBackend() {
		return ov;
	}

	public <T> T getProperty(LP linkProperty) {
		return ov.getProperty(linkProperty.toString());
	}
	public void setProperty(LP linkProperty, Object property) {
		ov.setProperty(linkProperty.toString(), property);
	}
	public void setProperty(LPLOG logProperty, Object property) {
		ov.setProperty(logProperty.toString(), property);
	}
	public ArrayList<Vertex> getVertices(Direction direction, String edgeLabel) {
		Iterable<com.tinkerpop.blueprints.Vertex> vertexes = ov.getVertices(direction.backendConvert(), edgeLabel);
		return DBUtil.backendIterableVtoIsraArraylistV(vertexes);
	}
	public ArrayList<Edge> getEdges(Direction direction, String edgeLabel) {
		Iterable<com.tinkerpop.blueprints.Edge> edges = ov.getEdges(direction.backendConvert(), edgeLabel);
		return DBUtil.backendIterableEtoIsraArraylistE(edges);
	}
	public void remove() {
		ov.remove();
	}
	public Edge addEdge(String edgeName, Vertex targetVertex) {
		return DBUtil.backendEtoIsraE( ov.addEdge(edgeName, targetVertex.getBackend()) );
	}

	/**
	 * Get class name.
	 */
	public String getCName() {
		return ov.getLabel();
	}
	/**
	 * Get Record ID, uniquely identifiable.
	 */
	public String getRid() {
		return ov.getIdentity().toString();
	}

	@Override
    public String toString() {
        return "[" + getCName() + "@" + getRid() + "]";
    }
}