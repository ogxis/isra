package isradatabase;

import com.tinkerpop.blueprints.impls.orient.OrientGraphFactory;

/**
 * Graph factory instance, provide graph instance to user, directly connected to database.
 */
public class GraphFactory {
	private OrientGraphFactory factory;

	public GraphFactory(String iURL) {
		factory = new OrientGraphFactory(iURL);
	}

	public Graph getTx() {
		return new Graph(factory.getTx());
	}
	public void setAutoStartTx(boolean autoStartTx) {
		factory.setAutoStartTx(autoStartTx);
	}
	public void setupPool(int iMin, int iMax) {
		factory.setupPool(iMin, iMax);
	}
	public void close() {
		factory.close();
	}
}