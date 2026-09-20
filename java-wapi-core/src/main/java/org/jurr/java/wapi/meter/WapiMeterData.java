package org.jurr.java.wapi.meter;

import java.util.ArrayList;
import java.util.List;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.protocol.ByteUtil;

/**
 * Decoded raw meter report: request ID followed by signed big-endian 16-bit values. The values follow
 * the requested groups in request order, with the number of values per group from PDF Table 5 (for
 * example 8 for a channel, 10 for an FX processor, 6 for the monitor), as confirmed with a console.
 *
 * @param requestId request identifier decoded from the first four bytes
 * @param rawValues signed raw meter values in datagram order; stored as an unmodifiable copy
 */
public record WapiMeterData(int requestId, List<Integer> rawValues)
{
	/**
	 * Copies the values.
	 *
	 * @param requestId request identifier decoded from the first four bytes
	 * @param rawValues signed raw meter values in datagram order; stored as an unmodifiable copy
	 */
	public WapiMeterData
	{
		rawValues = List.copyOf(rawValues);
	}

	/**
	 * Decodes a meter datagram containing a four-byte request ID and zero or more
	 * signed big-endian 16-bit meter values.
	 *
	 * @param bytes non-null report with at least four bytes and an even number of bytes after the request ID
	 * @return decoded report with immutable raw values
	 * @throws WapiException if the report is null or has an invalid length
	 */
	public static WapiMeterData decode(final byte[] bytes)
	{
		if (bytes == null || bytes.length < 4 || (bytes.length - 4 & 1) != 0)
		{
			throw new WapiException("Invalid meter report");
		}
		final int requestId = ByteUtil.readSignedInt(bytes, 0);
		final var values = new ArrayList<Integer>(bytes.length / 2 - 2);
		for (var offset = 4; offset < bytes.length; offset += 2)
		{
			values.add((int) ByteUtil.readSignedShort(bytes, offset));
		}
		return new WapiMeterData(requestId, values);
	}

	/**
	 * Converts each raw meter value to the documented dB scale by dividing by 256. This is only meaningful
	 * for level meters: gain values (such as gate gain and dyn gain) use their own scale (PDF: "1.0 (256)
	 * maps to 20 dB gain reduction"), and LEDs and states are 0 or 1. The lowest value, -32768
	 * ({@code 0x8000}), means no signal and converts to -128.0.
	 *
	 * @return unmodifiable list of converted values in datagram order
	 */
	public List<Float> decibelValues()
	{
		final var result = new ArrayList<Float>();
		for (final var value : rawValues)
		{
			result.add(value / 256.0F);
		}
		return List.copyOf(result);
	}
}
