package rfpp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.faces.application.StateManager;
import javax.faces.component.UIViewRoot;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;

import org.ajax4jsf.application.AjaxStateManager;
import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This StateManager implementation with implement three performance changes: 1)
 * implement serialization of the JSF view state 2) implement compression of the
 * JSF view state 3) reduce the number of view states stored in session
 * 
 * @author Greg Heidorn
 * @author Andy Carson
 * @version $Id$
 */
public class PerformantStateManager extends AjaxStateManager {

	private static final Log LOG = LogFactory.getLog(PerformantStateManager.class);

	static {
		System.out.println("*** Using " + PerformantStateManager.class.getName() + " ***");
	}

	private static final String VIEW_STATES_MAP = AjaxStateManager.class.getName() + ".VIEW_STATES_MAP";
	private static final String VIEW_SEQUENCE = AjaxStateManager.class.getName() + ".VIEW_SEQUENCE";

	public PerformantStateManager(StateManager parent) {
		super(parent);
	}

	@Override
	protected StateManager.SerializedView saveStateInSession(FacesContext context, Object treeStructure, Object state) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("calling saveStateInSession");
		}

		StateManager.SerializedView serializedView;
		UIViewRoot viewRoot = context.getViewRoot();
		ExternalContext externalContext = context.getExternalContext();
		Object session = externalContext.getSession(true);
		synchronized (session) {
			LRUMap viewStates = (LRUMap) externalContext.getSessionMap().get(VIEW_STATES_MAP);

			if (null == viewStates) {
				viewStates = new LRUMap(getNumberOfViews(externalContext));
				externalContext.getSessionMap().put(VIEW_STATES_MAP, viewStates);
			}

			Object id = getNextViewId(context);
			LRUMap logicalViewsMap = (LRUMap) viewStates.get(viewRoot.getViewId());

			if (null == logicalViewsMap) {
				logicalViewsMap = new LRUMap(getNumberOfViews(externalContext));
			}

			viewStates.put(viewRoot.getViewId(), logicalViewsMap);
			logicalViewsMap.put(id, serializeView(treeStructure, state));
			serializedView = new SerializedView(id, null);
		}
		return serializedView;
	}

	@Override
	protected Object[] restoreStateFromSession(FacesContext context, String viewId, String renderKitId) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("calling restoreStateFromSession");
		}
		Object restoredState = null;
		Object id = getRenderKit(context, renderKitId).getResponseStateManager().getTreeStructureToRestore(context, viewId);

		ExternalContext externalContext = context.getExternalContext();
		Object session = externalContext.getSession(false);
		if (null == session) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Can't restore view state : session expired");
			}
		} else {
			synchronized (session) {
				LRUMap viewStates = (LRUMap) externalContext.getSessionMap().get(VIEW_STATES_MAP);
				if (null != viewStates) {
					LRUMap logicalStates = (LRUMap) viewStates.get(viewId);
					if (null != logicalStates) {
						if (null != id) {
							restoredState = logicalStates.get(id);
							externalContext.getRequestMap().put(VIEW_SEQUENCE, id);
							if (restoredState == null) {
								if (LOG.isDebugEnabled()) {
									LOG.debug("No saved view state found for a Id " + id + ". Restore last saved state");
								}
								return retrieveStateUsingLastkey(logicalStates);
							} else if (restoredState instanceof byte[]) {
								// reverse compression and serialization
								if (LOG.isDebugEnabled()) {
									LOG.debug("found instance of byte[] in logicalStates by id " + id + " with length " + ((byte[]) restoredState).length);
								}
								return deserializeView(restoredState);
							} else if (restoredState instanceof Object[]) {
								if (LOG.isDebugEnabled()) {
									LOG.debug("found instance of Object[] in logicalStates by id " + id);
								}
								// no manipulation needed
								return (Object[]) restoredState;
							}
							if (LOG.isDebugEnabled()) {
								LOG.debug("could not determine type of object stored in session " + restoredState.toString());
							}
							throw new RuntimeException("restoring state from serialization failed");
						} else {
							if (LOG.isDebugEnabled()) {
								LOG.debug("id was null");
							}
							return retrieveStateUsingLastkey(logicalStates);
						}
					} else {
						if (LOG.isDebugEnabled()) {
							LOG.debug("logicalStates was null");
						}
					}
				} else {
					if (LOG.isDebugEnabled()) {
						LOG.debug("viewstates was null");
					}
				}
			}
		}
		return null;
	}

	private Object[] retrieveStateUsingLastkey(LRUMap logicalStates) {
		Object lastState = logicalStates.get(logicalStates.lastKey());
		if (lastState instanceof byte[]) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("found instance of byte[] as last state with length " + ((byte[]) lastState).length);
			}
			return deserializeView(lastState);
		} else if (lastState instanceof Object[]) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("found instance of Object[] as last state");
			}
			return (Object[]) lastState;
		} else {
			if (LOG.isDebugEnabled()) {
				LOG.debug("unexpected type found in logicalStates");
			}
			throw new RuntimeException("");
		}
	}

	private Object[] deserializeView(Object restoredState) {
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream((byte[]) restoredState);
			InputStream is = bais;
			is = new GZIPInputStream(is);
			ObjectInputStream in = new ObjectInputStream(is);
			Object obj1 = in.readObject();
			Object obj2 = in.readObject();
			if (LOG.isDebugEnabled()) {
				LOG.debug("deserialized and decompressed " + obj1.getClass().getName() + " and " + obj2.getClass().getName());
				LOG.debug("stream availability: " + in.available());
			}
			return new Object[] { obj1, obj2 };
		} catch (IOException e) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Exiting deserializeView - Could not deserialize state: " + e.getMessage());
			}
		} catch (ClassNotFoundException e) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Exiting deserializeView - Could not deserialize state: " + e.getMessage());
			}
		}
		return null;
	}

	/**
	 * Use this method to serialize and compress the view state.
	 * 
	 * @param structure
	 * @param state
	 * @return
	 */
	private Object serializeView(Object structure, Object state) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("forcing serialization and compression");
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
		try {
			OutputStream os = baos;
			os = new GZIPOutputStream(os, 1024);

			ObjectOutputStream out = new ObjectOutputStream(os);
			out.writeObject(structure);
			out.writeObject(state);
			out.close();
			baos.close();

			if (LOG.isDebugEnabled()) {
				LOG.debug("serializedView - bytes = " + baos.size());
			}
			return baos.toByteArray();
		} catch (IOException e) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("could not serialize state: " + e.getMessage());
			}
			return new Object[] { structure, state };
		}
	}

	@Override
	protected int getNumberOfViews(ExternalContext externalContext) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("hardcoded 3 views for VIEW_STATES_IN_SESSION");
		}
		return 3;
	}
}
