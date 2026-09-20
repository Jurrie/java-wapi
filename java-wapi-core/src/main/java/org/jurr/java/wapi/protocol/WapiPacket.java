package org.jurr.java.wapi.protocol;

import java.util.Arrays;

/**
 * Immutable packet: the bytes received from or sent to the console. The array is copied in and out,
 * and two packets are equal when their bytes are.
 *
 * @param payload non-null packet bytes
 */
public record WapiPacket(byte[] payload)
{
	/**
	 * Copies the payload in.
	 *
	 * @param payload non-null packet bytes
	 * @throws NullPointerException if {@code payload} is {@code null}
	 */
	public WapiPacket
	{
		payload = payload.clone();
	}

	/** {@return a copy of the packet bytes} */
	@Override
	public byte[] payload()
	{
		return payload.clone();
	}

	/** {@return payload length in bytes} */
	public int length()
	{
		return payload.length;
	}

	@Override
	public boolean equals(final Object other)
	{
		return other instanceof WapiPacket packet && Arrays.equals(payload, packet.payload);
	}

	@Override
	public int hashCode()
	{
		return Arrays.hashCode(payload);
	}

	@Override
	public String toString()
	{
		return "WapiPacket[" + WapiBinaryDataCodec.encodeHex(payload) + "]";
	}
}
