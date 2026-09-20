package org.jurr.java.wapi.meter;

import java.io.ByteArrayOutputStream;
import java.util.stream.IntStream;

import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;

/** Encodes the documented Channel 3 meter request definition. */
public final class WapiMeterRequest
{
	private final int requestId;
	private final int udpPort;
	private final ByteArrayOutputStream collection = new ByteArrayOutputStream();

	/**
	 * Starts a meter subscription request for a UDP destination.
	 *
	 * @param requestId non-negative request identifier
	 * @param udpPort destination port from 0 through 65535
	 * @throws IllegalArgumentException if the request ID or port is negative or the port exceeds 65535
	 */
	public WapiMeterRequest(final int requestId, final int udpPort)
	{
		if (requestId < 0 || udpPort < 0 || udpPort > 0xFFFF)
		{
			throw new IllegalArgumentException("Invalid meter request values");
		}
		this.requestId = requestId;
		this.udpPort = udpPort;
	}

	/** {@return the request identifier, which the reports carry and a renewal repeats} */
	public int requestId()
	{
		return requestId;
	}

	/** Groups of the C {@code wSetMetersRequest} bitmask in table order, with their width in bytes. */
	private static final WapiMeterGroup[] BITMASK_GROUPS = {
			WapiMeterGroup.CHANNEL, WapiMeterGroup.AUX, WapiMeterGroup.BUS, WapiMeterGroup.MAIN, WapiMeterGroup.MATRIX,
			WapiMeterGroup.DCA, WapiMeterGroup.FX, WapiMeterGroup.SOURCE, WapiMeterGroup.OUTPUT, WapiMeterGroup.MONITOR,
			WapiMeterGroup.RTA, WapiMeterGroup.CHANNEL_V2, WapiMeterGroup.AUX_V2, WapiMeterGroup.BUS_V2, WapiMeterGroup.MAIN_V2,
			WapiMeterGroup.MATRIX_V2
	};
	private static final int[] BITMASK_WIDTHS = { 5, 1, 2, 1, 1, 2, 2, 2, 2, 1, 1, 5, 1, 2, 1, 1 };
	private static final int BITMASK_LENGTH = 30;

	/**
	 * Creates a request from the 30-byte selection bitmask of C {@code wSetMetersRequest} (PDF table at
	 * "wSetMetersRequest"). Each group has its own bytes, and bit 1 of a group is the most significant
	 * bit of its first byte: {@code {0x80, 0, 0, 0, 0x01, 0, ...}} requests channels 1 and 40. Groups
	 * are added in table order, indexes in ascending order.
	 *
	 * @param requestId non-negative request identifier
	 * @param udpPort destination port from 0 through 65535
	 * @param mask 30-byte selection bitmask
	 * @return the request
	 * @throws IllegalArgumentException if the mask is not 30 bytes, or selects an index outside a
	 *             group's range in PDF Table 4, such as DCA 9 to 16, for which the bitmask has room
	 */
	public static WapiMeterRequest fromBitmask(final int requestId, final int udpPort, final byte[] mask)
	{
		if (mask == null || mask.length != BITMASK_LENGTH)
		{
			throw new IllegalArgumentException("Meter bitmask must be " + BITMASK_LENGTH + " bytes");
		}
		final var request = new WapiMeterRequest(requestId, udpPort);
		var offset = 0;
		for (var g = 0; g < BITMASK_GROUPS.length; g++)
		{
			final var indexes = selectedIndexes(mask, offset, BITMASK_WIDTHS[g]);
			offset += BITMASK_WIDTHS[g];
			if (indexes.length > 0)
			{
				request.addFromBitmask(BITMASK_GROUPS[g], indexes);
			}
		}
		return request;
	}

	/**
	 * @return the one-based numbers of the bits that are set in {@code width} bytes from {@code offset},
	 *         ascending; bit 1 is the most significant bit of the first byte
	 */
	private static int[] selectedIndexes(final byte[] mask, final int offset, final int width)
	{
		return IntStream.range(0, width * 8)
				.filter(bit -> (mask[offset + bit / 8] & 0x80 >>> bit % 8) != 0)
				.map(bit -> bit + 1)
				.toArray();
	}

	/** Adds a group selected in the bitmask; a group without index, such as the monitor, has only bit 1. */
	private void addFromBitmask(final WapiMeterGroup group, final int[] indexes)
	{
		if (group.hasIndex())
		{
			addGroup(group, indexes);
			return;
		}
		if (indexes.length != 1 || indexes[0] != 1)
		{
			throw new IllegalArgumentException("Meter group " + group + " has only bit 1");
		}
		addGroup(group);
	}

	/**
	 * Adds one meter collection group to this request. The group token is followed by its
	 * indexes, as in the PDF example {@code dc a0 00 01 08 a6 04 de} (channels 1, 2 and 9, fx 5).
	 *
	 * @param group non-null meter group
	 * @param indexes one-based indexes from 1 through {@link WapiMeterGroup#getMaxIndex()}; at least one
	 *            for a group with indexes, none for {@link WapiMeterGroup#MONITOR} and {@link WapiMeterGroup#RTA}
	 * @return this request, for fluent construction
	 * @throws IllegalArgumentException if the group or indexes are null, or the indexes do not match the group
	 */
	public WapiMeterRequest addGroup(final WapiMeterGroup group, final int... indexes)
	{
		if (group == null || indexes == null)
		{
			throw new IllegalArgumentException("Meter group and indexes are required");
		}
		if (group.hasIndex() == (indexes.length == 0))
		{
			throw new IllegalArgumentException(group.hasIndex()
					? "Meter group " + group + " requires at least one index"
					: "Meter group " + group + " takes no index");
		}
		for (final int index : indexes)
		{
			if (index < 1 || index > group.getMaxIndex())
			{
				throw new IllegalArgumentException("Meter group " + group + " index must be between 1 and " + group.getMaxIndex() + ": " + index);
			}
		}
		collection.write(group.getToken());
		for (final int index : indexes)
		{
			collection.write(index - 1);
		}
		return this;
	}

	/**
	 * Encodes the request ID, UDP port, and groups in the Channel 3 meter definition format.
	 *
	 * @return newly allocated encoded bytes; subsequent changes to this builder do not alter this array
	 */
	public byte[] encode()
	{
		final var bytes = new ByteArrayOutputStream();
		bytes.write(WapiBinaryStreamCodec.TOKEN_INT16);
		bytes.write(udpPort >>> 8 & 0xFF);
		bytes.write(udpPort & 0xFF);
		bytes.write(WapiBinaryStreamCodec.TOKEN_INT32);
		bytes.write(requestId >>> 24 & 0xFF);
		bytes.write(requestId >>> 16 & 0xFF);
		bytes.write(requestId >>> 8 & 0xFF);
		bytes.write(requestId & 0xFF);
		bytes.write(WapiBinaryStreamCodec.TOKEN_DATA_REQUEST);
		final byte[] definition = collection.toByteArray();
		bytes.write(definition, 0, definition.length);
		bytes.write(WapiBinaryStreamCodec.TOKEN_END_DATA);
		return bytes.toByteArray();
	}
}
