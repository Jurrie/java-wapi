package org.jurr.java.wapi.node;

import java.util.Arrays;
import java.util.Objects;

/**
 * Parsed node-definition record and its decoded flags and optional metadata. Two definitions are equal
 * when all their fields, including the raw bytes, are equal.
 *
 * @param length encoded definition length
 * @param parentHash hash of the parent node as an unsigned 32-bit value; 0 for a top-level node
 * @param hash node hash as an unsigned 32-bit value
 * @param index number by which the node is addressed, or 0 when it is addressed by name
 * @param name short node name, possibly {@code null}
 * @param longName descriptive node name, possibly {@code null}
 * @param flags raw node flags
 * @param metadata extended metadata, or {@code null} when not supplied
 * @param rawBytes the {@code 0xdf} response the definition was parsed from, or an empty array when not
 *            parsed from a response; copied in and out
 */
public record WapiNodeDefinition(int length, long parentHash, long hash, int index, String name, String longName, int flags,
		WapiNodeMetadata metadata, byte[] rawBytes)
{
	/**
	 * Copies the raw bytes in; {@code null} becomes an empty array.
	 *
	 * @param length encoded definition length
	 * @param parentHash hash of the parent node as an unsigned 32-bit value; 0 for a top-level node
	 * @param hash node hash as an unsigned 32-bit value
	 * @param index number by which the node is addressed, or 0 when it is addressed by name
	 * @param name short node name, possibly {@code null}
	 * @param longName descriptive node name, possibly {@code null}
	 * @param flags raw node flags
	 * @param metadata extended metadata, or {@code null} when not supplied
	 * @param rawBytes the {@code 0xdf} response the definition was parsed from, or an empty array when not
	 *            parsed from a response; copied in and out
	 */
	public WapiNodeDefinition
	{
		rawBytes = rawBytes == null ? new byte[0] : rawBytes.clone();
	}

	/**
	 * Creates a node definition without extended metadata or raw bytes.
	 *
	 * @param length encoded definition length
	 * @param parentHash hash of the parent node as an unsigned 32-bit value; 0 for a top-level node
	 * @param hash node hash as an unsigned 32-bit value
	 * @param index number by which the node is addressed, or 0 when it is addressed by name
	 * @param name short node name, possibly {@code null}
	 * @param longName descriptive node name, possibly {@code null}
	 * @param flags raw node flags
	 */
	public WapiNodeDefinition(final int length, final long parentHash, final long hash, final int index, final String name, final String longName, final int flags)
	{
		this(length, parentHash, hash, index, name, longName, flags, null);
	}

	/**
	 * Creates a node definition without raw bytes.
	 *
	 * @param length encoded definition length
	 * @param parentHash hash of the parent node as an unsigned 32-bit value; 0 for a top-level node
	 * @param hash node hash as an unsigned 32-bit value
	 * @param index number by which the node is addressed, or 0 when it is addressed by name
	 * @param name short node name, possibly {@code null}
	 * @param longName descriptive node name, possibly {@code null}
	 * @param flags raw node flags
	 * @param metadata extended metadata, or {@code null} when not supplied
	 */
	public WapiNodeDefinition(final int length, final long parentHash, final long hash, final int index, final String name, final String longName, final int flags,
			final WapiNodeMetadata metadata)
	{
		this(length, parentHash, hash, index, name, longName, flags, metadata, new byte[0]);
	}

	/** {@return node kind decoded from the raw flags} */
	public WapiNodeKind nodeKind()
	{
		return WapiNodeKind.fromFlags(flags);
	}

	/** {@return the low four-bit unit code from the raw flags} */
	public int unitCode()
	{
		return flags & 0x0F;
	}

	/** {@return {@code true} when read-only flag bit 9 is set} */
	public boolean isReadOnly()
	{
		return (flags & 0x0200) != 0;
	}

	/**
	 * Returns the hash of the parent node. Captured definitions show that this field is the parent's
	 * hash, not an index: the children of {@code ch.1} carry the hash of {@code ch.1}.
	 *
	 * @return parent hash as an unsigned 32-bit value; 0 for a top-level node
	 */
	@Override
	public long parentHash()
	{
		return parentHash;
	}

	/**
	 * Returns the number by which the node is addressed. Captured definitions show two kinds of
	 * numbered nodes: {@code ch.1} has an empty name and index 1, and the dynamics parameter
	 * {@code ch.3.dyn.2} has the model-specific name {@code ratio} and index 2 (PDF, "Dynamic
	 * parameters anonymization"). Nodes addressed by name, such as {@code ch.1.fdr}, have index 0.
	 *
	 * @return number by which the node is addressed, or 0 when it is addressed by name
	 */
	@Override
	public int index()
	{
		return index;
	}

	/**
	 * Returns this node's segment in its dotted path, which is also what its hash is computed from:
	 * the index when it is not 0, otherwise the name. For example {@code 1} for {@code ch.1},
	 * {@code fdr} for {@code ch.1.fdr}, and {@code 2} for the dynamics parameter {@code ch.3.dyn.2}
	 * named {@code ratio}.
	 *
	 * @return path segment of this node
	 */
	public String pathSegment()
	{
		return index != 0 ? String.valueOf(index) : name;
	}

	/** {@return a copy of the {@code 0xdf} response bytes, or an empty array when not parsed from a response} */
	@Override
	public byte[] rawBytes()
	{
		return rawBytes.clone();
	}

	@Override
	public boolean equals(final Object other)
	{
		return other instanceof WapiNodeDefinition definition
				&& length == definition.length && parentHash == definition.parentHash && hash == definition.hash
				&& index == definition.index && flags == definition.flags && Objects.equals(name, definition.name)
				&& Objects.equals(longName, definition.longName) && Objects.equals(metadata, definition.metadata)
				&& Arrays.equals(rawBytes, definition.rawBytes);
	}

	@Override
	public int hashCode()
	{
		return 31 * Objects.hash(length, parentHash, hash, index, name, longName, flags, metadata) + Arrays.hashCode(rawBytes);
	}

	@Override
	public String toString()
	{
		return "WapiNodeDefinition[name=" + name + ", longName=" + longName + ", hash=" + Long.toHexString(hash) + ", parentHash="
				+ Long.toHexString(parentHash) + ", index=" + index + ", flags=" + Integer.toHexString(flags) + ", metadata=" + metadata + "]";
	}
}
