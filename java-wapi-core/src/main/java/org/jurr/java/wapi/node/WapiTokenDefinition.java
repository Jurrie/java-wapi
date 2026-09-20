package org.jurr.java.wapi.node;

import java.util.Arrays;
import java.util.Objects;

import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.protocol.ByteUtil;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;

/**
 * Result of C {@code wGetTokenDef}: the node-definition response of a token, as C returns it
 * ({@link #str()}, {@link #num()}), as received ({@link #rawPayload()}) and parsed
 * ({@link #nodeDefinition()}, which also holds enum values and other metadata).
 *
 * @param token associated token; may be null
 * @param rawPayload raw response bytes; null is normalized to an empty array; copied in and out
 * @param nodeDefinition parsed node definition; may be null
 */
public record WapiTokenDefinition(WapiToken token, byte[] rawPayload, WapiNodeDefinition nodeDefinition)
{
	/**
	 * Copies the raw payload in; {@code null} becomes an empty array.
	 *
	 * @param token associated token; may be null
	 * @param rawPayload raw response bytes; null is normalized to an empty array; copied in and out
	 * @param nodeDefinition parsed node definition; may be null
	 */
	public WapiTokenDefinition
	{
		rawPayload = rawPayload == null ? new byte[0] : rawPayload.clone();
	}

	/** {@return the number of bytes in {@link #str()}, like {@code num} of C {@code wGetTokenDef}} */
	public int num()
	{
		return str().length;
	}

	/**
	 * Returns the definition the way C {@code wGetTokenDef} returns it in {@code str}: the response
	 * from its {@code index} field on, without {@code 0xdf}, the length, the parent and the hash, and
	 * followed by the end-of-data token {@code 0xde} that ends the console's reply (captured with
	 * libwapi.a for {@code ch.1.fdr}: {@code 0000 03 666472 05 4641444552 0031 de}).
	 *
	 * @return the C form of the definition, or an empty array without a definition
	 */
	public byte[] str()
	{
		if (rawPayload.length < 3)
		{
			return new byte[0];
		}
		// df len.w [len.l] parent.l hash.l
		final var header = 1 + 2 + (ByteUtil.readUnsignedShort(rawPayload, 1) == 0 ? 4 : 0) + 4 + 4;
		if (rawPayload.length < header)
		{
			return new byte[0];
		}
		final byte[] str = Arrays.copyOfRange(rawPayload, header, rawPayload.length + 1);
		str[str.length - 1] = (byte) WapiBinaryStreamCodec.TOKEN_END_DATA;
		return str;
	}

	/** {@return a copy of the raw response bytes} */
	@Override
	public byte[] rawPayload()
	{
		return rawPayload.clone();
	}

	@Override
	public boolean equals(final Object other)
	{
		return other instanceof WapiTokenDefinition definition && Objects.equals(token, definition.token)
				&& Arrays.equals(rawPayload, definition.rawPayload) && Objects.equals(nodeDefinition, definition.nodeDefinition);
	}

	@Override
	public int hashCode()
	{
		return 31 * Objects.hash(token, nodeDefinition) + Arrays.hashCode(rawPayload);
	}

	@Override
	public String toString()
	{
		return "WapiTokenDefinition[token=" + token + ", nodeDefinition=" + nodeDefinition + "]";
	}
}
