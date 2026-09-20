package org.jurr.java.wapi.client;

import org.jurr.java.wapi.WapiException;

/**
 * Callback for a connection that ended without the application closing it. C has no such callback;
 * C only notices a lost connection in the next call that fails.
 */
public interface WapiConnectionListener
{
	/**
	 * Handles a lost connection: the console closed it, a read failed, or a protocol error made the
	 * client disconnect. Called on the dispatcher thread, after the listeners of the events that
	 * arrived before; not called for the application's own {@link WapiClient#wClose()} or
	 * {@link WapiClient#wOpen(String)}. To reconnect, call {@link WapiClient#wOpen()}.
	 *
	 * @param cause non-null reason the connection ended
	 */
	void onConnectionLost(WapiException cause);
}
