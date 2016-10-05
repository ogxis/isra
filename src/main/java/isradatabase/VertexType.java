package isradatabase;

import com.tinkerpop.blueprints.impls.orient.OrientVertexType;

import linkProperty.LinkProperty.LP;
import logger.Logger.LPLOG;

/**
 * Vertex's class representation. Used to configure its semantic.
 */
public class VertexType {
	private OrientVertexType ovt;

	protected VertexType(OrientVertexType givenOvt) {
		ovt = givenOvt;
	}

	public void createProperty(LP propertyName, PropertyType propertyType) {
		ovt.createProperty(propertyName.toString(), propertyType.backendConvert());
	}
	public void createProperty(LPLOG propertyName, PropertyType propertyType) {
		ovt.createProperty(propertyName.toString(), propertyType.backendConvert());
	}
	public void createProperty(String propertyName, PropertyType propertyType) {
		ovt.createProperty(propertyName.toString(), propertyType.backendConvert());
	}
}