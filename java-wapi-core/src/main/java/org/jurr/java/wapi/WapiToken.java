package org.jurr.java.wapi;

import java.util.Objects;

import org.jurr.java.wapi.protocol.ByteUtil;

/**
 * Identifies a WING token by its serialized name, 32-bit hash, and optional declared type. Two tokens
 * are equal when their name, hash, and declared type are equal.
 *
 * @param name serialized token name, or {@value #UNKNOWN_NAME} for a token known only by its hash
 * @param hash the 32-bit token identifier; the hash of the name when the name is known
 * @param type the declared type, or {@link WapiType#UNKN} if none was supplied
 */
public record WapiToken(String name, int hash, WapiType type)
{
	/** Name of a token that is known only by its hash. */
	public static final String UNKNOWN_NAME = "<unknown>";

	/**
	 * Checks the name and hash; a {@code null} type becomes {@link WapiType#UNKN}.
	 *
	 * @param name serialized token name, or {@value #UNKNOWN_NAME} for a token known only by its hash
	 * @param hash the 32-bit token identifier; the hash of the name when the name is known
	 * @param type the declared type, or {@link WapiType#UNKN} if none was supplied
	 * @throws IllegalArgumentException if the name is known and {@code hash} is not its hash
	 */
	public WapiToken
	{
		Objects.requireNonNull(name, "name");
		type = type == null ? WapiType.UNKN : type;
		if (!UNKNOWN_NAME.equals(name) && hash != hashName(name))
		{
			throw new IllegalArgumentException("Hash " + Integer.toHexString(hash) + " is not the hash of " + name);
		}
	}

	/**
	 * Creates a token known only by its hash, with an unspecified type.
	 *
	 * @param hash token hash; stored as supplied
	 */
	public WapiToken(final int hash)
	{
		this(hash, WapiType.UNKN);
	}

	/**
	 * Creates a token known only by its hash.
	 *
	 * @param hash token hash; stored as supplied
	 * @param type declared value type; {@code null} is normalized to {@link WapiType#UNKN}
	 */
	public WapiToken(final int hash, final WapiType type)
	{
		this(UNKNOWN_NAME, hash, type);
	}

	/**
	 * Creates a token with an unspecified type.
	 *
	 * @param name serialized token name; may not be {@code null}. The hash is computed from the name.
	 */
	public WapiToken(final String name)
	{
		this(name, WapiType.UNKN);
	}

	/**
	 * Creates a token from its name and declared type.
	 *
	 * @param name serialized token name; may not be {@code null}. The hash is computed from the name.
	 * @param type declared value type; {@code null} is normalized to {@link WapiType#UNKN}
	 */
	public WapiToken(final String name, final WapiType type)
	{
		this(name, hashName(name), type);
	}

	/** {@return whether the name is known, not {@value #UNKNOWN_NAME}} */
	public boolean hasKnownName()
	{
		return !UNKNOWN_NAME.equals(name);
	}

	/** {@return the hash as four big-endian bytes, as it is sent} */
	public byte[] hashBytes()
	{
		return ByteUtil.toBytes(hash);
	}

	/**
	 * Computes the WING token hash for a serialized token name. ASCII lowercase
	 * letters are folded to uppercase by the hash algorithm.
	 *
	 * @param name token name; {@code null} and the empty string both produce zero
	 * @return the 32-bit token hash
	 */
	static int hashName(final String name)
	{
		if (name == null || name.isEmpty())
		{
			return 0;
		}
		var hash = 0xCB21C6BE;
		var offset = 0;
		while (name.length() - offset >= 4)
		{
			final int block = upper(name.charAt(offset)) << 24
					| upper(name.charAt(offset + 1)) << 16
					| upper(name.charAt(offset + 2)) << 8
					| upper(name.charAt(offset + 3));
			hash = mix(hash, block);
			offset += 4;
		}
		if (offset < name.length())
		{
			var tail = 0;
			while (offset < name.length())
			{
				tail = tail << 8 | upper(name.charAt(offset++));
			}
			hash = mix(hash, tail);
		}
		return hash ^ name.length();
	}

	private static int mix(final int hash, final int value)
	{
		int hashTmp = hash;
		int valueTmp = value;
		valueTmp *= 0xCC9E2D51;
		valueTmp = Integer.rotateLeft(valueTmp, 15);
		valueTmp *= 0x1B873593;
		hashTmp ^= valueTmp;
		return Integer.rotateLeft(hashTmp, 13) * 5 + 0xE6546B64;
	}

	private static int upper(final char value)
	{
		return value >= 'a' && value <= 'z' ? value - ('a' - 'A') : value;
	}

	/** {@return the token name followed by its decimal hash in parentheses} */
	@Override
	public String toString()
	{
		return name + "(" + hash + ", type = " + type + ")";
	}
}
