package org.jurr.java.wapi.client;

import org.jurr.java.wapi.WapiEvent;

/** Callback for decoded unsolicited token events accepted by a {@link WapiClient}. */
public interface WapiEventListener
{
	/**
	 * Handles one decoded token event.
	 *
	 * @param event non-null event delivered by the client
	 */
	void onEvent(WapiEvent event);
}