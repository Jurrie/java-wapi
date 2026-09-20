package org.jurr.java.wapi.client;

import org.jurr.java.wapi.meter.WapiMeterData;

/** Callback for meter reports received by a {@link WapiClient}. C has no such callback. */
public interface WapiMeterListener
{
	/**
	 * Handles one meter report. Called on the meter dispatcher thread, in the order the reports
	 * arrived; WING sends about 20 reports per second for each request.
	 *
	 * @param report non-null decoded report
	 */
	void onMeters(WapiMeterData report);
}
