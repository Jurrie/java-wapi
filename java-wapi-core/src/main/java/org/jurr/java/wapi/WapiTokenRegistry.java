package org.jurr.java.wapi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Insertion-ordered lookup of tokens by serialized name and 32-bit hash. */
public class WapiTokenRegistry
{
	private final Map<String, WapiToken> tokens = new LinkedHashMap<>();
	private final Map<Integer, WapiToken> tokensByHash = new LinkedHashMap<>();

	/** Creates an empty registry. */
	public WapiTokenRegistry()
	{
	}

	/**
	 * Registers a token under both its name and hash, replacing any existing entry
	 * for either key.
	 *
	 * @param token token to register; must not be {@code null}
	 * @throws WapiException if {@code token} is {@code null}
	 */
	public void register(final WapiToken token)
	{
		if (token == null)
		{
			throw new WapiException("Token cannot be null");
		}
		if (token.hasKnownName())
		{
			tokens.put(token.name(), token);
			tokensByHash.put(token.hash(), token);
		}
		else
		{
			tokensByHash.putIfAbsent(token.hash(), token);
		}
	}

	/**
	 * Finds a token by exact serialized name.
	 *
	 * @param name name to look up; {@code null} and empty names are treated as absent
	 * @return the registered token, or {@code null} when no entry matches
	 */
	public WapiToken findByName(final String name)
	{
		if (name == null || name.isEmpty())
		{
			return null;
		}

		if (WapiToken.UNKNOWN_NAME.equals(name))
		{
			throw new WapiException("Token name cannot be " + WapiToken.UNKNOWN_NAME);
		}

		return tokens.get(name);
	}

	/**
	 * Finds a token by its 32-bit hash.
	 *
	 * @param hash hash to look up
	 * @return the registered token, or {@code null} when no entry matches
	 */
	public WapiToken findByHash(final int hash)
	{
		return tokensByHash.get(hash);
	}

	/**
	 * Finds a token by hash or creates an opaque token when the hash is not known.
	 * Opaque tokens are indexed only by hash until a named token is registered.
	 *
	 * @param hash token hash to look up
	 * @return existing token or a new hash-only token
	 */
	public WapiToken findOrCreateByHash(final int hash)
	{
		var token = findByHash(hash);
		if (token != null)
		{
			return token;
		}

		token = new WapiToken(hash);
		register(token);
		return findByHash(hash);
	}

	/**
	 * Returns the registered token for a name or creates one using its canonical hash.
	 *
	 * @param name non-null, non-empty serialized token name
	 * @return the existing or newly registered token
	 * @throws WapiException if {@code name} is {@code null} or empty
	 */
	public WapiToken findOrCreateByName(final String name)
	{
		if (name == null || name.isEmpty())
		{
			throw new WapiException("Token name cannot be null or empty");
		}

		if (WapiToken.UNKNOWN_NAME.equals(name))
		{
			throw new WapiException("Token name cannot be " + WapiToken.UNKNOWN_NAME);
		}

		var token = findByName(name);
		if (token != null)
		{
			return token;
		}

		token = new WapiToken(name);
		register(token);
		return token;
	}

	/**
	 * Returns an unmodifiable live view of tokens keyed by name.
	 *
	 * @return unmodifiable map view of named tokens, reflecting subsequent registry updates
	 */
	public Map<String, WapiToken> asMap()
	{
		return Collections.unmodifiableMap(tokens);
	}
}
